package store

import (
	"context"
	"testing"
)

// liveBooks и liveCards отделяют живые записи от надгробий.
//
// Проигравшая строка после §5-слияния не стирается, а помечается удалённой:
// устройство, у которого она ещё жива, узнаёт о её судьбе обычным путём.
// Поэтому «осталась одна книга» на языке выборки значит «одна живая», а не
// «одна строка», и тесты обязаны считать именно так — иначе они запрещали бы
// ровно то, ради чего надгробия и заведены.
func liveBooks(books []Book) []Book {
	var live []Book
	for _, b := range books {
		if !b.Deleted {
			live = append(live, b)
		}
	}
	return live
}

func liveCards(cards []Card) []Card {
	var live []Card
	for _, c := range cards {
		if !c.Deleted {
			live = append(live, c)
		}
	}
	return live
}

// buried отвечает, доехало ли надгробие для этого номера.
func buriedBook(books []Book, id string) bool {
	for _, b := range books {
		if b.ID == id && b.Deleted {
			return true
		}
	}
	return false
}

func buriedCard(cards []Card, id string) bool {
	for _, c := range cards {
		if c.ID == id && c.Deleted {
			return true
		}
	}
	return false
}

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
	live := liveBooks(obs.Books)
	if len(live) != 1 {
		t.Fatalf("ожидалась одна живая книга, а пришло %d: %+v", len(live), obs.Books)
	}
	if live[0].SourceKey != hash {
		t.Fatalf("книга потеряла source_key: %+v", live[0])
	}
	// Проигравший номер обязан приехать надгробием, а не исчезнуть молча:
	// у телефона книга A ещё лежит на полке, и без этой записи он оставит её
	// у себя и заведёт заново на следующей отправке.
	loser := bookA.ID
	if live[0].ID == bookA.ID {
		loser = bookB.ID
	}
	if !buriedBook(obs.Books, loser) {
		t.Fatalf("проигравший номер %s не похоронен: %+v", loser, obs.Books)
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
		ID:      "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
		BookID:  bookA.ID,
		Kind:    "word",
		Lemma:   "hello",
		Surface: "hello",
	}
	// Телефон: книга A + карточка hello
	if _, err := s.Sync(ctx, user, Changes{Books: []Book{bookA}, Cards: []Card{cardA}}); err != nil {
		t.Fatalf("первая: %v", err)
	}
	cardB := Card{
		ID:      "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
		BookID:  bookB.ID,
		Kind:    "word",
		Lemma:   "world",
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
	live := liveBooks(obs.Books)
	if len(live) != 1 {
		t.Fatalf("живых книг %d, ожидалась 1: %+v", len(live), obs.Books)
	}
	canonicalID := live[0].ID
	cards := liveCards(obs.Cards)
	if len(cards) != 2 {
		t.Fatalf("живых карточек %d, ожидалось 2 (hello и world, обе перепривязаны к canonical): %+v", len(cards), obs.Cards)
	}
	for _, c := range cards {
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
	cards := liveCards(obs.Cards)
	if len(cards) != 1 {
		t.Fatalf("ожидалась одна живая карточка hello после dedup, а пришло %d: %+v", len(cards), obs.Cards)
	}
	if cards[0].Lemma != "hello" {
		t.Fatalf("не та лемма: %+v", cards[0])
	}
	// Проигравший номер обязан доехать надгробием: без него устройство,
	// приславшее дубль, оставило бы у себя вторую запись на то же слово, и
	// слово всплывало бы в колоде дважды.
	loser := cardHelloA.ID
	if cards[0].ID == cardHelloA.ID {
		loser = cardHelloB.ID
	}
	if !buriedCard(obs.Cards, loser) {
		t.Fatalf("проигравший номер карточки %s не похоронен: %+v", loser, obs.Cards)
	}
}

// Эталон, общий с ядром.
//
// `canonicalBookID` и Rust-овый `canonical_book_id` обязаны давать один номер:
// по нему сервер решает, какая из двух строк с одним source_key каноническая.
// Разойдутся — вернётся вечное перекидывание книги между устройствами.
// Те же пары проверяются в `core/src/library/book.rs`.
func TestCanonicalBookIDСовпадаетСЯдром(t *testing.T) {
	эталон := map[string]string{
		"abc123":          "62cca241-2f0a-5f65-9ec5-73768c755796",
		"hash123":         "87acf686-b595-5a9f-916c-695c49355d5e",
		"same-hash-sync":  "6f258824-9163-55be-aae4-aa460c08006d",
		"deadbeefcafe123": "e06a9c5b-f253-52d8-bbf7-e99284ce4ac1",
	}
	for ключ, номер := range эталон {
		if got := canonicalBookID(ключ); got != номер {
			t.Errorf("canonicalBookID(%q) = %q, ожидали %q", ключ, got, номер)
		}
	}
	if got := canonicalBookID(""); got != "" {
		t.Errorf("пустой отпечаток не каноникализируется, получили %q", got)
	}
}

// Устройство со старой копией библиотеки шлёт легаси-номер книги вместе с
// карточками на него — сервер уже держит эту книгу под каноническим номером.
//
// Такой отправкой заканчивается любое обновление, пока хоть одно устройство
// не переехало на §5: канонический номер уже на сервере, а телефон в кармане
// про него ещё не знает. Книга при этом вливается в канонический номер, чужой
// номер не заводится — и карточки, присланные в той же посылке, обязаны
// приехать вместе с ней. Иначе внешний ключ роняет всю транзакцию, и это
// устройство перестаёт синхронизироваться навсегда: ни новых слов, ни
// прогресса чтения.
func TestЛегасиНомерКнигиНеРоняетКарточки(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	hash := "hash-legacy-device"
	canonical := canonicalBookID(hash)
	if canonical == "" {
		t.Fatalf("канонический номер не посчитался для %q", hash)
	}

	// Обновлённое устройство: книга уже под каноническим номером.
	if _, err := s.Sync(ctx, user, Changes{Books: []Book{{
		ID:        canonical,
		Title:     "Дракула",
		SourceKey: hash,
		Format:    "epub",
	}}}); err != nil {
		t.Fatalf("канонический номер не записался: %v", err)
	}

	// Старое устройство: тот же файл под случайным номером, и слово к нему.
	legacy := "55555555-5555-5555-5555-555555555555"
	if legacy == canonical {
		t.Fatalf("тест бессмыслен: легаси-номер совпал с каноническим")
	}
	resp, err := s.Sync(ctx, user, Changes{
		Books: []Book{{ID: legacy, Title: "Дракула", SourceKey: hash, Format: "epub"}},
		Cards: []Card{{
			ID:      "cccccccc-cccc-cccc-cccc-cccccccccccc",
			BookID:  legacy,
			Kind:    "word",
			Lemma:   "coffin",
			Surface: "coffin",
		}},
	})
	if err != nil {
		t.Fatalf("отправка со старого устройства упала: %v", err)
	}
	_ = resp

	obs, err := s.Sync(ctx, user, Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("наблюдатель: %v", err)
	}
	live := 0
	for _, b := range obs.Books {
		if !b.Deleted {
			live++
			if b.ID != canonical {
				t.Fatalf("выжила книга %s, а канонический номер %s", b.ID, canonical)
			}
		}
	}
	if live != 1 {
		t.Fatalf("живых книг %d, ожидалась одна: %+v", live, obs.Books)
	}
	if len(obs.Cards) != 1 {
		t.Fatalf("карточек %d, ожидалась одна: %+v", len(obs.Cards), obs.Cards)
	}
	if obs.Cards[0].BookID != canonical {
		t.Fatalf("карточка осталась на несуществующем номере %s вместо %s",
			obs.Cards[0].BookID, canonical)
	}
}
