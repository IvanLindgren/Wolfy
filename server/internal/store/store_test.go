package store_test

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/wolfy/server/internal/store"
)

// databaseURL — адрес базы для тестов.
//
// Тесты работают на настоящем Postgres, а не на заглушке, и это осознанно:
// проверять надо схему и запросы, а мок повторил бы только то, что мы и так
// написали. Без базы тесты не падают, а пропускаются — чтобы `go test ./...`
// на чужой машине не требовал докера.
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

func open(t *testing.T) *store.Store {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	s, err := store.Open(ctx, databaseURL(t))
	if err != nil {
		t.Fatalf("база не открылась: %v", err)
	}
	t.Cleanup(s.Close)
	return s
}

func TestМиграцииПрименяютсяИПовторноНеЛомаются(t *testing.T) {
	s := open(t)
	ctx := context.Background()

	// Повторное открытие обязано быть безопасным: сервис перезапускается
	// на каждом деплое, и миграции проходят снова.
	second, err := store.Open(ctx, databaseURL(t))
	if err != nil {
		t.Fatalf("повторное применение миграций сломалось: %v", err)
	}
	defer second.Close()

	var count int
	err = s.Pool.QueryRow(ctx,
		`SELECT count(*) FROM wolfy.schema_migrations`).Scan(&count)
	if err != nil {
		t.Fatalf("журнал миграций не читается: %v", err)
	}
	if count == 0 {
		t.Fatal("миграции не отметились в журнале")
	}
}

func TestСхемаWolfyОтделенаОтЧитавука(t *testing.T) {
	s := open(t)
	ctx := context.Background()

	// Свои таблицы лежат в схеме wolfy и нигде больше: библиотека английских
	// книг не должна путаться с данными Читавука.
	for _, table := range []string{"user_state", "books", "cards", "translations"} {
		var exists bool
		err := s.Pool.QueryRow(ctx, `
            SELECT EXISTS (
                SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'wolfy' AND table_name = $1)`,
			table).Scan(&exists)
		if err != nil {
			t.Fatalf("проверка таблицы %s: %v", table, err)
		}
		if !exists {
			t.Errorf("таблицы wolfy.%s нет", table)
		}
	}
}

func TestОдноСловоИзКнигиСохраняетсяОдинРаз(t *testing.T) {
	s := open(t)
	ctx := context.Background()

	user := createUser(t, s)

	insert := `
        INSERT INTO wolfy.cards (id, user_id, kind, surface, lemma)
        VALUES (gen_random_uuid(), $1, 'word', $2, $3)`

	if _, err := s.Pool.Exec(ctx, insert, user, "reading", "read"); err != nil {
		t.Fatalf("первое сохранение слова: %v", err)
	}
	// Повторное нажатие по тому же слову не должно плодить дубликаты —
	// иначе колода забьётся одним словом в разных формах.
	_, err := s.Pool.Exec(ctx, insert, user, "reads", "read")
	if err == nil {
		t.Fatal("дубликат слова прошёл — уникальный индекс не работает")
	}
}

func TestУдалениеКарточкиПомечаетсяАНеСтирается(t *testing.T) {
	s := open(t)
	ctx := context.Background()

	user := createUser(t, s)

	var id string
	err := s.Pool.QueryRow(ctx, `
        INSERT INTO wolfy.cards (id, user_id, kind, surface, lemma)
        VALUES (gen_random_uuid(), $1, 'word', 'library', 'library')
        RETURNING id::text`, user).Scan(&id)
	if err != nil {
		t.Fatalf("сохранение слова: %v", err)
	}

	if _, err := s.Pool.Exec(ctx,
		`UPDATE wolfy.cards SET deleted_at = now() WHERE id = $1`, id); err != nil {
		t.Fatalf("пометка удаления: %v", err)
	}

	// Запись осталась: без неё удаление не доехало бы до второго устройства.
	var deleted bool
	err = s.Pool.QueryRow(ctx,
		`SELECT deleted_at IS NOT NULL FROM wolfy.cards WHERE id = $1`, id).Scan(&deleted)
	if err != nil {
		t.Fatalf("удалённая карточка исчезла из базы: %v", err)
	}
	if !deleted {
		t.Fatal("карточка не помечена удалённой")
	}

	// А то же слово теперь можно сохранить заново: уникальный индекс
	// учитывает только живые карточки.
	_, err = s.Pool.Exec(ctx, `
        INSERT INTO wolfy.cards (id, user_id, kind, surface, lemma)
        VALUES (gen_random_uuid(), $1, 'word', 'library', 'library')`, user)
	if err != nil {
		t.Fatalf("слово не сохраняется после удаления: %v", err)
	}
}

// createUser заводит пользователя в таблице Читавука и возвращает его id.
func createUser(t *testing.T, s *store.Store) string {
	t.Helper()
	ctx := context.Background()

	var id string
	err := s.Pool.QueryRow(ctx, `
        INSERT INTO users (id, email, display_name)
        VALUES (gen_random_uuid(), $1, 'Тестовый читатель')
        RETURNING id::text`,
		"test-"+time.Now().Format("150405.000000")+"@example.com").Scan(&id)
	if err != nil {
		t.Fatalf("пользователь не создан: %v", err)
	}
	t.Cleanup(func() {
		ctx := context.Background()
		// Карточки чистим явно: у wolfy.cards нет внешнего ключа на users,
		// поэтому каскад их не заберёт. Ключа нет намеренно — жёсткая связь со
		// схемой Читавука означала бы, что его миграция способна уронить
		// таблицы Wolfy.
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.cards WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM wolfy.books WHERE user_id = $1`, id)
		_, _ = s.Pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, id)
	})
	return id
}
