package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"

	"github.com/jackc/pgx/v5"

	"github.com/wolfy/server/internal/annotations"
)

// BookAnnotationsSync отдаёт сохранённый список отметок книги и поколение
// снимка, заодно подтверждая прочтение устройством.
//
// Чтение тоже регистрирует устройство и поднимает его подтверждение:
// держатель копии обязан блокировать сборку мусора, даже если ни разу не
// писал. Возвращаемое поколение клиент подтвердит следующим запросом —
// только после того, как долговечно сохранит снимок у себя.
func (s *Store) BookAnnotationsSync(
	ctx context.Context,
	userID, bookID, deviceID string,
	seenGeneration int64,
) (items []annotations.Item, generation int64, err error) {
	if err := s.ConfirmAnnotationsGeneration(ctx, userID, bookID, deviceID, seenGeneration); err != nil {
		return nil, 0, err
	}

	var raw []byte
	err = s.Pool.QueryRow(ctx, `
        SELECT items, generation FROM wolfy.book_annotations
         WHERE user_id = $1 AND book_id = $2`, userID, bookID).Scan(&raw, &generation)
	switch {
	case err == nil:
	case errors.Is(err, pgx.ErrNoRows):
		return []annotations.Item{}, 0, nil
	default:
		return nil, 0, fmt.Errorf("чтение заметок книги: %w", err)
	}

	items = []annotations.Item{}
	if raw != nil {
		if err := json.Unmarshal(raw, &items); err != nil {
			return nil, 0, fmt.Errorf("разбор заметок книги: %w", err)
		}
	}
	return items, generation, nil
}

// SaveBookAnnotations сливает присланный список с хранимым и возвращает
// результат вместе с новым поколением снимка.
//
// Всё внутри одной транзакции, и конкуренция закрыта с обеих сторон. Строку
// книги материализует INSERT ... ON CONFLICT DO NOTHING до SELECT FOR
// UPDATE: блокировка пустой строки невозможна, и два устройства, впервые
// синхронизирующие одну книгу одновременно, без этого прочитали бы оба
// «пусто» и последний UPSERT перезаписал бы первого.
//
// Порядок шагов внутри транзакции:
//  1. материализация строки и SELECT FOR UPDATE;
//  2. подтверждение поколения устройством — до сборки мусора, иначе это же
//     устройство заморозило бы стирание собственных пометок;
//  3. слияние по (Rev, Writer);
//  4. новое поколение, если состояние изменилось, и штамп им всех записей;
//  5. сборка мусора — только по минимуму подтверждений всех устройств.
func (s *Store) SaveBookAnnotations(
	ctx context.Context,
	userID, bookID, deviceID string,
	seenGeneration int64,
	incoming []annotations.Item,
) ([]annotations.Item, int64, error) {
	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return nil, 0, fmt.Errorf("начало записи заметок: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, `
        INSERT INTO wolfy.book_annotations (user_id, book_id)
        VALUES ($1, $2)
        ON CONFLICT (user_id, book_id) DO NOTHING`,
		userID, bookID); err != nil {
		return nil, 0, fmt.Errorf("материализация строки заметок: %w", err)
	}

	var storedRaw []byte
	var generation int64
	if err := tx.QueryRow(ctx, `
        SELECT items, generation FROM wolfy.book_annotations
         WHERE user_id = $1 AND book_id = $2
         FOR UPDATE`, userID, bookID).Scan(&storedRaw, &generation); err != nil {
		return nil, 0, fmt.Errorf("чтение заметок книги: %w", err)
	}

	stored := []annotations.Item{}
	if storedRaw != nil {
		if err := json.Unmarshal(storedRaw, &stored); err != nil {
			return nil, 0, fmt.Errorf("разбор заметок книги: %w", err)
		}
	}

	if _, err := tx.Exec(ctx, `
        INSERT INTO wolfy.book_annotations_devices (user_id, book_id, device_id, seen_generation)
        VALUES ($1, $2, $3, $4)
        ON CONFLICT (user_id, book_id, device_id) DO UPDATE SET
            seen_generation = GREATEST(wolfy.book_annotations_devices.seen_generation, excluded.seen_generation)`,
		userID, bookID, deviceID, seenGeneration); err != nil {
		return nil, 0, fmt.Errorf("подтверждение прочтения: %w", err)
	}

	merged := annotations.Merge(stored, incoming)
	if len(merged) > annotations.MaxItems {
		// Потолок один и на входе, и на хранении: состояние, которое сервер
		// выдает, обязано влезать обратно в запрос. Отказ детерминирован —
		// ничего не выбрасывается и не теряется молча.
		return nil, 0, fmt.Errorf("%w: объединённый список вырос сверх меры", annotations.ErrTooMany)
	}

	// Поколение растёт только вместе с состоянием: пустой повторный пуш
	// ничего не двигает, и устройства не гоняют подтверждения впустую.
	if !reflect.DeepEqual(merged, stored) {
		generation++
		for index := range merged {
			merged[index].Generation = generation
		}
	}

	// Пометка стирается, только когда её видели все зарегистрированные
	// устройства этой книги. Устройство, которое больше не приходит, своё
	// подтверждение не поднимает — и пометки, которых оно не видело, остаются.
	var confirmed int64
	if err := tx.QueryRow(ctx, `
        SELECT COALESCE(MIN(seen_generation), 0) FROM wolfy.book_annotations_devices
         WHERE user_id = $1 AND book_id = $2`, userID, bookID).Scan(&confirmed); err != nil {
		return nil, 0, fmt.Errorf("сборка мусора заметок: %w", err)
	}
	merged = annotations.PruneTombstones(merged, confirmed)

	payload, err := json.Marshal(merged)
	if err != nil {
		return nil, 0, fmt.Errorf("сериализация заметок книги: %w", err)
	}

	_, err = tx.Exec(ctx, `
        UPDATE wolfy.book_annotations
           SET items = $3, generation = $4, updated_at = now()
         WHERE user_id = $1 AND book_id = $2`,
		userID, bookID, payload, generation)
	if err != nil {
		return nil, 0, fmt.Errorf("запись заметок книги: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, 0, fmt.Errorf("фиксация заметок книги: %w", err)
	}
	return merged, generation, nil
}

// ConfirmAnnotationsGeneration поднимает подтверждение устройства о
// долговечно сохранённом снимке поколения. Монотонно: меньше уже
// подтверждённого не становится — откат означал бы, что устройство потеряло
// данные, а сборщик мусора с таким устройством не имеет права торопиться.
func (s *Store) ConfirmAnnotationsGeneration(
	ctx context.Context,
	userID, bookID, deviceID string,
	seenGeneration int64,
) error {
	_, err := s.Pool.Exec(ctx, `
        INSERT INTO wolfy.book_annotations_devices (user_id, book_id, device_id, seen_generation)
        VALUES ($1, $2, $3, $4)
        ON CONFLICT (user_id, book_id, device_id) DO UPDATE SET
            seen_generation = GREATEST(wolfy.book_annotations_devices.seen_generation, excluded.seen_generation)`,
		userID, bookID, deviceID, seenGeneration)
	if err != nil {
		return fmt.Errorf("подтверждение прочтения: %w", err)
	}
	return nil
}
