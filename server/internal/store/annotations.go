package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"

	"github.com/wolfy/server/internal/annotations"
)

// BookAnnotations возвращает сохранённый список отметок книги.
//
// Отсутствующая строка — книга ещё ни разу не синхронизировалась, а не
// ошибка: у каждого второго читателя её нет.
func (s *Store) BookAnnotations(ctx context.Context, userID, bookID string) ([]annotations.Item, error) {
	var raw []byte
	err := s.Pool.QueryRow(ctx, `
        SELECT items FROM wolfy.book_annotations
         WHERE user_id = $1 AND book_id = $2`, userID, bookID).Scan(&raw)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return []annotations.Item{}, nil
		}
		return nil, fmt.Errorf("чтение заметок книги: %w", err)
	}

	var items []annotations.Item
	if err := json.Unmarshal(raw, &items); err != nil {
		return nil, fmt.Errorf("разбор заметок книги: %w", err)
	}
	return items, nil
}

// SaveBookAnnotations сливает присланный список с хранимым, подтверждает
// прочтение устройством и возвращает результат.
//
// Всё внутри одной транзакции и под блокировкой строки: два устройства,
// отправившие списки одновременно, обязаны увидеть друг друга, а не
// перезаписать. Порядок шагов важен:
//
//  1. подтверждение seen — до сборки мусора, иначе это же устройство
//     заморозило бы стирание собственных пометок;
//  2. слияние по (Rev, Writer);
//  3. сборка мусора — только по минимуму подтверждений всех устройств.
func (s *Store) SaveBookAnnotations(
	ctx context.Context,
	userID, bookID, deviceID string,
	seenRev int64,
	incoming []annotations.Item,
) ([]annotations.Item, error) {
	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("начало записи заметок: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var raw []byte
	err = tx.QueryRow(ctx, `
        SELECT items FROM wolfy.book_annotations
         WHERE user_id = $1 AND book_id = $2
         FOR UPDATE`, userID, bookID).Scan(&raw)
	switch {
	case err == nil:
	case errors.Is(err, pgx.ErrNoRows):
		raw = nil
	default:
		return nil, fmt.Errorf("чтение заметок книги: %w", err)
	}

	stored := []annotations.Item{}
	if raw != nil {
		if err := json.Unmarshal(raw, &stored); err != nil {
			return nil, fmt.Errorf("разбор заметок книги: %w", err)
		}
	}

	if _, err := tx.Exec(ctx, `
        INSERT INTO wolfy.book_annotations_devices (user_id, book_id, device_id, seen_rev)
        VALUES ($1, $2, $3, $4)
        ON CONFLICT (user_id, book_id, device_id) DO UPDATE SET
            seen_rev = GREATEST(wolfy.book_annotations_devices.seen_rev, excluded.seen_rev)`,
		userID, bookID, deviceID, seenRev); err != nil {
		return nil, fmt.Errorf("подтверждение прочтения: %w", err)
	}

	merged := annotations.Merge(stored, incoming)
	// Потолок проверяется после объединения, а не только на входе: два
	// устройства с валидными списками обязаны влезать в него вдвоём, а то,
	// что не влезает, — не честные списки, а отказ, не выборочная потеря.
	if len(merged) > annotations.MaxStored {
		return nil, fmt.Errorf("%w: объединённый список вырос сверх меры", annotations.ErrTooMany)
	}

	// Пометка стирается, только когда её видели все зарегистрированные
	// устройства этой книги. Устройство, которое больше не приходит, свой
	// seen не поднимает — и пометки, которых оно не видело, остаются.
	var confirmedRev int64
	if err := tx.QueryRow(ctx, `
        SELECT COALESCE(MIN(seen_rev), 0) FROM wolfy.book_annotations_devices
         WHERE user_id = $1 AND book_id = $2`, userID, bookID).Scan(&confirmedRev); err != nil {
		return nil, fmt.Errorf("сборка мусора заметок: %w", err)
	}
	merged = annotations.PruneTombstones(merged, confirmedRev)

	payload, err := json.Marshal(merged)
	if err != nil {
		return nil, fmt.Errorf("сериализация заметок книги: %w", err)
	}

	_, err = tx.Exec(ctx, `
        INSERT INTO wolfy.book_annotations (user_id, book_id, items)
        VALUES ($1, $2, $3)
        ON CONFLICT (user_id, book_id) DO UPDATE SET
            items = excluded.items,
            updated_at = now()`,
		userID, bookID, payload)
	if err != nil {
		return nil, fmt.Errorf("запись заметок книги: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("фиксация заметок книги: %w", err)
	}
	return merged, nil
}

// ConfirmAnnotationsSeen поднимает подтверждение устройства о долговечно
// сохранённой версии списка. Монотонно: меньше уже подтверждённого не
// становится — откат означал бы, что устройство потеряло данные, а сборщик
// мусора с таким устройством не имеет права торопиться.
//
// Отдельно от SaveBookAnnotations: читатель, который ни разу не правил,
// тоже держит копию и обязан учитываться сборщиком.
func (s *Store) ConfirmAnnotationsSeen(
	ctx context.Context,
	userID, bookID, deviceID string,
	seenRev int64,
) error {
	_, err := s.Pool.Exec(ctx, `
        INSERT INTO wolfy.book_annotations_devices (user_id, book_id, device_id, seen_rev)
        VALUES ($1, $2, $3, $4)
        ON CONFLICT (user_id, book_id, device_id) DO UPDATE SET
            seen_rev = GREATEST(wolfy.book_annotations_devices.seen_rev, excluded.seen_rev)`,
		userID, bookID, deviceID, seenRev)
	if err != nil {
		return fmt.Errorf("подтверждение прочтения: %w", err)
	}
	return nil
}
