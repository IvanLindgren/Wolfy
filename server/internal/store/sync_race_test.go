package store

import (
	"context"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Тест живёт в самом пакете store, чтобы расставить паузу между чтением
// изменений и фиксацией курсора: именно там теряла изменения старая
// композиция «прочитать книги — потом узнать CurrentRev».

func databaseURL(t *testing.T) string {
	t.Helper()
	url := os.Getenv("WOLFY_TEST_DB_URL")
	if url == "" {
		url = os.Getenv("WOLFY_DB_URL")
	}
	if url == "" {
		t.Skip("нет WOLFY_TEST_DB_URL — пропускаем тесты с базой (docker compose up -d)")
	}
	return url
}

func openStore(t *testing.T) *Store {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	s, err := Open(ctx, databaseURL(t))
	if err != nil {
		t.Fatalf("база не открылась: %v", err)
	}
	t.Cleanup(s.Close)
	return s
}

func createUser(t *testing.T, s *Store) string {
	t.Helper()
	ctx := context.Background()
	var id string
	err := s.Pool.QueryRow(ctx, `
        INSERT INTO users (id, email, display_name)
        VALUES (gen_random_uuid(), $1, 'Тестовый читатель')
        RETURNING id::text`,
		"race-"+time.Now().Format("150405.000000")+"@example.com").Scan(&id)
	if err != nil {
		t.Fatalf("пользователь не создан: %v", err)
	}
	t.Cleanup(func() {
		ctx := context.Background()
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.cards WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.books WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, id)
	})
	return id
}

func newTestBook() Book {
	return Book{
		ID: "11111111-1111-1111-1111-111111111111", Title: "Новая книга",
		SourceKey: "hash-new", Format: "epub", ChapterCount: 1, Rev: 5,
	}
}

// Синхронизация не должна перепрыгнуть невидимое изменение: если ответ
// выдал cursor = N, в нём обязаны быть все изменения rev <= N. Проверка
// детерминированная: A останавливается после формирования набора, B
// записывает книгу, A продолжает — курсор A не имеет права догнать ревизию B.
func TestСинхронизацияНеПерепрыгиваетНевидимыеИзменения(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	entered := make(chan struct{})
	release := make(chan struct{})
	var called atomic.Bool
	s.TestHookBeforeCursor = func() {
		if !called.CompareAndSwap(false, true) {
			return
		}
		close(entered)
		<-release
	}
	defer func() { s.TestHookBeforeCursor = nil }()

	var respA Changes
	var errA error
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		respA, errA = s.Sync(ctx, user, Changes{Cursor: 0})
	}()

	<-entered
	// B пишет книгу, пока A висит на паузе.
	if _, err := s.Sync(ctx, user, Changes{Books: []Book{newTestBook()}}); err != nil {
		t.Fatalf("запись B: %v", err)
	}
	close(release)
	wg.Wait()

	if errA != nil {
		t.Fatalf("синхронизация A: %v", errA)
	}
	if !called.Load() {
		t.Fatal("пауза не сработала — тест ничего не проверяет")
	}

	// Наблюдатель: узнаём ревизию книги B.
	obs, err := s.Sync(ctx, user, Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("наблюдатель: %v", err)
	}
	revB := int64(0)
	for _, b := range obs.Books {
		if b.ID == newTestBook().ID {
			revB = b.Rev
		}
	}
	if revB == 0 {
		t.Fatal("книга B не доехала до наблюдателя")
	}

	if respA.Cursor >= revB {
		seen := false
		for _, b := range respA.Books {
			if b.ID == newTestBook().ID {
				seen = true
			}
		}
		if !seen {
			t.Fatalf("курсор %d догнал ревизию %d, а книги B в ответе нет",
				respA.Cursor, revB)
		}
	}
}

// Повторная синхронизация с тем же курсором не должна таскать изменения,
// которые уже были выданы: верхняя граница снимка — часть ответа, и курсор
// соответствует ровно тому, что в ней прочитано.
func TestСнимокДержитсяСвоейВерхнейГраницы(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	first, err := s.Sync(ctx, user, Changes{Books: []Book{newTestBook()}})
	if err != nil {
		t.Fatalf("первая синхронизация: %v", err)
	}
	for _, b := range first.Books {
		if b.ID != newTestBook().ID {
			t.Fatalf("первый снимок принёс лишнее: %+v", b)
		}
		if b.Rev > first.Cursor {
			t.Fatalf("ревизия %d за верхней границей %d", b.Rev, first.Cursor)
		}
	}

	second, err := s.Sync(ctx, user, Changes{Cursor: first.Cursor})
	if err != nil {
		t.Fatalf("вторая синхронизация: %v", err)
	}
	if second.Cursor != first.Cursor {
		t.Fatalf("курсор без новых записей сдвинулся: %d -> %d", first.Cursor, second.Cursor)
	}
	if len(second.Books) != 0 {
		t.Fatalf("уже выданная книга уехала снова: %+v", second.Books)
	}
}
