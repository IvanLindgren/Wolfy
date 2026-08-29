package store_test

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"path"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/wolfy/server/internal/store"
)

// Проверка, что 0012 чинит базу, отставшую от правки 0003 и 0004.
//
// Журнал миграций помнит имя файла: миграция, чьё имя уже записано, второй раз
// не выполняется. Когда 0003 и 0004 однажды поправили на месте, production
// остался со схемой прежней редакции - и «column "generation" does not exist»
// вылезло много позже, при перепривязке книги.
//
// Свежая база этого не воспроизводит: она берёт нынешнюю редакцию файлов и
// выглядит правильной, поэтому обычные тесты проходили всё это время. Здесь
// прежнее состояние собирается руками, в отдельной базе, и уже на нём
// запускается настоящий `store.Open`.
func TestМиграцияЧинитБазуСоСтаройСхемойЗаметок(t *testing.T) {
	admin := databaseURL(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	name := fmt.Sprintf("wolfy_drift_%d", time.Now().UnixNano())
	driftURL := withDatabase(t, admin, name)

	adminPool, err := pgxpool.New(ctx, admin)
	if err != nil {
		t.Fatalf("админское подключение: %v", err)
	}
	defer adminPool.Close()
	if _, err := adminPool.Exec(ctx, `CREATE DATABASE `+quoteIdent(name)); err != nil {
		t.Fatalf("создание базы для проверки: %v", err)
	}
	t.Cleanup(func() {
		clean, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		_, _ = adminPool.Exec(clean, `DROP DATABASE IF EXISTS `+quoteIdent(name)+` WITH (FORCE)`)
	})

	// Схема ровно та, что осталась в production: у заметок нет generation,
	// а у устройств колонка называется seen_rev.
	seed(ctx, t, driftURL, `
        CREATE SCHEMA wolfy;
        CREATE TABLE wolfy.book_annotations (
            user_id    uuid        NOT NULL,
            book_id    text        NOT NULL,
            items      jsonb       NOT NULL DEFAULT '[]'::jsonb,
            updated_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (user_id, book_id)
        );
        CREATE TABLE wolfy.book_annotations_devices (
            user_id   uuid   NOT NULL,
            book_id   text   NOT NULL,
            device_id text   NOT NULL,
            seen_rev  bigint NOT NULL DEFAULT 0,
            PRIMARY KEY (user_id, book_id, device_id)
        );
        INSERT INTO wolfy.book_annotations (user_id, book_id)
        VALUES ('11111111-1111-1111-1111-111111111111', 'book');
        INSERT INTO wolfy.book_annotations_devices (user_id, book_id, device_id, seen_rev)
        VALUES ('11111111-1111-1111-1111-111111111111', 'book', 'phone', 42);
    `)

	// Журнал помнит все миграции, кроме той, что чинит. Именно так выглядит
	// production: имена записаны, содержимое файлов с тех пор изменилось.
	markApplied(ctx, t, driftURL, "0012_annotation_generations.sql")

	opened, err := store.Open(ctx, driftURL)
	if err != nil {
		t.Fatalf("миграции не прошли на старой схеме: %v", err)
	}
	defer opened.Close()

	if got := columnType(ctx, t, opened, "book_annotations", "generation"); got != "bigint" {
		t.Errorf("колонка generation не появилась: %q", got)
	}
	if got := columnType(ctx, t, opened, "book_annotations_devices", "seen_generation"); got != "bigint" {
		t.Errorf("колонка seen_generation не появилась: %q", got)
	}
	if got := columnType(ctx, t, opened, "book_annotations_devices", "seen_rev"); got != "" {
		t.Errorf("старая колонка seen_rev осталась: %q", got)
	}

	// Строка устройства обязана уцелеть: она и есть то, что удерживает
	// сборщик пометок от преждевременной уборки.
	var devices int
	var seen int64
	if err := opened.Pool.QueryRow(ctx,
		`SELECT count(*), COALESCE(max(seen_generation), -1) FROM wolfy.book_annotations_devices`,
	).Scan(&devices, &seen); err != nil {
		t.Fatalf("чтение устройств: %v", err)
	}
	if devices != 1 {
		t.Errorf("устройство потерялось при переименовании: строк %d", devices)
	}
	// Ноль здесь обязателен. seen_rev хранил номер правки писателя, а
	// поколение выдаёт сервер и начинает с нуля: оставь 42 под новым именем -
	// и MIN(seen_generation) сразу окажется больше поколения любой новой
	// пометки удаления, так что первое же слияние стёрло бы её, не дождавшись
	// ни одного устройства.
	if seen != 0 {
		t.Errorf("подтверждение устройства не сброшено: %d (старый seen_rev пролез под новым именем)", seen)
	}

	// Второй запуск — обычный перезапуск сервиса на деплое.
	again, err := store.Open(ctx, driftURL)
	if err != nil {
		t.Fatalf("повторный запуск после починки сломался: %v", err)
	}
	again.Close()
}

// markApplied записывает в журнал все миграции, кроме перечисленных.
func markApplied(ctx context.Context, t *testing.T, dsn string, except ...string) {
	t.Helper()
	skip := make(map[string]bool, len(except))
	for _, name := range except {
		skip[name] = true
	}
	entries, err := os.ReadDir("migrations")
	if err != nil {
		t.Fatalf("чтение каталога миграций: %v", err)
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && !skip[entry.Name()] {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	if len(names) == 0 {
		t.Fatal("миграции не найдены: тест запущен не из каталога пакета")
	}
	statements := []string{`CREATE TABLE wolfy.schema_migrations (
        name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`}
	for _, name := range names {
		statements = append(statements,
			fmt.Sprintf(`INSERT INTO wolfy.schema_migrations (name) VALUES ('%s')`, name))
	}
	seed(ctx, t, dsn, strings.Join(statements, ";\n")+";")
}

func seed(ctx context.Context, t *testing.T, dsn, sql string) {
	t.Helper()
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatalf("подключение к базе проверки: %v", err)
	}
	defer pool.Close()
	if _, err := pool.Exec(ctx, sql); err != nil {
		t.Fatalf("подготовка старой схемы: %v", err)
	}
}

// columnType — тип колонки или пустая строка, если её нет.
func columnType(ctx context.Context, t *testing.T, s *store.Store, table, column string) string {
	t.Helper()
	var kind string
	err := s.Pool.QueryRow(ctx, `
        SELECT data_type FROM information_schema.columns
         WHERE table_schema = 'wolfy' AND table_name = $1 AND column_name = $2`,
		table, column).Scan(&kind)
	if err != nil {
		return ""
	}
	return kind
}

func withDatabase(t *testing.T, dsn, name string) string {
	t.Helper()
	parsed, err := url.Parse(dsn)
	if err != nil {
		t.Fatalf("адрес базы не разобран: %v", err)
	}
	parsed.Path = "/" + path.Base(name)
	return parsed.String()
}

func quoteIdent(name string) string {
	return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
}
