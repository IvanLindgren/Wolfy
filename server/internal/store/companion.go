package store

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"

	"github.com/jackc/pgx/v5"
)

// Companion — профиль книжного компаньона так, как его хранит сервер.
//
// Профиль и набор реплик — JSON с устройства, проверенный библиотечным
// слоём. Сервер не разбирает его на поля: он назначает ревизию, хранит
// tombstone и отдаёт вторым устройствам.
type Companion struct {
	Profile     json.RawMessage `json:"profile,omitempty"`
	PhrasePack  json.RawMessage `json:"phrasePack,omitempty"`
	ProfileHash string          `json:"profileHash"`
	Rev         int64           `json:"rev"`
	Deleted     bool            `json:"deleted"`
}

// SaveCompanion записывает профиль компаньона.
//
// Правило то же, что у книг и карточек: побеждает последний записавший, а
// устаревшая живая копия не снимает tombstone — удаление, увиденное
// устройством раньше, чем пришедший профиль, сильнее него.
func (s *Store) SaveCompanion(ctx context.Context, tx pgx.Tx, userID string, rev int64, companion *Companion) error {
	if companion == nil || len(companion.Profile) == 0 {
		return nil
	}
	companionID, err := companionIDOf(companion.Profile)
	if err != nil {
		return fmt.Errorf("номер компаньона: %w", err)
	}
	_, err = tx.Exec(ctx, `
        INSERT INTO wolfy.companions (
            user_id, companion_id, profile, phrase_pack, profile_hash, rev, deleted)
        VALUES ($1::uuid, $2::uuid, $3, $4, $5, $6, $7)
        ON CONFLICT (user_id) DO UPDATE SET
            companion_id = EXCLUDED.companion_id,
            profile = EXCLUDED.profile,
            phrase_pack = EXCLUDED.phrase_pack,
            profile_hash = EXCLUDED.profile_hash,
            rev = EXCLUDED.rev,
            deleted = EXCLUDED.deleted,
            updated_at = now()
        WHERE NOT (
            wolfy.companions.deleted
            AND NOT EXCLUDED.deleted
            AND $8 < wolfy.companions.rev)`,
		userID, companionID, companion.Profile, companion.PhrasePack,
		companion.ProfileHash, rev, companion.Deleted, companion.Rev)
	if err != nil {
		return fmt.Errorf("запись компаньона: %w", err)
	}
	return nil
}

// companionIDOf достаёт идентификатор из профиля: клиент придумывает его сам,
// колонка обязана остаться uuid.
func companionIDOf(profile json.RawMessage) (string, error) {
	var parsed struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(profile, &parsed); err != nil {
		return "", err
	}
	if !reUUID.MatchString(parsed.ID) {
		return "", fmt.Errorf("не uuid: %q", parsed.ID)
	}
	return parsed.ID, nil
}

// reUUID — та же форма идентификаторов, что и в sync.go.
var reUUID = regexp.MustCompile(
	`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// companionSinceTx отдаёт профиль, изменившийся после курсора.
//
// Профиль один, поэтому чтение сводится к «есть ли строка новее курсора».
// Удалённый приезжает с пометкой: без неё второй компьютер оставил бы
// компаньона у себя навсегда.
func (s *Store) companionSinceTx(ctx context.Context, tx pgx.Tx, userID string, since, until int64) (*Companion, error) {
	var (
		companionID string
		profile     []byte
		pack        []byte
		hash        string
		rev         int64
		deleted     bool
	)
	err := tx.QueryRow(ctx, `
        SELECT companion_id::text, profile, phrase_pack, profile_hash, rev, deleted
        FROM wolfy.companions
        WHERE user_id = $1::uuid AND rev > $2 AND rev <= $3`,
		userID, since, until).Scan(&companionID, &profile, &pack, &hash, &rev, &deleted)
	switch {
	case err == pgx.ErrNoRows:
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("чтение компаньона: %w", err)
	}
	_ = companionID
	result := &Companion{
		Profile:     profile,
		PhrasePack:  pack,
		ProfileHash: hash,
		Rev:         rev,
		Deleted:     deleted,
	}
	return result, nil
}

// CompanionForAI отдаёт сохранённый профиль для серверной подстановки persona.
//
// ИИ-эндпоинты берут характер отсюда, а не из запроса: клиент не решает,
// каким компаньон для сервера считается.
func (s *Store) CompanionForAI(ctx context.Context, userID string) (*Companion, error) {
	var (
		profile []byte
		pack    []byte
		hash    string
		rev     int64
		deleted bool
	)
	err := s.Pool.QueryRow(ctx, `
        SELECT profile, phrase_pack, profile_hash, rev, deleted
        FROM wolfy.companions WHERE user_id = $1::uuid AND NOT deleted`, userID).
		Scan(&profile, &pack, &hash, &rev, &deleted)
	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("чтение компаньона: %w", err)
	}
	return &Companion{Profile: profile, PhrasePack: pack, ProfileHash: hash, Rev: rev}, nil
}

// CompanionPackByHash возвращает сохранённый набор реплик для того же
// характера. Повторный запрос с тем же profile hash не платит квотой.
func (s *Store) CompanionPackByHash(ctx context.Context, userID, profileHash string) (json.RawMessage, error) {
	var pack []byte
	err := s.Pool.QueryRow(ctx, `
        SELECT phrase_pack FROM wolfy.companion_phrase_packs
        WHERE user_id = $1::uuid AND profile_hash = $2 AND status = 'ready'`,
		userID, profileHash).Scan(&pack)
	if err == nil {
		return pack, nil
	}
	if err != pgx.ErrNoRows {
		return nil, fmt.Errorf("поиск кэша набора реплик: %w", err)
	}

	err = s.Pool.QueryRow(ctx, `
        SELECT phrase_pack FROM wolfy.companions
        WHERE user_id = $1::uuid AND NOT deleted AND profile_hash = $2 AND phrase_pack IS NOT NULL`,
		userID, profileHash).Scan(&pack)
	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("поиск набора реплик: %w", err)
	}
	return pack, nil
}

// ClaimCompanionPackGeneration атомарно назначает один запрос генератором.
// Зависшая после падения сервера заявка протухает через две минуты.
func (s *Store) ClaimCompanionPackGeneration(ctx context.Context, userID, profileHash string) (bool, error) {
	var claimed bool
	err := s.Pool.QueryRow(ctx, `
        INSERT INTO wolfy.companion_phrase_packs (user_id, profile_hash, status)
        VALUES ($1::uuid, $2, 'generating')
        ON CONFLICT (user_id, profile_hash) DO UPDATE SET
            status = 'generating', phrase_pack = NULL, updated_at = now()
        WHERE wolfy.companion_phrase_packs.status = 'generating'
          AND wolfy.companion_phrase_packs.updated_at < now() - interval '2 minutes'
        RETURNING true`, userID, profileHash).Scan(&claimed)
	if err == pgx.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("заявка генерации реплик: %w", err)
	}
	return claimed, nil
}

// CompleteCompanionPackGeneration фиксирует только полный проверенный набор.
func (s *Store) CompleteCompanionPackGeneration(ctx context.Context, userID, profileHash string, pack json.RawMessage) error {
	_, err := s.Pool.Exec(ctx, `
        UPDATE wolfy.companion_phrase_packs
        SET phrase_pack = $3, status = 'ready', updated_at = now()
        WHERE user_id = $1::uuid AND profile_hash = $2`, userID, profileHash, pack)
	if err != nil {
		return fmt.Errorf("сохранение кэша реплик: %w", err)
	}
	return nil
}

// ReleaseCompanionPackGeneration позволяет повторить запрос после отказа.
func (s *Store) ReleaseCompanionPackGeneration(ctx context.Context, userID, profileHash string) {
	_, _ = s.Pool.Exec(ctx, `
        DELETE FROM wolfy.companion_phrase_packs
        WHERE user_id = $1::uuid AND profile_hash = $2 AND status = 'generating'`,
		userID, profileHash)
}
