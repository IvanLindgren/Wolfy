package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// DiscoveryProfile синхронизируется через сервер и потому одинаков на всех
// устройствах пользователя.
type DiscoveryProfile struct {
	EnglishLevel       string   `json:"englishLevel"`
	Genres             []string `json:"genres"`
	OnboardingComplete bool     `json:"onboardingComplete"`
}

// DiscoveryReaction — сигнал для локального векторного ранжирования.
type DiscoveryReaction struct {
	ItemID      string    `json:"itemId"`
	ContentType string    `json:"contentType"`
	Liked       bool      `json:"liked"`
	Added       bool      `json:"added"`
	Embedding   []float64 `json:"embedding"`
	Genres      []string  `json:"genres"`
}

func (s *Store) DiscoveryProfile(ctx context.Context, userID string) (DiscoveryProfile, error) {
	var profile DiscoveryProfile
	var genres []byte
	err := s.Pool.QueryRow(ctx, `
        SELECT english_level, genres, onboarding_complete
          FROM wolfy.discovery_profiles
         WHERE user_id = $1`, userID).
		Scan(&profile.EnglishLevel, &genres, &profile.OnboardingComplete)
	if err != nil {
		// Отсутствующая строка означает первый вход, а не ошибку.
		if errors.Is(err, pgx.ErrNoRows) {
			profile.Genres = []string{}
			return profile, nil
		}
		return DiscoveryProfile{}, fmt.Errorf("чтение профиля рекомендаций: %w", err)
	}
	if err := json.Unmarshal(genres, &profile.Genres); err != nil {
		return DiscoveryProfile{}, fmt.Errorf("разбор жанров: %w", err)
	}
	return profile, nil
}

func (s *Store) SaveDiscoveryProfile(ctx context.Context, userID string, profile DiscoveryProfile) error {
	genres, err := json.Marshal(profile.Genres)
	if err != nil {
		return fmt.Errorf("сериализация жанров: %w", err)
	}
	_, err = s.Pool.Exec(ctx, `
        INSERT INTO wolfy.discovery_profiles
            (user_id, english_level, genres, onboarding_complete)
        VALUES ($1, $2, $3, $4)
        ON CONFLICT (user_id) DO UPDATE SET
            english_level = excluded.english_level,
            genres = excluded.genres,
            onboarding_complete = excluded.onboarding_complete,
            updated_at = now()`,
		userID, profile.EnglishLevel, genres, profile.OnboardingComplete)
	if err != nil {
		return fmt.Errorf("запись профиля рекомендаций: %w", err)
	}
	return nil
}

func (s *Store) DiscoveryReactions(ctx context.Context, userID string) ([]DiscoveryReaction, error) {
	rows, err := s.Pool.Query(ctx, `
        SELECT item_id, content_type, liked, added, embedding, genres
          FROM wolfy.discovery_reactions
         WHERE user_id = $1`, userID)
	if err != nil {
		return nil, fmt.Errorf("чтение реакций: %w", err)
	}
	defer rows.Close()

	reactions := make([]DiscoveryReaction, 0)
	for rows.Next() {
		var reaction DiscoveryReaction
		var embedding, genres []byte
		if err := rows.Scan(&reaction.ItemID, &reaction.ContentType, &reaction.Liked,
			&reaction.Added, &embedding, &genres); err != nil {
			return nil, fmt.Errorf("разбор реакции: %w", err)
		}
		if err := json.Unmarshal(embedding, &reaction.Embedding); err != nil {
			return nil, fmt.Errorf("разбор вектора реакции: %w", err)
		}
		if err := json.Unmarshal(genres, &reaction.Genres); err != nil {
			return nil, fmt.Errorf("разбор жанров реакции: %w", err)
		}
		reactions = append(reactions, reaction)
	}
	return reactions, rows.Err()
}

func (s *Store) SaveDiscoveryReaction(ctx context.Context, userID string, reaction DiscoveryReaction) error {
	embedding, err := json.Marshal(reaction.Embedding)
	if err != nil {
		return fmt.Errorf("сериализация вектора: %w", err)
	}
	genres, err := json.Marshal(reaction.Genres)
	if err != nil {
		return fmt.Errorf("сериализация жанров реакции: %w", err)
	}
	_, err = s.Pool.Exec(ctx, `
        INSERT INTO wolfy.discovery_reactions
            (user_id, item_id, content_type, liked, added, embedding, genres)
        VALUES ($1, $2, $3, $4, $5, $6, $7)
        ON CONFLICT (user_id, item_id) DO UPDATE SET
            liked = wolfy.discovery_reactions.liked OR excluded.liked,
            added = wolfy.discovery_reactions.added OR excluded.added,
            embedding = excluded.embedding,
            genres = excluded.genres,
            updated_at = now()`,
		userID, reaction.ItemID, reaction.ContentType, reaction.Liked,
		reaction.Added, embedding, genres)
	if err != nil {
		return fmt.Errorf("запись реакции: %w", err)
	}
	return nil
}
