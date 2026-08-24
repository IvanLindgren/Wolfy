package annotations

import (
	"math/rand"
	"reflect"
	"testing"
)

func tone(value int) *int { return &value }

func TestMergeKeepsHigherRevision(t *testing.T) {
	base := []Item{
		{ID: "a", Chapter: 1, Start: 4, End: 7, Note: "старое", Rev: 1, Writer: "phone", CreatedAt: 90, UpdatedAt: 100},
	}
	incoming := []Item{
		{ID: "a", Chapter: 1, Start: 4, End: 7, Note: "новое", Rev: 2, Writer: "phone", CreatedAt: 90, UpdatedAt: 200},
	}

	merged := Merge(base, incoming)
	if len(merged) != 1 || merged[0].Note != "новое" {
		t.Fatalf("слияние потеряло правку: %+v", merged)
	}

	// Порядок сторон не важен: у большей версии правда и в обратную сторону,
	// иначе устройство с отставшим списком перезаписало бы свежее.
	merged = Merge(incoming, base)
	if len(merged) != 1 || merged[0].Note != "новое" {
		t.Fatalf("слияние зависит от порядка сторон: %+v", merged)
	}
}

func TestMergeResolvesEqualRevisionByWriter(t *testing.T) {
	// Два устройства правят одну запись с одинаковым номером версии —
	// возможный исход одновременной офлайн-правки. Исход обязан не зависеть
	// от порядка сторон, иначе устройства разойдутся.
	left := []Item{{ID: "a", Chapter: 0, Start: 1, End: 2, Note: "первое", Rev: 5, Writer: "aaa"}}
	right := []Item{{ID: "a", Chapter: 0, Start: 1, End: 2, Note: "второе", Rev: 5, Writer: "bbb"}}

	forward := Merge(left, right)
	backward := Merge(right, left)
	if len(forward) != 1 || len(backward) != 1 {
		t.Fatalf("слияние потеряло запись: %+v / %+v", forward, backward)
	}
	if forward[0].Note != backward[0].Note {
		t.Fatalf("равная версия решилась по-разному: %q против %q",
			forward[0].Note, backward[0].Note)
	}
	// Победитель — больший писатель: "bbb" > "aaa".
	if forward[0].Note != "второе" {
		t.Fatalf("выбран неожиданный победитель: %q", forward[0].Note)
	}
}

func TestMergeTotalOrderCoversEveryField(t *testing.T) {
	// Раньше порядок равных (Rev, Writer) решался слепком, в который не
	// входили место и время правки — две записи с разным Start и равным
	// слепком расходились бы по порядку сторон. Теперь сравнение покрывает
	// все поля, и каждое из них обязано решаться одинаково в обе стороны.
	cases := []struct {
		name  string
		patch func(Item) Item
	}{
		{"глава", func(i Item) Item { i.Chapter = 3; return i }},
		{"начало", func(i Item) Item { i.Start = 20; return i }},
		{"конец", func(i Item) Item { i.End = 25; return i }},
		{"краска", func(i Item) Item { i.Tone = tone(7); return i }},
		{"цитата", func(i Item) Item { i.Quote = "z"; return i }},
		{"заметка", func(i Item) Item { i.Note = "z"; return i }},
		{"время создания", func(i Item) Item { i.CreatedAt = 99; return i }},
		{"время правки", func(i Item) Item { i.UpdatedAt = 99; return i }},
		{"пометка удаления", func(i Item) Item { i.Deleted = true; return i }},
		{"поколение", func(i Item) Item { i.Generation = 4; return i }},
	}
	base := Item{ID: "a", Chapter: 0, Start: 10, End: 12, Tone: tone(1),
		Quote: "a", Note: "a", Rev: 5, Writer: "phone", CreatedAt: 1, UpdatedAt: 1}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			left := []Item{base}
			right := []Item{test.patch(base)}

			forward := Merge(left, right)
			backward := Merge(right, left)
			if !reflect.DeepEqual(forward, backward) {
				t.Fatalf("порядок сторон решает исход: %+v против %+v", forward, backward)
			}
			// Победитель — изменённая запись: она больше по каждому из полей.
			if !reflect.DeepEqual(forward[0], test.patch(base)) {
				t.Fatalf("выбран неожиданный победитель: %+v", forward[0])
			}
		})
	}
}

// Слияние — объединение максимумов по полному порядку, поэтому обязано быть
// коммутативным, ассоциативным и идемпотентным. Проверяется на случайных
// списках с общими номерами записей — ровно тот случай, где эти свойства
// разъезжаются первыми.
func TestMergeIsCommutativeAssociativeIdempotent(t *testing.T) {
	rng := rand.New(rand.NewSource(42))
	for round := 0; round < 300; round++ {
		a := randomItems(rng, 10)
		b := randomItems(rng, 10)
		c := randomItems(rng, 10)

		if got, want := Merge(a, b), Merge(b, a); !reflect.DeepEqual(got, want) {
			t.Fatalf("коммутативность нарушена:\n%+v\n%+v", got, want)
		}
		if got, want := Merge(Merge(a, b), c), Merge(a, Merge(b, c)); !reflect.DeepEqual(got, want) {
			t.Fatalf("ассоциативность нарушена:\n%+v\n%+v", got, want)
		}
		if got, want := Merge(a, a), Merge(a, nil); !reflect.DeepEqual(got, want) {
			t.Fatalf("идемпотентность нарушена:\n%+v\n%+v", got, want)
		}
	}
}

// randomItems собирает список уникальных записей из общего пула номеров,
// чтобы у разных списков были конфликты за одни и те же записи.
func randomItems(rng *rand.Rand, size int) []Item {
	total := 20
	pick := rng.Perm(total)[:rng.Intn(total)+1]
	items := make([]Item, 0, len(pick))
	for _, number := range pick {
		items = append(items, Item{
			ID:         string(rune('a' + number)),
			Chapter:    rng.Intn(4),
			Start:      rng.Intn(30),
			End:        rng.Intn(30) + 30,
			Tone:       randomTone(rng),
			Quote:      []string{"", "to be", "z"}[rng.Intn(3)],
			Note:       []string{"", "мысль", "z"}[rng.Intn(3)],
			Rev:        int64(rng.Intn(10) + 1),
			Writer:     []string{"aaa", "bbb", "ccc"}[rng.Intn(3)],
			Generation: int64(rng.Intn(4)),
			CreatedAt:  int64(rng.Intn(5)),
			UpdatedAt:  int64(rng.Intn(5)),
			Deleted:    rng.Intn(2) == 1,
		})
	}
	return items
}

func randomTone(rng *rand.Rand) *int {
	switch rng.Intn(3) {
	case 0:
		return nil
	case 1:
		return tone(rng.Intn(MaxTone) + 1)
	default:
		return tone(-1) // битые краски разрешены только в property-тестах
	}
}

func TestMergeUnionsDistinctDevices(t *testing.T) {
	base := []Item{{ID: "phone", Chapter: 0, Start: 2, End: 5, Tone: tone(3), Rev: 1, Writer: "phone", CreatedAt: 10, UpdatedAt: 10}}
	incoming := []Item{{ID: "laptop", Chapter: 2, Start: 9, End: 9, Note: "с ноутбука", Rev: 3, Writer: "laptop", CreatedAt: 20, UpdatedAt: 20}}

	merged := Merge(base, incoming)
	if len(merged) != 2 {
		t.Fatalf("чужие отметки не объединились: %+v", merged)
	}
	// Порядок чтения: сначала по главе, потом по месту.
	if merged[0].ID != "phone" || merged[1].ID != "laptop" {
		t.Fatalf("порядок заметок нарушен: %s, %s", merged[0].ID, merged[1].ID)
	}
}

func TestMergeCarriesTombstoneToTheOtherSide(t *testing.T) {
	base := []Item{{ID: "a", Chapter: 0, Start: 1, End: 3, Note: "живая", Rev: 2, Writer: "phone", CreatedAt: 10, UpdatedAt: 10}}
	incoming := []Item{{ID: "a", Chapter: 0, Start: 1, End: 3, Deleted: true, Rev: 3, Writer: "phone", CreatedAt: 10, UpdatedAt: 30}}

	merged := Merge(base, incoming)
	if len(merged) != 1 || !merged[0].Deleted {
		t.Fatalf("удаление не доехало до второго списка: %+v", merged)
	}

	// Старый список без пометки не воскрешает запись: его версия меньше.
	merged = Merge(incoming, base)
	if len(merged) != 1 || !merged[0].Deleted {
		t.Fatalf("устаревшая копия воскресила удалённое: %+v", merged)
	}
}

func TestMergeSortsStableAcrossRuns(t *testing.T) {
	first := Merge(
		nil,
		[]Item{
			{ID: "b", Chapter: 0, Start: 8, End: 9, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1},
			{ID: "a", Chapter: 0, Start: 2, End: 3, Rev: 2, Writer: "w", CreatedAt: 2, UpdatedAt: 2},
			{ID: "c", Chapter: 1, Start: 0, End: 0, Rev: 3, Writer: "w", CreatedAt: 3, UpdatedAt: 3},
		},
	)
	second := Merge(first, nil)
	for index := range first {
		if first[index].ID != second[index].ID {
			t.Fatalf("повторное слияние перемешало список")
		}
	}
}

func TestPruneTombstonesDropsOnlyConfirmedByGeneration(t *testing.T) {
	items := []Item{
		{ID: "seen", Chapter: 0, Start: 0, End: 1, Deleted: true, Rev: 2, Writer: "phone", Generation: 2},
		{ID: "unseen", Chapter: 0, Start: 2, End: 3, Deleted: true, Rev: 4, Writer: "phone", Generation: 4},
		{ID: "unstamped", Chapter: 0, Start: 6, End: 7, Deleted: true, Rev: 6, Writer: "phone"},
		{ID: "alive", Chapter: 0, Start: 4, End: 5, Rev: 3, Writer: "phone", Generation: 3},
	}

	pruned := PruneTombstones(items, 3)
	ids := make([]string, 0, len(pruned))
	for _, item := range pruned {
		ids = append(ids, item.ID)
	}
	// Пометка поколения 2 подтверждена (порог 3) и стёрта; поколение 4 — ещё
	// нет: какое-то устройство его не видело. Непроштампованная пометка не
	// собирается — сервер такие не хранит, но и не имеет права угадывать.
	expected := []string{"unseen", "unstamped", "alive"}
	if len(ids) != len(expected) {
		t.Fatalf("после обрезки осталось не то: %v", ids)
	}
	for index := range expected {
		if ids[index] != expected[index] {
			t.Fatalf("после обрезки осталось не то: %v", ids)
		}
	}
}

func TestNormalizeAcceptsAndTrims(t *testing.T) {
	longNote := make([]rune, maxNoteRunes+50)
	for index := range longNote {
		longNote[index] = 'ж'
	}
	clean, err := Normalize([]Item{{
		ID: "a", Chapter: 0, Start: 0, End: 2, Tone: tone(MaxTone),
		Quote: "to be", Note: string(longNote),
		Rev: 2, Writer: "phone", CreatedAt: 1, UpdatedAt: 2,
	}})
	if err != nil {
		t.Fatalf("годная отметка отвергнута: %v", err)
	}
	if got := len([]rune(clean[0].Note)); got != maxNoteRunes {
		t.Fatalf("заметка не обрезана: %d", got)
	}
}

func TestNormalizeRejectsBrokenItems(t *testing.T) {
	cases := map[string][]Item{
		"без номера":          {{Chapter: 0, Start: 0, End: 0, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"отрицательная глава": {{ID: "a", Chapter: -1, Start: 0, End: 0, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"конец раньше начала": {{ID: "a", Chapter: 0, Start: 5, End: 2, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"чужая краска":        {{ID: "a", Chapter: 0, Start: 0, End: 0, Tone: tone(MaxTone + 1), Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"без версии":          {{ID: "a", Chapter: 0, Start: 0, End: 0, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"без писателя":        {{ID: "a", Chapter: 0, Start: 0, End: 0, Rev: 1, CreatedAt: 1, UpdatedAt: 1}},
	}
	for name, items := range cases {
		if _, err := Normalize(items); err == nil {
			t.Errorf("%s: битая отметка прошла проверку", name)
		}
	}
	if _, err := Normalize(make([]Item, MaxItems+1)); err == nil {
		t.Error("слишком длинный список прошёл проверку")
	}
}

func TestNormalizeRejectsDuplicateIDs(t *testing.T) {
	items := []Item{
		{ID: "a", Chapter: 0, Start: 0, End: 1, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 2},
		{ID: "a", Chapter: 0, Start: 5, End: 6, Rev: 2, Writer: "w", CreatedAt: 1, UpdatedAt: 3},
	}
	if _, err := Normalize(items); err == nil {
		t.Fatal("повторный номер в одном списке прошёл проверку")
	}
}

func TestNormalizeRejectsUnfitIDs(t *testing.T) {
	long := make([]rune, maxID+1)
	for index := range long {
		long[index] = 'a'
	}
	cases := map[string]string{
		"пустой":          "",
		"из пробелов":     "     ",
		"слишком длинный": string(long),
	}
	for name, id := range cases {
		items := []Item{{ID: id, Chapter: 0, Start: 0, End: 1, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 2}}
		if _, err := Normalize(items); err == nil {
			t.Errorf("%s номер прошёл проверку", name)
		}
	}
}
