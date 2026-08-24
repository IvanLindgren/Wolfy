package store

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// Book — книга библиотеки так, как её хранит сервер.
//
// Файла книги здесь нет и не будет: книга пользователя это его файл, и держать
// чужие epub у себя значит отвечать за них. Сервер хранит только то, что нужно
// второму устройству, чтобы узнать книгу и продолжить с того же места.
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

// Card — слово или фраза в колоде вместе с состоянием повторений.
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
	Reading json.RawMessage `json:"reading,omitempty"`
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
func (s *Store) SaveBooks(ctx context.Context, tx pgx.Tx, userID string, rev int64, books []Book) error {
	for _, book := range books {
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
			return fmt.Errorf("запись книги %s: %w", book.ID, err)
		}
	}
	return nil
}

// SaveCards записывает карточки. Книги обязаны быть записаны раньше: у карточки
// внешний ключ на книгу, и порядок здесь не украшение.
//
// Защита от воскрешения та же, что у книг: устаревшая живая копия не снимает
// пометку удаления, если ревизия, которую устройство видело, старше.
func (s *Store) SaveCards(ctx context.Context, tx pgx.Tx, userID string, rev int64, cards []Card) error {
	for _, card := range cards {
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

	if len(changes.Books) > 0 || len(changes.Cards) > 0 || len(changes.Reading) > 0 {
		// Ревизия выдаётся внутри снимка: отправка с телефона — одно событие,
		// и второе устройство не должно увидеть книги без их карточек.
		rev, err := s.NextRev(ctx, tx, userID)
		if err != nil {
			return Changes{}, err
		}
		// Книги раньше карточек: у карточки внешний ключ на книгу, и обратный
		// порядок сорвался бы на первой же новой книге со словами.
		if err := s.SaveBooks(ctx, tx, userID, rev, changes.Books); err != nil {
			return Changes{}, err
		}
		if err := s.SaveCards(ctx, tx, userID, rev, changes.Cards); err != nil {
			return Changes{}, err
		}
		if err := s.SaveReading(ctx, tx, userID, changes.Reading); err != nil {
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

	if s.TestHookBeforeCursor != nil {
		s.TestHookBeforeCursor()
	}

	if err := tx.Commit(ctx); err != nil {
		return Changes{}, fmt.Errorf("фиксация синхронизации: %w", err)
	}
	return Changes{
		Cursor:  snapshotRev,
		Books:   books,
		Cards:   cards,
		Reading: reading,
	}, nil
}

// kindOr защищает от пустого вида карточки: колонка не допускает пустоты, а
// клиент прошлой версии о поле мог не знать.
func kindOr(kind string) string {
	if kind == "" {
		return "word"
	}
	return kind
}
