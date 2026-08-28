package library_test

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/wolfy/server/internal/library"
	"github.com/wolfy/server/internal/store"
)

// Тесты идут на настоящем Postgres: проверяется схема, транзакция и порядок
// записи, а заглушка повторила бы только то, что мы и так написали. Без базы
// тесты пропускаются, чтобы `go test ./...` на чужой машине не требовал докера.
func open(t *testing.T) *store.Store {
	t.Helper()
	url := os.Getenv("WOLFY_TEST_DB_URL")
	if url == "" {
		url = os.Getenv("WOLFY_DB_URL")
	}
	if url == "" {
		t.Skip("нет WOLFY_TEST_DB_URL — пропускаем тесты с базой (docker compose up -d)")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	s, err := store.Open(ctx, url)
	if err != nil {
		t.Fatalf("база не открылась: %v", err)
	}
	t.Cleanup(s.Close)
	return s
}

func createUser(t *testing.T, s *store.Store) string {
	t.Helper()
	ctx := context.Background()

	var id string
	err := s.Pool.QueryRow(ctx, `
        INSERT INTO users (id, email, display_name)
        VALUES (gen_random_uuid(), $1, 'Тестовый читатель')
        RETURNING id::text`,
		"sync-"+time.Now().Format("150405.000000000")+"@example.com").Scan(&id)
	if err != nil {
		t.Fatalf("пользователь не создан: %v", err)
	}
	t.Cleanup(func() {
		ctx := context.Background()
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.cards WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.companions WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.books WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.practice_components WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.user_state WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, id)
	})
	return id
}

func book(title string) store.Book {
	return store.Book{
		ID:           uuid.NewString(),
		Title:        title,
		Format:       "epub",
		ChapterCount: 12,
	}
}

func TestКнигаСОдногоУстройстваПриходитНаДругое(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	// Телефон отправляет книгу.
	phone := book("The Great Gatsby")
	phone.LastChapter = 3
	first, err := svc.Sync(ctx, user, store.Changes{Books: []store.Book{phone}})
	if err != nil {
		t.Fatalf("отправка с телефона: %v", err)
	}
	if first.Cursor == 0 {
		t.Fatal("курсор не сдвинулся — ревизия не выдана")
	}

	// Компьютер приходит с нуля и получает её целиком.
	second, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("получение на компьютере: %v", err)
	}
	if len(second.Books) != 1 {
		t.Fatalf("книг пришло %d, ожидалась одна", len(second.Books))
	}
	if second.Books[0].Title != "The Great Gatsby" || second.Books[0].LastChapter != 3 {
		t.Fatalf("книга приехала другой: %+v", second.Books[0])
	}
}

func TestУжеИзвестноеПовторноНеПриходит(t *testing.T) {
	// Ради этого курсор и нужен: библиотека на тысячу слов не должна ездить
	// по сети при каждом открытии приложения.
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	pushed, err := svc.Sync(ctx, user, store.Changes{Books: []store.Book{book("Dune")}})
	if err != nil {
		t.Fatalf("отправка: %v", err)
	}

	again, err := svc.Sync(ctx, user, store.Changes{Cursor: pushed.Cursor})
	if err != nil {
		t.Fatalf("повторный запрос: %v", err)
	}
	if len(again.Books) != 0 {
		t.Fatalf("пришло %d книг, а нового не было", len(again.Books))
	}
	if again.Cursor != pushed.Cursor {
		t.Fatalf("курсор сдвинулся без изменений: было %d, стало %d", pushed.Cursor, again.Cursor)
	}
}

func TestПолкаДоезжаетНаДругоеУстройствоСразуПослеСинхронизации(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	phone := book("The Left Hand of Darkness")
	phone.Shelf = "Фантастика"
	phone.Position = 2
	if _, err := svc.Sync(ctx, user, store.Changes{Books: []store.Book{phone}}); err != nil {
		t.Fatalf("отправка книги на полку: %v", err)
	}

	computer, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("получение на другом устройстве: %v", err)
	}
	if len(computer.Books) != 1 {
		t.Fatalf("книг пришло %d, ожидалась одна", len(computer.Books))
	}
	if computer.Books[0].Shelf != "Фантастика" || computer.Books[0].Position != 2 {
		t.Fatalf("полка не доехала: %+v", computer.Books[0])
	}
}

func TestУдалениеДоезжаетПометкой(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	gone := book("Забытая")
	if _, err := svc.Sync(ctx, user, store.Changes{Books: []store.Book{gone}}); err != nil {
		t.Fatalf("добавление: %v", err)
	}

	gone.Deleted = true
	after, err := svc.Sync(ctx, user, store.Changes{Books: []store.Book{gone}})
	if err != nil {
		t.Fatalf("удаление: %v", err)
	}

	// Запись обязана приехать, а не исчезнуть: исчезнувшую второе устройство
	// не заметит, и книга там воскреснет.
	fresh, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("получение: %v", err)
	}
	if len(fresh.Books) != 1 || !fresh.Books[0].Deleted {
		t.Fatalf("удаление не доехало: %+v (курсор %d)", fresh.Books, after.Cursor)
	}
}

func TestКарточкиЕдутВместеСоСвоейКнигой(t *testing.T) {
	// Книга и её карточки уходят одной отправкой, и записаться обязаны в одной
	// транзакции: у карточки внешний ключ на книгу.
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	novel := book("Jane Eyre")
	card := store.Card{
		ID:      uuid.NewString(),
		BookID:  novel.ID,
		Kind:    "word",
		Surface: "serendipity",
		Lemma:   "serendipity",
		Context: "a serendipity of bookmarks",
		Cefr:    "C1",
		HP:      100,
	}

	if _, err := svc.Sync(ctx, user, store.Changes{
		Books: []store.Book{novel},
		Cards: []store.Card{card},
	}); err != nil {
		t.Fatalf("отправка книги со словом: %v", err)
	}

	got, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("получение: %v", err)
	}
	if len(got.Cards) != 1 || got.Cards[0].Lemma != "serendipity" {
		t.Fatalf("карточка не приехала: %+v", got.Cards)
	}
	if got.Cards[0].BookID != novel.ID {
		t.Fatalf("карточка потеряла книгу: %+v", got.Cards[0])
	}
}

func TestЧужаяБиблиотекаНеПриходит(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	ctx := context.Background()

	mine := createUser(t, s)
	stranger := createUser(t, s)

	if _, err := svc.Sync(ctx, stranger, store.Changes{Books: []store.Book{book("Чужая")}}); err != nil {
		t.Fatalf("отправка чужой книги: %v", err)
	}

	got, err := svc.Sync(ctx, mine, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("получение: %v", err)
	}
	if len(got.Books) != 0 {
		t.Fatalf("пришли чужие книги: %+v", got.Books)
	}
}

func TestНастройкиЧтенияЕздятЦеликом(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	reading := json.RawMessage(`{"theme":"Sepia","fontScale":1.2}`)
	if _, err := svc.Sync(ctx, user, store.Changes{Reading: reading}); err != nil {
		t.Fatalf("отправка настроек: %v", err)
	}

	got, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("получение: %v", err)
	}

	var parsed map[string]any
	if err := json.Unmarshal(got.Reading, &parsed); err != nil {
		t.Fatalf("настройки не разобрались: %v (%s)", err, got.Reading)
	}
	if parsed["theme"] != "Sepia" {
		t.Fatalf("тема не доехала: %v", parsed)
	}
}

func TestНепонятныйНомерОтвергается(t *testing.T) {
	// Номер придумывает устройство, и потому его форма проверяется: в базе
	// колонка uuid, и «b1a02a07-0» туда не ляжет.
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)

	_, err := svc.Sync(context.Background(), user, store.Changes{
		Books: []store.Book{{ID: "b1a02a07-0", Title: "Кривая"}},
	})
	if err == nil {
		t.Fatal("книга с непонятным номером прошла")
	}
}

func TestСлишкомБольшаяОтправкаОтклоняется(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)

	books := make([]store.Book, library.MaxBooks+1)
	for i := range books {
		books[i] = book("Книга")
	}

	_, err := svc.Sync(context.Background(), user, store.Changes{Books: books})
	if err == nil {
		t.Fatal("отправка сверх предела прошла")
	}
}

func companionProfile(id string) json.RawMessage {
	return json.RawMessage(`{"id":"` + id + `","name":"Лис","locale":"ru","personality":{"warmth":72},"rev":0,"deleted":false}`)
}

func TestКомпаньонЕдетМеждуУстройствами(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	profile := companionProfile(uuid.NewString())
	if _, err := svc.Sync(ctx, user, store.Changes{
		Companion: &store.Companion{Profile: profile, ProfileHash: "abc123"},
	}); err != nil {
		t.Fatalf("отправка компаньона: %v", err)
	}

	got, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("получение: %v", err)
	}
	if got.Companion == nil || got.Companion.Deleted {
		t.Fatalf("компаньон не доехал: %+v", got.Companion)
	}
	var parsed map[string]any
	if err := json.Unmarshal(got.Companion.Profile, &parsed); err != nil {
		t.Fatalf("профиль не разобрался: %v", err)
	}
	if parsed["name"] != "Лис" {
		t.Fatalf("имя потерялось: %v", parsed)
	}
}

func TestTombstoneКомпаньонаНеОживает(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)
	ctx := context.Background()

	id := uuid.NewString()
	live := store.Companion{Profile: companionProfile(id), ProfileHash: "h1"}
	if _, err := svc.Sync(ctx, user, store.Changes{Companion: &live}); err != nil {
		t.Fatalf("создание: %v", err)
	}
	first, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("чтение: %v", err)
	}
	seenRev := first.Companion.Rev

	// Удаление со второго устройства.
	tombstone := store.Companion{Profile: companionProfile(id), ProfileHash: "h1", Rev: seenRev, Deleted: true}
	if _, err := svc.Sync(ctx, user, store.Changes{Companion: &tombstone}); err != nil {
		t.Fatalf("удаление: %v", err)
	}

	// Устаревшая живая копия со старой ревизией не должна воскресить профиль.
	stale := store.Companion{Profile: companionProfile(id), ProfileHash: "h1", Rev: 1}
	if _, err := svc.Sync(ctx, user, store.Changes{Companion: &stale}); err != nil {
		t.Fatalf("устаревшая отправка: %v", err)
	}

	got, err := svc.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("финальное чтение: %v", err)
	}
	if got.Companion == nil || !got.Companion.Deleted {
		t.Fatalf("tombstone потерялся: %+v", got.Companion)
	}
}

func TestСтарыйКлиентБезКомпаньонаСинхронизируется(t *testing.T) {
	// Payload без поля companion обязан пройти: поле новое, а читатель со
	// старой версией не должен лишиться синхронизации.
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)

	got, err := svc.Sync(context.Background(), user, store.Changes{Reading: json.RawMessage(`{"theme":"Paper"}`)})
	if err != nil {
		t.Fatalf("обмен без компаньона: %v", err)
	}
	if got.Companion != nil {
		t.Fatalf("компаньон появился из ниоткуда: %+v", got.Companion)
	}
}

func TestСлишкомБольшойПрофильОтклоняется(t *testing.T) {
	s := open(t)
	svc := library.New(s)
	user := createUser(t, s)

	huge := make([]byte, library.MaxCompanionProfile+1)
	for i := range huge {
		huge[i] = 'a'
	}
	_, err := svc.Sync(context.Background(), user, store.Changes{
		Companion: &store.Companion{Profile: append([]byte(`{"id":"`+uuid.NewString()+`","pad":"`), append(huge, '"')...), ProfileHash: "h"},
	})
	if err == nil {
		t.Fatal("гигантский профиль прошёл")
	}
}
