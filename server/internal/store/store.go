// Package store — единственное место, где сервис говорит с базой.
//
// Правило слоя простое: здесь нет ни бизнес-логики, ни HTTP. Функция этого
// пакета либо читает, либо пишет, а решение о том, что именно делать, приходит
// сверху.
package store

import (
	"context"
	"embed"
	"fmt"
	"sort"

	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Store — пул соединений с базой.
type Store struct {
	Pool *pgxpool.Pool

	// TestHookBeforeCursor — зацепка для тестов: пауза после того, как
	// изменения сформированы, но перед фиксацией. В продакшене всегда nil.
	TestHookBeforeCursor func()
}

// Open подключается к базе и применяет миграции.
func Open(ctx context.Context, databaseURL string) (*Store, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("подключение к базе: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("база не отвечает: %w", err)
	}

	s := &Store{Pool: pool}
	if err := s.migrate(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return s, nil
}

// Close отпускает соединения.
func (s *Store) Close() {
	if s.Pool != nil {
		s.Pool.Close()
	}
}

// Healthy — отвечает ли база. Используется в /healthz.
func (s *Store) Healthy(ctx context.Context) error {
	return s.Pool.Ping(ctx)
}

// migrate применяет неприменённые миграции по порядку имён.
//
// Своя таблица версий, а не общая с Читавуком: два сервиса ходят в одну базу,
// и общий журнал миграций означал бы, что деплой одного ломает другой.
func (s *Store) migrate(ctx context.Context) error {
	_, err := s.Pool.Exec(ctx, `
        CREATE SCHEMA IF NOT EXISTS wolfy;
        CREATE TABLE IF NOT EXISTS wolfy.schema_migrations (
            name       text PRIMARY KEY,
            applied_at timestamptz NOT NULL DEFAULT now()
        )`)
	if err != nil {
		return fmt.Errorf("журнал миграций: %w", err)
	}

	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		return fmt.Errorf("чтение миграций: %w", err)
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() {
			names = append(names, entry.Name())
		}
	}
	// Порядок задаётся номером в имени файла, поэтому сортировка обязана быть
	// явной: порядок выдачи файловой системы не гарантирован.
	sort.Strings(names)

	for _, name := range names {
		applied, err := s.applied(ctx, name)
		if err != nil {
			return err
		}
		if applied {
			continue
		}
		if err := s.apply(ctx, name); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) applied(ctx context.Context, name string) (bool, error) {
	var exists bool
	err := s.Pool.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM wolfy.schema_migrations WHERE name = $1)`,
		name).Scan(&exists)
	if err != nil {
		return false, fmt.Errorf("проверка миграции %s: %w", name, err)
	}
	return exists, nil
}

// apply выполняет миграцию целиком в одной транзакции.
//
// Транзакция важна: миграция, применившаяся наполовину, оставила бы базу в
// состоянии, из которого нет пути ни вперёд, ни назад.
func (s *Store) apply(ctx context.Context, name string) error {
	sql, err := migrations.ReadFile("migrations/" + name)
	if err != nil {
		return fmt.Errorf("чтение миграции %s: %w", name, err)
	}

	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("начало миграции %s: %w", name, err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, string(sql)); err != nil {
		return fmt.Errorf("миграция %s: %w", name, err)
	}
	_, err = tx.Exec(ctx,
		`INSERT INTO wolfy.schema_migrations (name) VALUES ($1)`, name)
	if err != nil {
		return fmt.Errorf("отметка миграции %s: %w", name, err)
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("фиксация миграции %s: %w", name, err)
	}
	return nil
}
