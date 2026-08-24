package annotations

import "testing"

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

func TestPruneTombstonesDropsOnlyConfirmed(t *testing.T) {
	items := []Item{
		{ID: "seen", Chapter: 0, Start: 0, End: 1, Deleted: true, Rev: 2, Writer: "phone"},
		{ID: "unseen", Chapter: 0, Start: 2, End: 3, Deleted: true, Rev: 4, Writer: "phone"},
		{ID: "alive", Chapter: 0, Start: 4, End: 5, Rev: 3, Writer: "phone"},
	}

	pruned := PruneTombstones(items, 3)
	ids := make([]string, 0, len(pruned))
	for _, item := range pruned {
		ids = append(ids, item.ID)
	}
	// Пометка версии 2 подтверждена (порог 3) и стёрта; пометка версии 4 —
	// ещё нет: какое-то устройство её не видело.
	expected := []string{"unseen", "alive"}
	if len(ids) != len(expected) {
		t.Fatalf("после обрезки осталось не то: %v", ids)
	}
	for index := range expected {
		if ids[index] != expected[index] {
			t.Fatalf("после обрезки осталось не то: %v", ids)
		}
	}
}

func TestTopSeenReportsHighestVersion(t *testing.T) {
	items := []Item{
		{ID: "a", Chapter: 0, Start: 0, End: 1, Rev: 3, Writer: "w"},
		{ID: "b", Chapter: 0, Start: 2, End: 3, Rev: 7, Writer: "w"},
	}
	if got := TopSeen(items); got != 7 {
		t.Fatalf("подтверждение должно быть 7, а не %d", got)
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
		"без номера":         {{Chapter: 0, Start: 0, End: 0, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"отрицательная глава": {{ID: "a", Chapter: -1, Start: 0, End: 0, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"конец раньше начала": {{ID: "a", Chapter: 0, Start: 5, End: 2, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"чужая краска":       {{ID: "a", Chapter: 0, Start: 0, End: 0, Tone: tone(MaxTone + 1), Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"без версии":         {{ID: "a", Chapter: 0, Start: 0, End: 0, Writer: "w", CreatedAt: 1, UpdatedAt: 1}},
		"без писателя":       {{ID: "a", Chapter: 0, Start: 0, End: 0, Rev: 1, CreatedAt: 1, UpdatedAt: 1}},
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
		"пустой":         "",
		"из пробелов":    "     ",
		"слишком длинный": string(long),
	}
	for name, id := range cases {
		items := []Item{{ID: id, Chapter: 0, Start: 0, End: 1, Rev: 1, Writer: "w", CreatedAt: 1, UpdatedAt: 2}}
		if _, err := Normalize(items); err == nil {
			t.Errorf("%s номер прошёл проверку", name)
		}
	}
}
