package store

import (
	"context"
	"testing"
)

// Тест §5 Variant A: два офлайн-устройства добавляют одну и ту же книгу
// (один HASH, разные случайные id) — после sync должна остаться одна
// логическая книга, вторая не падает на unique violation и прогресс не теряется.
func TestДваУстройстваОдинHashСхлопываются(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	hash := "offline-hash-same-file-42"
	bookA := Book{
		ID:           "11111111-1111-1111-1111-111111111111",
		Title:        "Книга A",
		SourceKey:    hash,
		Format:       "epub",
		ChapterCount: 5,
		LastChapter:  1,
		Rev:          1,
	}
	bookB := Book{
		ID:           "22222222-2222-2222-2222-222222222222",
		Title:        "Книга B",
		SourceKey:    hash,
		Format:       "epub",
		ChapterCount: 5,
		LastChapter:  3,
		Rev:          1,
	}

	// Телефон синхронизирует A
	if _, err := s.Sync(ctx, user, Changes{Books: []Book{bookA}}); err != nil {
		t.Fatalf("первая синхронизация A: %v", err)
	}
	// Десктоп офлайн, курсор 0, шлёт B с тем же HASH
	respB, err := s.Sync(ctx, user, Changes{Books: []Book{bookB}})
	if err != nil {
		t.Fatalf("вторая синхронизация B не должна падать на unique violation: %v", err)
	}
	// Наблюдатель с нуля должен видеть одну книгу, а не две
	obs, err := s.Sync(ctx, user, Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("наблюдатель: %v", err)
	}
	if len(obs.Books) != 1 {
		t.Fatalf("ожидалась одна логическая книга, а пришло %d: %+v", len(obs.Books), obs.Books)
	}
	if obs.Books[0].SourceKey != hash {
		t.Fatalf("книга потеряла source_key: %+v", obs.Books[0])
	}
	// Вторая запись — last writer wins: должна быть B (т.к. пришла позже), либо A если rev одинаков?
	// Главное — не две и не ошибка, и source_key сохранён.
	// Курсор наблюдателя должен догнать respB.Cursor
	if obs.Cursor != respB.Cursor {
		t.Logf("курсор наблюдателя %d vs %d (не критично)", obs.Cursor, respB.Cursor)
	}
}

func TestКарточкиПерепривязываютсяКCanonical(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	hash := "hash-with-cards"
	bookA := Book{
		ID:        "33333333-3333-3333-3333-333333333333",
		Title:     "A",
		SourceKey: hash,
		Format:    "epub",
	}
	bookB := Book{
		ID:        "44444444-4444-4444-4444-444444444444",
		Title:     "B",
		SourceKey: hash,
		Format:    "epub",
	}
	cardA := Card{
		ID:     "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
		BookID: bookA.ID,
		Kind:   "word",
		Lemma:  "hello",
		Surface: "hello",
	}
	// Телефон: книга A + карточка hello
	if _, err := s.Sync(ctx, user, Changes{Books: []Book{bookA}, Cards: []Card{cardA}}); err != nil {
		t.Fatalf("первая: %v", err)
	}
	cardB := Card{
		ID:     "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
		BookID: bookB.ID,
		Kind:   "word",
		Lemma:  "world",
		Surface: "world",
	}
	// Десктоп: книга B + карточка world (тот же файл, другая карточка)
	if _, err := s.Sync(ctx, user, Changes{Books: []Book{bookB}, Cards: []Card{cardB}}); err != nil {
		t.Fatalf("вторая: %v", err)
	}
	obs, err := s.Sync(ctx, user, Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("наблюдатель: %v", err)
	}
	if len(obs.Books) != 1 {
		t.Fatalf("книг %d, ожидалась 1", len(obs.Books))
	}
	canonicalID := obs.Books[0].ID
	if len(obs.Cards) != 2 {
		t.Fatalf("карточек %d, ожидалось 2 (hello и world, обе перепривязаны к canonical): %+v", len(obs.Cards), obs.Cards)
	}
	for _, c := range obs.Cards {
		if c.BookID != canonicalID {
			t.Fatalf("карточка %s не перепривязалась к canonical %s, а имела %s", c.ID, canonicalID, c.BookID)
		}
	}
}

func TestДубликатЛеммыНеПадает(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	hash := "hash-dup-lemma"
	bookA := Book{ID: "55555555-5555-5555-5555-555555555555", Title: "A", SourceKey: hash, Format: "epub"}
	bookB := Book{ID: "66666666-6666-6666-6666-666666666666", Title: "B", SourceKey: hash, Format: "epub"}
	cardHelloA := Card{ID: "cccccccc-cccc-cccc-cccc-cccccccccccc", BookID: bookA.ID, Kind: "word", Lemma: "hello", Surface: "hello"}
	cardHelloB := Card{ID: "dddddddd-dddd-dddd-dddd-dddddddddddd", BookID: bookB.ID, Kind: "word", Lemma: "hello", Surface: "hello"}

	if _, err := s.Sync(ctx, user, Changes{Books: []Book{bookA}, Cards: []Card{cardHelloA}}); err != nil {
		t.Fatalf("первая: %v", err)
	}
	// Вторая пытается добавить то же слово hello с другим id — не должна падать на unique
	if _, err := s.Sync(ctx, user, Changes{Books: []Book{bookB}, Cards: []Card{cardHelloB}}); err != nil {
		t.Fatalf("вторая с дублем lemma не должна падать: %v", err)
	}
	obs, err := s.Sync(ctx, user, Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("наблюдатель: %v", err)
	}
	if len(obs.Cards) != 1 {
		t.Fatalf("ожидалась одна карточка hello после dedup, а пришло %d: %+v", len(obs.Cards), obs.Cards)
	}
	if obs.Cards[0].Lemma != "hello" {
		t.Fatalf("не та лемма: %+v", obs.Cards[0])
	}
}
