package store

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/wolfy/server/internal/annotations"
)

// Book — книга библиотеки так, как её хранит сервер.
//
// Сам файл хранится отдельно от строки библиотеки: синхронизация остаётся
// маленьким JSON-обменом, а книга едет защищённым потоковым маршрутом.
type Book struct {
	ID           string `json:"id"`
	Title        string `json:"title"`
	Author       string `json:"author"`
	Format       string `json:"format"`
	SourceKey    string `json:"sourceKey"`
	ChapterCount int    `json:"chapterCount"`
	LastChapter  int    `json:"lastChapter"`
	LastOffset   int    `json:"lastOffset"`
	Shelf        string `json:"shelf"`
	Position     int    `json:"position"`
	Rev          int64  `json:"rev"`
	Deleted      bool   `json:"deleted"`
}

// BookFile — доступность файла в защищённом хранилище. В запросе клиента это
// поле игнорируется; в ответе оно позволяет второму устройству скачать книгу.
type BookFile struct {
	BookID   string `json:"bookId"`
	FileName string `json:"fileName"`
	Size     int64  `json:"size"`
	SHA256   string `json:"sha256"`
}

// Card — слово, фраза или правило вместе с состоянием повторений.
type Card struct {
	ID           string     `json:"id"`
	BookID       string     `json:"bookId"`
	Kind         string     `json:"kind"`
	Surface      string     `json:"surface"`
	Lemma        string     `json:"lemma"`
	Translation  string     `json:"translation"`
	Context      string     `json:"context"`
	Pos          string     `json:"pos"`
	Cefr         string     `json:"cefr"`
	HP           int        `json:"hp"`
	Streak       int        `json:"streak"`
	IntervalDays int        `json:"intervalDays"`
	DueAt        time.Time  `json:"dueAt"`
	ReviewedAt   *time.Time `json:"reviewedAt,omitempty"`
	Rev          int64      `json:"rev"`
	Deleted      bool       `json:"deleted"`
}

// Changes — то, чем обмениваются устройство и сервер.
type Changes struct {
	// Cursor — ревизия, по которую состояние уже согласовано.
	Cursor  int64           `json:"cursor"`
	Books   []Book          `json:"books"`
	Cards   []Card          `json:"cards"`
	Files   []BookFile      `json:"files,omitempty"`
	Reading json.RawMessage `json:"reading,omitempty"`
	// Компаньон — отдельная коллекция со своей ревизией и tombstone.
	// Указатель отличает «поля нет» от «профиль удалён».
	Companion *Companion `json:"companion,omitempty"`
}

// NextRev выдаёт следующую ревизию пользователя.
//
// Все записи одной отправки получают одну и ту же ревизию, и это не небрежность:
// отправка — атомарное событие, и разбивать её на несколько номеров значило бы
// разрешить второму устройству увидеть половину изменения.
//
// Строка user_state заводится здесь же, при первом обращении: регистрация
// происходит в Читавуке, и заводить её заранее Wolfy не может.
func (s *Store) NextRev(ctx context.Context, tx pgx.Tx, userID string) (int64, error) {
	var rev int64
	err := tx.QueryRow(ctx, `
        INSERT INTO wolfy.user_state (user_id, sync_rev)
        VALUES ($1, 1)
        ON CONFLICT (user_id) DO UPDATE
            SET sync_rev = wolfy.user_state.sync_rev + 1,
                updated_at = now()
        RETURNING sync_rev`, userID).Scan(&rev)
	if err != nil {
		return 0, fmt.Errorf("выдача ревизии: %w", err)
	}
	return rev, nil
}

// SaveBooks записывает книги, пришедшие с устройства.
//
// Разрешение конфликта — «побеждает последний записавший», и выбрано оно не от
// лени. Настоящее слияние двух прогрессов чтения невозможно: если человек читал
// одну книгу на телефоне и на компьютере, верного ответа про «где он на самом
// деле остановился» не существует, и любая догадка будет одинаково неверной.
// Последняя отправка хотя бы соответствует тому, что он делал только что.
//
// Единственное осознанное исключение — пометка удаления. Старая копия не имеет
// права снять tombstone только потому, что пришла позже: удаление и «устаревший
// прогресс», присланный с копии, не знавшей об удалении, — разные вещи, и
// различает их ревизия, которую устройство видело. Книга возвращается к жизни
// только когда пришедшая версия подтверждает, что tombstone был виден.
//
// §5 Variant A: детерминированный canonical id из source_key (Rust).
// Две офлайн-книги (id=A, id=B, один HASH) обязаны сойтись к одному
// логическому id = canonical(HASH). Сервер хранит unique (user_id, source_key)
// и не может иметь два id на один HASH. При конфликте source_key с разным id
// выполняется alias-merge: карточки, аннотации и устройства перепривязываются
// к каноническому id, а проигравшая строка помечается удалённой — чтобы
// устройство, у которого она ещё жива, узнало об этом обычным путём. Без
// alias-merge вторая офлайн-отправка падала бы на unique violation с потерей
// прогресса. Кто из двух номеров канонический, решает `canonicalBookID`, а не
// порядок прихода: иначе устройства перекидывали бы книгу друг у друга.
//
// Возвращает карту «присланный номер -> номер, под которым книга легла».
// Она непустая ровно в одном случае — когда присланный номер проиграл
// каноническому, — и её обязан применить вызывающий к остальной посылке:
// карточки той же отправки ссылаются на проигравший номер, которого в базе
// нет и не будет, и без переписывания вся синхронизация упала бы на внешнем
// ключе.
func (s *Store) SaveBooks(ctx context.Context, tx pgx.Tx, userID string, rev int64, books []Book) (map[string]string, error) {
	aliases := map[string]string{}
	for _, book := range books {
		sent := book.ID
		// §5 alias: если такой source_key уже есть под другим id — освобождаем unique перед вставкой,
		// чтобы INSERT не упал на books_user_source_idx, а затем перепривязываем зависимости.
		var oldID string
		var hasOld bool
		if book.SourceKey != "" {
			err := tx.QueryRow(ctx, `
                SELECT id::text FROM wolfy.books
                 WHERE user_id = $1 AND source_key = $2 AND source_key <> '' AND id <> $3::uuid`,
				userID, book.SourceKey, book.ID).Scan(&oldID)
			if err == nil {
				// Победителя выбирает не порядок прихода, а сам source_key:
				// каноническим считается id, который из него выводится.
				//
				// Иначе получалось перекидывание. Устройство со старой копией
				// библиотеки шлёт legacy-номер A — сервер объявлял бы
				// каноническим A и хоронил C; обновлённое устройство при
				// следующем открытии снова приводит книгу к C и шлёт C —
				// сервер хоронит A. И так на каждой синхронизации, каждый раз
				// перетаскивая все карточки книги.
				canonical := canonicalBookID(book.SourceKey)
				if canonical != "" && oldID == canonical && book.ID != canonical {
					// Пришёл не-канонический номер, а канонический уже есть:
					// вливаем присланное в него, чужой номер не заводим.
					book.ID = oldID
					aliases[sent] = oldID
				} else {
					hasOld = true
					if _, err := tx.Exec(ctx, `UPDATE wolfy.books SET source_key='', updated_at=now() WHERE user_id=$1 AND id=$2::uuid`, userID, oldID); err != nil {
						return nil, fmt.Errorf("освобождение source_key %s: %w", oldID, err)
					}
				}
			} else if !errors.Is(err, pgx.ErrNoRows) {
				return nil, fmt.Errorf("проверка source_key %s: %w", book.SourceKey, err)
			}
		}

		var deletedAt any
		if book.Deleted {
			deletedAt = time.Now()
		}
		_, err := tx.Exec(ctx, `
            INSERT INTO wolfy.books (
                id, user_id, title, author, format, source_key, chapter_count,
                last_chapter, last_offset, shelf, position, rev, deleted_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, now())
            ON CONFLICT (id) DO UPDATE SET
                title = excluded.title,
                author = excluded.author,
                format = excluded.format,
                source_key = excluded.source_key,
                chapter_count = excluded.chapter_count,
                last_chapter = excluded.last_chapter,
                last_offset = excluded.last_offset,
                shelf = excluded.shelf,
                position = excluded.position,
                rev = excluded.rev,
                deleted_at = excluded.deleted_at,
                updated_at = now()
            WHERE wolfy.books.user_id = $2
              AND NOT (
                  wolfy.books.deleted_at IS NOT NULL
                  AND excluded.deleted_at IS NULL
                  AND $14::bigint < wolfy.books.rev)`,
			book.ID, userID, book.Title, book.Author, book.Format, book.SourceKey,
			book.ChapterCount, book.LastChapter, book.LastOffset, book.Shelf,
			book.Position, rev, deletedAt, book.Rev)
		if err != nil {
			return nil, fmt.Errorf("запись книги %s: %w", book.ID, err)
		}

		if hasOld {
			if err := s.rebindBookAlias(ctx, tx, userID, rev, oldID, book.ID); err != nil {
				return nil, fmt.Errorf("перепривязка книги %s -> %s: %w", oldID, book.ID, err)
			}
		}
	}
	return aliases, nil
}

// applyBookAliases переписывает ссылки карточек на книгу, проигравшую
// каноническому номеру.
//
// Нужно потому, что порядок записи жёсткий: книги, потом карточки. Книга,
// пришедшая под чужим номером, легла под канонический, а карточки той же
// посылки всё ещё показывают на несуществующий номер — внешний ключ на
// `wolfy.books` уронил бы всю транзакцию, и устройство со старой копией
// библиотеки перестало бы синхронизироваться вовсе.
func applyBookAliases(cards []Card, aliases map[string]string) {
	if len(aliases) == 0 {
		return
	}
	for i := range cards {
		if to, ok := aliases[cards[i].BookID]; ok {
			cards[i].BookID = to
		}
	}
}

// rebindBookAlias перепривязывает карточки, аннотации и устройства со старого
// book id к новому canonical id, разрешая конфликты без потери данных.
// Вызывается внутри транзакции Sync до вставки новой книги.
func (s *Store) rebindBookAlias(ctx context.Context, tx pgx.Tx, userID string, rev int64, oldID, newID string) error {
	// Карточки: удалить дубликаты по (kind, lemma) где lemma непуст и not deleted,
	// чтобы последующий UPDATE не словил unique violation
	if _, err := tx.Exec(ctx, `
        DELETE FROM wolfy.cards AS old
        USING wolfy.cards AS nw
        WHERE old.user_id = $1 AND old.book_id = $2::uuid
          AND nw.user_id = $1 AND nw.book_id = $3::uuid
          AND old.kind = nw.kind AND old.lemma = nw.lemma
          AND old.lemma <> '' AND old.deleted_at IS NULL AND nw.deleted_at IS NULL`,
		userID, oldID, newID); err != nil {
		return fmt.Errorf("чистка дублей карточек: %w", err)
	}
	if _, err := tx.Exec(ctx, `
        UPDATE wolfy.cards SET book_id = $3::uuid
         WHERE user_id = $1 AND book_id = $2::uuid`,
		userID, oldID, newID); err != nil {
		return fmt.Errorf("перепривязка карточек: %w", err)
	}

	// Устройства аннотаций: удалить дубликаты по device_id
	if _, err := tx.Exec(ctx, `
        DELETE FROM wolfy.book_annotations_devices AS old
        USING wolfy.book_annotations_devices AS nw
        WHERE old.user_id = $1 AND old.book_id = $2
          AND nw.user_id = $1 AND nw.book_id = $3 AND nw.device_id = old.device_id`,
		userID, oldID, newID); err != nil {
		return fmt.Errorf("чистка дублей устройств: %w", err)
	}
	if _, err := tx.Exec(ctx, `
        UPDATE wolfy.book_annotations_devices SET book_id = $3
         WHERE user_id = $1 AND book_id = $2`,
		userID, oldID, newID); err != nil {
		return fmt.Errorf("перепривязка устройств: %w", err)
	}

	// Аннотации: слить items по правилам annotations.Merge
	var oldRaw, newRaw []byte
	var oldGen, newGen int64
	var oldExists, newExists bool

	err := tx.QueryRow(ctx, `SELECT items, generation FROM wolfy.book_annotations WHERE user_id=$1 AND book_id=$2`, userID, oldID).Scan(&oldRaw, &oldGen)
	if err == nil {
		oldExists = true
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return fmt.Errorf("чтение старых аннотаций: %w", err)
	}
	err = tx.QueryRow(ctx, `SELECT items, generation FROM wolfy.book_annotations WHERE user_id=$1 AND book_id=$2`, userID, newID).Scan(&newRaw, &newGen)
	if err == nil {
		newExists = true
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return fmt.Errorf("чтение новых аннотаций: %w", err)
	}

	if oldExists {
		if newExists {
			var oldItems, newItems []annotations.Item
			if len(oldRaw) > 0 {
				if err := json.Unmarshal(oldRaw, &oldItems); err != nil {
					return fmt.Errorf("разбор старых аннотаций: %w", err)
				}
			}
			if len(newRaw) > 0 {
				if err := json.Unmarshal(newRaw, &newItems); err != nil {
					return fmt.Errorf("разбор новых аннотаций: %w", err)
				}
			}
			merged := annotations.Merge(oldItems, newItems)
			mergedGen := oldGen
			if newGen > mergedGen {
				mergedGen = newGen
			}
			// Поколение растёт только вместе с состоянием — то же правило,
			// что в обычном пути (`store/annotations.go`). Слияние, которое
			// ничего не добавило к тому, что уже лежало под новым номером,
			// не должно гонять устройства за подтверждениями впустую.
			if !reflect.DeepEqual(merged, newItems) {
				mergedGen++
				for i := range merged {
					merged[i].Generation = mergedGen
				}
			}
			payload, err := json.Marshal(merged)
			if err != nil {
				return fmt.Errorf("сериализация аннотаций: %w", err)
			}
			if _, err := tx.Exec(ctx, `UPDATE wolfy.book_annotations SET items=$3, generation=$4, updated_at=now() WHERE user_id=$1 AND book_id=$2`, userID, newID, payload, mergedGen); err != nil {
				return fmt.Errorf("обновление слитых аннотаций: %w", err)
			}
			if _, err := tx.Exec(ctx, `DELETE FROM wolfy.book_annotations WHERE user_id=$1 AND book_id=$2`, userID, oldID); err != nil {
				return fmt.Errorf("удаление старых аннотаций: %w", err)
			}
		} else {
			// Просто перепривязать старую строку к новому id
			if _, err := tx.Exec(ctx, `UPDATE wolfy.book_annotations SET book_id=$3 WHERE user_id=$1 AND book_id=$2`, userID, oldID, newID); err != nil {
				return fmt.Errorf("перепривязка аннотаций: %w", err)
			}
		}
	}
	// Старую книгу хороним, а не стираем.
	//
	// Стёртая строка не попадает в выборку изменений, и устройство, у которого
	// эта книга ещё жива, о её судьбе не узнает: оно оставит её у себя и на
	// следующей отправке заведёт заново. Tombstone же доезжает как обычное
	// удаление, и книга исчезает там, где была.
	if _, err := tx.Exec(ctx, `
        UPDATE wolfy.books
           SET deleted_at = COALESCE(deleted_at, now()), source_key = '', rev = $3, updated_at = now()
         WHERE user_id = $1 AND id = $2::uuid`,
		userID, oldID, rev); err != nil {
		return fmt.Errorf("похороны старой книги: %w", err)
	}
	return nil
}

// canonicalBookID — тот же детерминированный номер, что считает Rust
// (`core/src/library/book.rs::canonical_book_id`).
//
// Сервер обязан уметь его вычислять сам: только так он может выбрать
// победителя при конфликте `source_key` не по порядку прихода, а по существу.
// Реализация повторена, а не вынесена: тащить ядро в серверный бинарник ради
// тридцати строк дороже, чем держать общий эталон в тестах на обеих сторонах.
//
// Пустой source_key не каноникализируется: это «отпечаток снять не удалось»,
// и склеивать по нему разные книги нельзя.
func canonicalBookID(sourceKey string) string {
	if sourceKey == "" {
		return ""
	}
	const prime uint64 = 1099511628211
	const offset uint64 = 14695981039346656037
	fnv1a := func(data []byte, hash uint64) uint64 {
		for _, b := range data {
			hash ^= uint64(b)
			hash *= prime
		}
		return hash
	}
	data := []byte(sourceKey)
	h1 := fnv1a(data, offset)
	h2 := fnv1a(data, prime^offset)

	var raw [16]byte
	binary.BigEndian.PutUint64(raw[0:8], h1)
	binary.BigEndian.PutUint64(raw[8:16], h2)
	raw[6] = (raw[6] & 0x0f) | 0x50 // версия 5
	raw[8] = (raw[8] & 0x3f) | 0x80 // вариант 10xx

	hex := fmt.Sprintf("%x", raw)
	return hex[0:8] + "-" + hex[8:12] + "-" + hex[12:16] + "-" + hex[16:20] + "-" + hex[20:32]
}

// SaveCards записывает карточки. Книги обязаны быть записаны раньше: у карточки
// внешний ключ на книгу, и порядок здесь не украшение.
//
// Защита от воскрешения та же, что у книг: устаревшая живая копия не снимает
// пометку удаления, если ревизия, которую устройство видело, старше.
//
// §5: если две офлайн-книги слились к canonical, их карточки с одним lemma
// тоже схлопываются. Дубликат по (kind, lemma, book_id) не должен падать на
// unique violation: второй сохраняется как обновление первого (last writer wins),
// а его собственный номер хоронится — иначе устройство, приславшее дубликат,
// так и не узнало бы, что его карточка слилась с чужой, и держало бы у себя
// две записи на одно слово.
func (s *Store) SaveCards(ctx context.Context, tx pgx.Tx, userID string, rev int64, cards []Card) error {
	for _, card := range cards {
		// §5 dedup дубля lemma после каноникализации книги
		if card.Lemma != "" && !card.Deleted {
			var dupID string
			// book_id может быть пустым (общая колода) — сравниваем через COALESCE
			var qBookID any
			if card.BookID != "" {
				qBookID = card.BookID
			}
			errDup := tx.QueryRow(ctx, `
                SELECT id::text FROM wolfy.cards
                 WHERE user_id=$1 AND kind=$2 AND lemma=$3
                   AND COALESCE(book_id::text,'') = COALESCE($4::text,'')
                   AND deleted_at IS NULL AND id <> $5::uuid`,
				userID, kindOr(card.Kind), card.Lemma, qBookID, card.ID).Scan(&dupID)
			if errDup == nil {
				// Хороним присланный номер до того, как перепишем его на
				// существующий: клиент увидит tombstone и уберёт лишнюю
				// запись, оставив ту, что пришла в ответе. Частичный
				// unique-индекс считает только живые строки, поэтому
				// tombstone с тем же lemma в него не упирается.
				if err := buryCard(ctx, tx, userID, rev, card); err != nil {
					return err
				}
				card.ID = dupID
			} else if !errors.Is(errDup, pgx.ErrNoRows) {
				return fmt.Errorf("проверка дубля карточки %s: %w", card.ID, errDup)
			}
		}

		var deletedAt any
		if card.Deleted {
			deletedAt = time.Now()
		}
		var bookID any
		if card.BookID != "" {
			bookID = card.BookID
		}
		due := card.DueAt
		if due.IsZero() {
			due = time.Now()
		}

		_, err := tx.Exec(ctx, `
            INSERT INTO wolfy.cards (
                id, user_id, book_id, kind, surface, lemma, translation, context,
                pos, cefr, hp, streak, interval_days, due_at, reviewed_at,
                rev, deleted_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, now())
            ON CONFLICT (id) DO UPDATE SET
                book_id = excluded.book_id,
                kind = excluded.kind,
                surface = excluded.surface,
                lemma = excluded.lemma,
                translation = excluded.translation,
                context = excluded.context,
                pos = excluded.pos,
                cefr = excluded.cefr,
                hp = excluded.hp,
                streak = excluded.streak,
                interval_days = excluded.interval_days,
                due_at = excluded.due_at,
                reviewed_at = excluded.reviewed_at,
                rev = excluded.rev,
                deleted_at = excluded.deleted_at,
                updated_at = now()
            WHERE wolfy.cards.user_id = $2
              AND NOT (
                  wolfy.cards.deleted_at IS NOT NULL
                  AND excluded.deleted_at IS NULL
                  AND $18::bigint < wolfy.cards.rev)`,
			card.ID, userID, bookID, kindOr(card.Kind), card.Surface, card.Lemma,
			card.Translation, card.Context, card.Pos, card.Cefr,
			card.HP, card.Streak, card.IntervalDays, due, card.ReviewedAt,
			rev, deletedAt, card.Rev)
		if err != nil {
			return fmt.Errorf("запись карточки %s: %w", card.ID, err)
		}
	}
	return nil
}

// booksSinceTx читает книги, изменившиеся после указанной ревизии и до
// верхней границы — ревизии снимка.
//
// Удалённые приходят вместе с живыми и с пометкой: иначе удаление не доедет до
// второго устройства и книга там воскреснет.
func (s *Store) booksSinceTx(ctx context.Context, tx pgx.Tx, userID string, since, until int64) ([]Book, error) {
	rows, err := tx.Query(ctx, `
        SELECT id::text, title, author, format, source_key, chapter_count,
               last_chapter, last_offset, shelf, position, rev,
               deleted_at IS NOT NULL
        FROM wolfy.books
        WHERE user_id = $1 AND rev > $2 AND rev <= $3
        ORDER BY rev, id`, userID, since, until)
	if err != nil {
		return nil, fmt.Errorf("чтение книг: %w", err)
	}
	defer rows.Close()

	books := make([]Book, 0)
	for rows.Next() {
		var b Book
		if err := rows.Scan(&b.ID, &b.Title, &b.Author, &b.Format, &b.SourceKey,
			&b.ChapterCount, &b.LastChapter, &b.LastOffset, &b.Shelf, &b.Position,
			&b.Rev, &b.Deleted); err != nil {
			return nil, fmt.Errorf("разбор книги: %w", err)
		}
		books = append(books, b)
	}
	return books, rows.Err()
}

// cardsSinceTx читает карточки, изменившиеся в диапазоне ревизий.
func (s *Store) cardsSinceTx(ctx context.Context, tx pgx.Tx, userID string, since, until int64) ([]Card, error) {
	rows, err := tx.Query(ctx, `
        SELECT id::text, COALESCE(book_id::text, ''), kind, surface, lemma,
               translation, context, pos, cefr, hp, streak, interval_days,
               due_at, reviewed_at, rev, deleted_at IS NOT NULL
        FROM wolfy.cards
        WHERE user_id = $1 AND rev > $2 AND rev <= $3
        ORDER BY rev, id`, userID, since, until)
	if err != nil {
		return nil, fmt.Errorf("чтение карточек: %w", err)
	}
	defer rows.Close()

	cards := make([]Card, 0)
	for rows.Next() {
		var c Card
		if err := rows.Scan(&c.ID, &c.BookID, &c.Kind, &c.Surface, &c.Lemma,
			&c.Translation, &c.Context, &c.Pos, &c.Cefr, &c.HP, &c.Streak,
			&c.IntervalDays, &c.DueAt, &c.ReviewedAt, &c.Rev, &c.Deleted); err != nil {
			return nil, fmt.Errorf("разбор карточки: %w", err)
		}
		cards = append(cards, c)
	}
	return cards, rows.Err()
}

// readingTx отдаёт настройки чтения. Пустой ответ — настроек ещё нет.
func (s *Store) readingTx(ctx context.Context, tx pgx.Tx, userID string) (json.RawMessage, error) {
	var raw []byte
	err := tx.QueryRow(ctx,
		`SELECT reading FROM wolfy.user_state WHERE user_id = $1`, userID).Scan(&raw)
	switch {
	case err == pgx.ErrNoRows:
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("чтение настроек: %w", err)
	}
	return raw, nil
}

// snapshotRevTx — ревизия снимка: значение счётчика в данной транзакции.
//
// Это верхняя граница для отдаваемых изменений: всё, что записано позже,
// обязано получить ревизию больше и приехать следующим синком.
func (s *Store) snapshotRevTx(ctx context.Context, tx pgx.Tx, userID string) (int64, error) {
	var rev int64
	err := tx.QueryRow(ctx,
		`SELECT sync_rev FROM wolfy.user_state WHERE user_id = $1`, userID).Scan(&rev)
	switch {
	case err == pgx.ErrNoRows:
		return 0, nil
	case err != nil:
		return 0, fmt.Errorf("чтение ревизии: %w", err)
	}
	return rev, nil
}

// SaveReading записывает настройки чтения целиком.
//
// Целиком, а не по полю: настроек полтора десятка, они меняются вместе, и
// слияние по полям дало бы половину темы с одного устройства и половину
// с другого.
func (s *Store) SaveReading(ctx context.Context, tx pgx.Tx, userID string, reading json.RawMessage) error {
	if len(reading) == 0 {
		return nil
	}
	_, err := tx.Exec(ctx, `
        INSERT INTO wolfy.user_state (user_id, reading)
        VALUES ($1, $2)
        ON CONFLICT (user_id) DO UPDATE
            SET reading = excluded.reading, updated_at = now()`, userID, reading)
	if err != nil {
		return fmt.Errorf("запись настроек: %w", err)
	}
	return nil
}

// Sync — один согласованный обмен: принимает изменения устройства и отдаёт
// всё, что новее его курсора.
//
// Вся операция — одна транзакция с изоляцией REPEATABLE READ. Это требование,
// а не прихоть: старая композиция «записать, прочитать книги, потом узнать
// CurrentRev» теряла изменения. Пока A собрал набор книг, B записывала книгу;
// A узнавала ревизию уже поверх — и возвращала курсор, за которым изменения
// не было. Обход по rev > cursor ничего не пропустит только тогда, когда
// верхняя граница и набор данных взяты из одного снимка.
//
// Снимок фиксируется первой командой транзакции. Всё, что записано после
// снимка, получило ревизию больше: счётчик монотонный, и два изменения не
// делят номер.
func (s *Store) Sync(ctx context.Context, userID string, changes Changes) (Changes, error) {
	tx, err := s.Pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead})
	if err != nil {
		return Changes{}, fmt.Errorf("начало синхронизации: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if len(changes.Books) > 0 || len(changes.Cards) > 0 || len(changes.Reading) > 0 || changes.Companion != nil {
		// Ревизия выдаётся внутри снимка: отправка с телефона — одно событие,
		// и второе устройство не должно увидеть книги без их карточек.
		rev, err := s.NextRev(ctx, tx, userID)
		if err != nil {
			return Changes{}, err
		}
		// Книги раньше карточек: у карточки внешний ключ на книгу, и обратный
		// порядок сорвался бы на первой же новой книге со словами.
		aliases, err := s.SaveBooks(ctx, tx, userID, rev, changes.Books)
		if err != nil {
			return Changes{}, err
		}
		applyBookAliases(changes.Cards, aliases)
		if err := s.SaveCards(ctx, tx, userID, rev, changes.Cards); err != nil {
			return Changes{}, err
		}
		if err := s.SaveReading(ctx, tx, userID, changes.Reading); err != nil {
			return Changes{}, err
		}
		if err := s.SaveCompanion(ctx, tx, userID, rev, changes.Companion); err != nil {
			return Changes{}, err
		}
	}

	snapshotRev, err := s.snapshotRevTx(ctx, tx, userID)
	if err != nil {
		return Changes{}, err
	}
	books, err := s.booksSinceTx(ctx, tx, userID, changes.Cursor, snapshotRev)
	if err != nil {
		return Changes{}, err
	}
	cards, err := s.cardsSinceTx(ctx, tx, userID, changes.Cursor, snapshotRev)
	if err != nil {
		return Changes{}, err
	}
	reading, err := s.readingTx(ctx, tx, userID)
	if err != nil {
		return Changes{}, err
	}
	companion, err := s.companionSinceTx(ctx, tx, userID, changes.Cursor, snapshotRev)
	if err != nil {
		return Changes{}, err
	}

	if s.TestHookBeforeCursor != nil {
		s.TestHookBeforeCursor()
	}

	if err := tx.Commit(ctx); err != nil {
		return Changes{}, fmt.Errorf("фиксация синхронизации: %w", err)
	}
	return Changes{
		Cursor:    snapshotRev,
		Books:     books,
		Cards:     cards,
		Reading:   reading,
		Companion: companion,
	}, nil
}

// kindOr защищает от пустого вида карточки: колонка не допускает пустоты, а
// клиент прошлой версии о поле мог не знать.
// buryCard помечает номер карточки удалённым, не трогая её содержимое.
//
// Нужно ровно одному месту — схлопыванию дубликатов по lemma: карточка
// продолжает жить под другим номером, а этот обязан доехать до приславшего
// устройства как удаление, иначе слово останется в колоде дважды.
func buryCard(ctx context.Context, tx pgx.Tx, userID string, rev int64, card Card) error {
	var bookID any
	if card.BookID != "" {
		bookID = card.BookID
	}
	due := card.DueAt
	if due.IsZero() {
		due = time.Now()
	}
	if _, err := tx.Exec(ctx, `
        INSERT INTO wolfy.cards (
            id, user_id, book_id, kind, surface, lemma, due_at, rev, deleted_at, updated_at)
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now(), now())
        ON CONFLICT (id) DO UPDATE SET
            deleted_at = COALESCE(wolfy.cards.deleted_at, now()),
            rev = excluded.rev,
            updated_at = now()
          WHERE wolfy.cards.user_id = $2`,
		card.ID, userID, bookID, kindOr(card.Kind), card.Surface, card.Lemma, due, rev); err != nil {
		return fmt.Errorf("похороны дубля карточки %s: %w", card.ID, err)
	}
	return nil
}

func kindOr(kind string) string {
	if kind == "" {
		return "word"
	}
	return kind
}
