package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// PracticeComponent — непрозрачный blob одного устройства.
//
// Go не знает математику PracticeState (days union, max per device,
// best_floor …). Он хранит и отдаёт JSON как есть, а сливает их Rust
// (PracticeState::merge). Поэтому здесь нет подсчётов, а есть только
// проверка формы и лимиты хранения.
type PracticeComponent struct {
	DeviceID  string          `json:"deviceId"`
	Practice  json.RawMessage `json:"practice"`
	UpdatedAt time.Time       `json:"updatedAt,omitempty"`
}

// Пределы (§7).
//
// Практика — десятки килобайт (дни + несколько счётчиков по устройствам),
// а не мегабайты. Лимит защищает от ошибки клиента, который записал бы
// туда мусор, и от злоупотребления: хранение чужого большого blob дорого.
const (
	MaxPracticeBytes    = 256 * 1024 // один component, сырой JSON
	MaxPracticeDevices  = 64         // устройств на пользователя
	MaxPracticeDeviceID = 64
)

// ErrPracticeTooLarge — blob не влезает в лимит.
var ErrPracticeTooLarge = errors.New("состояние тренировки слишком большое")

// ErrTooManyDevices — слишком много устройств (§7: хранить всё, но не бесконечно).
var ErrTooManyDevices = errors.New("слишком много устройств для тренировки")

// deviceIDPattern — допустимый ID реплики.
//
// Строгий набор, чтобы не хранить управляющие символы и не плодить
// различимые только пробелами имена. Допускает uuid, legacy, phone-1 и т.п.
var deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

func validatePracticeDeviceID(deviceID string) error {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return errors.New("deviceId пустой")
	}
	if len(deviceID) > MaxPracticeDeviceID {
		return fmt.Errorf("deviceId длиннее %d", MaxPracticeDeviceID)
	}
	if !deviceIDPattern.MatchString(deviceID) {
		return fmt.Errorf("deviceId имеет неверный формат: %q", deviceID)
	}
	return nil
}

// validatePracticeJSON проверяет, что practice — валидный JSON-объект
// разумного размера. Содержимое не интерпретируется: сервер opaque.
func validatePracticeJSON(practice json.RawMessage) error {
	if len(practice) == 0 {
		return errors.New("practice пуст")
	}
	if len(practice) > MaxPracticeBytes {
		return fmt.Errorf("%w: %d > %d", ErrPracticeTooLarge, len(practice), MaxPracticeBytes)
	}
	if !json.Valid(practice) {
		return errors.New("practice не JSON")
	}
	// Практика ожидается объектом, а не массивом/скаляром.
	trimmed := strings.TrimSpace(string(practice))
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return errors.New("practice должен быть JSON-объектом")
	}
	return nil
}

// SavePracticeTx записывает/обновляет practice одного устройства в рамках
// транзакции. Идемпотентно и last-write-wins на уровне устройства.
func (s *Store) SavePracticeTx(ctx context.Context, tx pgx.Tx, userID, deviceID string, practice json.RawMessage) error {
	if err := validatePracticeDeviceID(deviceID); err != nil {
		return err
	}
	if err := validatePracticeJSON(practice); err != nil {
		return err
	}
	// Лимит на число устройств: проверяем только при вставке нового device_id.
	// Обновление существующего — не прирост, и лимит его не касается.
	var exists bool
	err := tx.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM wolfy.practice_components WHERE user_id = $1 AND device_id = $2)`,
		userID, deviceID).Scan(&exists)
	if err != nil {
		return fmt.Errorf("проверка устройства тренировки: %w", err)
	}
	if !exists {
		var count int
		err = tx.QueryRow(ctx,
			`SELECT count(*) FROM wolfy.practice_components WHERE user_id = $1`,
			userID).Scan(&count)
		if err != nil {
			return fmt.Errorf("подсчёт устройств тренировки: %w", err)
		}
		if count >= MaxPracticeDevices {
			return ErrTooManyDevices
		}
	}

	_, err = tx.Exec(ctx, `
        INSERT INTO wolfy.practice_components (user_id, device_id, practice_json, updated_at)
        VALUES ($1, $2, $3::jsonb, now())
        ON CONFLICT (user_id, device_id) DO UPDATE
            SET practice_json = excluded.practice_json,
                updated_at = now()`,
		userID, deviceID, practice)
	if err != nil {
		return fmt.Errorf("запись состояния тренировки: %w", err)
	}
	return nil
}

// SavePractice — обёртка без внешней транзакции.
//
// Для атомарного лимита устройств открывает короткую транзакцию и
// делегирует в SavePracticeTx, чтобы проверка count и upsert видели один
// снимок.
func (s *Store) SavePractice(ctx context.Context, userID, deviceID string, practice json.RawMessage) error {
	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("начало записи тренировки: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := s.SavePracticeTx(ctx, tx, userID, deviceID, practice); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("фиксация тренировки: %w", err)
	}
	return nil
}

// ListPracticeComponentsTx читает все per-device blobs пользователя в рамках
// транзакции. Возвращает отсортированный по device_id список для детерминизма.
func (s *Store) ListPracticeComponentsTx(ctx context.Context, tx pgx.Tx, userID string) ([]PracticeComponent, error) {
	rows, err := tx.Query(ctx, `
        SELECT device_id, practice_json, updated_at
          FROM wolfy.practice_components
         WHERE user_id = $1
         ORDER BY device_id`, userID)
	if err != nil {
		return nil, fmt.Errorf("чтение состояний тренировки: %w", err)
	}
	defer rows.Close()

	out := make([]PracticeComponent, 0)
	for rows.Next() {
		var c PracticeComponent
		var raw []byte
		if err := rows.Scan(&c.DeviceID, &raw, &c.UpdatedAt); err != nil {
			return nil, fmt.Errorf("разбор состояния тренировки: %w", err)
		}
		c.Practice = json.RawMessage(raw)
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("чтение состояний тренировки: %w", err)
	}
	return out, nil
}

// ListPracticeComponents — обёртка без внешней транзакции.
//
// Возвращает пустой slice, если у пользователя ещё нет тренировки.
func (s *Store) ListPracticeComponents(ctx context.Context, userID string) ([]PracticeComponent, error) {
	rows, err := s.Pool.Query(ctx, `
        SELECT device_id, practice_json, updated_at
          FROM wolfy.practice_components
         WHERE user_id = $1
         ORDER BY device_id`, userID)
	if err != nil {
		return nil, fmt.Errorf("чтение состояний тренировки: %w", err)
	}
	defer rows.Close()

	out := make([]PracticeComponent, 0)
	for rows.Next() {
		var c PracticeComponent
		var raw []byte
		if err := rows.Scan(&c.DeviceID, &raw, &c.UpdatedAt); err != nil {
			return nil, fmt.Errorf("разбор состояния тренировки: %w", err)
		}
		c.Practice = json.RawMessage(raw)
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("чтение состояний тренировки: %w", err)
	}
	return out, nil
}

// PracticeComponentsMap — удобная форма для клиента, который хочет отдать
// blobs в Rust по одному: device_id -> practice_json.
func (s *Store) PracticeComponentsMap(ctx context.Context, userID string) (map[string]json.RawMessage, error) {
	components, err := s.ListPracticeComponents(ctx, userID)
	if err != nil {
		return nil, err
	}
	m := make(map[string]json.RawMessage, len(components))
	for _, c := range components {
		m[c.DeviceID] = c.Practice
	}
	return m, nil
}
