// Package annotations — заметки и выделения к книгам.
//
// Одна сущность на две вещи, потому что это и есть одна вещь: кусок книги,
// который читатель чем-то отметил. Выделение — отметка цветом, заметка —
// отметка словами.
//
// Здесь только чистые правила: что считается годной заметкой и как сливать
// два списка, приехавшие с разных устройств. База и HTTP живут в других
// слоях — по той же причине, по которой они живут там всюду.
package annotations

import (
	"errors"
	"fmt"
	"sort"
	"strings"
)

const (
	// MaxItems — сколько отметок одна книга может принести в одном запросе.
	// Две тысячи — это больше, чем ставит самый дотошный читатель, и меньше,
	// чем нужно, чтобы одна испорченная вкладка забила базу гигабайтами.
	MaxItems = 2000

	// MaxStored — потолок слитого списка, включая пометки удалений. Он выше
	// удвоенного MaxItems: объединение двух честных устройств обязано
	// влезать без потерь. Превышение означает не «читатель много отмечал»,
	// а «кто-то шлёт безостановочно» — и ответом на это служит отказ, а не
	// выборочная потеря записей.
	MaxStored = 5000

	maxID         = 64
	maxWriter     = 64
	maxSpanTokens = 4000
	maxQuoteRunes = 4000
	maxNoteRunes  = 8000
	MaxTone       = 10
)

var (
	ErrTooMany = errors.New("заметок слишком много для одной книги")
	ErrInvalid = errors.New("заметка не разобрана")
)

// Item — одна отметка: выделение маркером, заметка или то и другое разом.
//
// Место хранится номерами токенов главы, а не страницей — страница меняется
// вместе с кеглем, токен нет.
//
// Версией записи служит пара (Rev, Writer), а не время правки: часы
// устройств врут, а (Rev, Writer) — полный детерминированный порядок,
// который не зависит ни от стен, ни от порядка прихода списков.
type Item struct {
	ID      string `json:"id"`
	Chapter int    `json:"chapter"`
	// Полуинтервал номеров токенов. У заметки к месту end == start.
	Start int    `json:"start"`
	End   int    `json:"end"`
	Tone  *int   `json:"tone"`
	Quote string `json:"quote"`
	Note  string `json:"note"`

	// Rev — номер правки в счётчике Лампорта писателя, строго
	// возрастающий на устройстве. Writer — стабильный номер устройства,
	// которым подписана правка.
	Rev    int64  `json:"rev"`
	Writer string `json:"writer"`

	CreatedAt int64 `json:"createdAt"`
	UpdatedAt int64 `json:"updatedAt"`

	// Удаление помечается, а не стирается: иначе оно не доедет до второго
	// устройства, и заметка там воскреснет.
	Deleted bool `json:"deleted,omitempty"`
}

// Normalize проверяет список и приводит его к хранимому виду.
//
// Номера записи и писателя проверяются строго: это имена, а не содержимое, и
// два разных имени не имеют права схлопнуться в одно. Тексты при этом
// обрезаются, а не отвергаются — цитата в восемь тысяч знаков это ошибка
// клиента, а не причина отказать читателю во всей выгрузке.
func Normalize(items []Item) ([]Item, error) {
	if len(items) > MaxItems {
		return nil, ErrTooMany
	}
	seen := make(map[string]struct{}, len(items))
	clean := make([]Item, 0, len(items))

	for _, item := range items {
		item.ID = strings.TrimSpace(item.ID)
		if item.ID == "" || len([]rune(item.ID)) > maxID {
			return nil, fmt.Errorf("%w: отметка без годного номера", ErrInvalid)
		}
		// Два одинаковых номера в одном списке — нарушение инварианта уже на
		// устройстве; слияние с таким списком зависит от порядка сторон.
		if _, dup := seen[item.ID]; dup {
			return nil, fmt.Errorf("%w: повторный номер %s", ErrInvalid, item.ID)
		}
		seen[item.ID] = struct{}{}

		item.Writer = strings.TrimSpace(item.Writer)
		if item.Writer == "" || len([]rune(item.Writer)) > maxWriter {
			return nil, fmt.Errorf("%w: %s — без писателя", ErrInvalid, item.ID)
		}
		if item.Rev <= 0 {
			return nil, fmt.Errorf("%w: %s — без версии", ErrInvalid, item.ID)
		}
		if item.Chapter < 0 || item.Start < 0 || item.End < item.Start {
			return nil, fmt.Errorf("%w: %s", ErrInvalid, item.ID)
		}
		if item.Tone != nil && (*item.Tone < 1 || *item.Tone > MaxTone) {
			return nil, fmt.Errorf("%w: %s", ErrInvalid, item.ID)
		}
		if item.End-item.Start > maxSpanTokens {
			return nil, fmt.Errorf("%w: %s", ErrInvalid, item.ID)
		}
		item.Quote = clip(strings.TrimSpace(item.Quote), maxQuoteRunes)
		item.Note = clip(strings.TrimSpace(item.Note), maxNoteRunes)
		clean = append(clean, item)
	}
	return clean, nil
}

// Merge сливает два списка одной книги.
//
// Победитель конфликта — большая пара (Rev, Writer); при полном равенстве —
// больший слепок содержимого. Правило детерминировано и не зависит от
// порядка сторон: иначе два устройства после синхронизации увидят разные
// списки, и следующая синхронизация начнёт бессмысленно их переписывать.
//
// Свойство Лампорта при этом сохраняется: устройство, увидевшее правку
// версии r, поднимает свой счётчик выше r, и любая его следующая правка
// побеждает всё, что оно видело. Воскресить удаление, которое устройство
// наблюдало, оно не может — только правку, которой не видело.
//
// Результат отсортирован по месту в книге: сервер хранит список целиком,
// и порядок в нём обязан быть устойчивым.
func Merge(base []Item, incoming []Item) []Item {
	position := make(map[string]int, len(base)+len(incoming))
	merged := make([]Item, 0, len(base)+len(incoming))

	for _, item := range base {
		if _, seen := position[item.ID]; seen {
			continue
		}
		position[item.ID] = len(merged)
		merged = append(merged, item)
	}
	for _, item := range incoming {
		at, seen := position[item.ID]
		if !seen {
			position[item.ID] = len(merged)
			merged = append(merged, item)
			continue
		}
		if later(item, merged[at]) {
			merged[at] = item
		}
	}

	sort.SliceStable(merged, func(left, right int) bool {
		a, b := merged[left], merged[right]
		if a.Chapter != b.Chapter {
			return a.Chapter < b.Chapter
		}
		if a.Start != b.Start {
			return a.Start < b.Start
		}
		return a.ID < b.ID
	})
	return merged
}

// PruneTombstones выбрасывает пометки удалений, подтверждённые всеми.
//
// Единственный звучащий критерий — наблюдение, а не возраст. Пометка
// перестаёт быть нужной, когда каждое устройство, зарегистрированное для
// этой книги, подтвердило, что долговечно сохранило состояние с версией не
// ниже пометки. Порог — минимум по реестру seen; считается вызывающим
// слоем, здесь только отсев.
func PruneTombstones(items []Item, confirmedRev int64) []Item {
	kept := make([]Item, 0, len(items))
	for _, item := range items {
		if item.Deleted && item.Rev <= confirmedRev {
			continue
		}
		kept = append(kept, item)
	}
	return kept
}

// TopSeen — наибольшая версия списка, которую видевшее его устройство
// обязано отрапортовать обратно серверу как своё подтверждение.
func TopSeen(items []Item) int64 {
	var top int64
	for _, item := range items {
		if item.Rev > top {
			top = item.Rev
		}
	}
	return top
}

// later отвечает, кого из двух кандидатов оставить в слиянии.
func later(candidate, current Item) bool {
	if candidate.Rev != current.Rev {
		return candidate.Rev > current.Rev
	}
	if candidate.Writer != current.Writer {
		// Сравнение строк в Go идёт по байтам UTF-8, в JS — по кодам UTF-16;
		// оба порядка совпадают с порядком кодовых точек, поэтому одно и то
		// же сравнение решается одинаково на обеих сторонах.
		return candidate.Writer > current.Writer
	}
	return key(candidate) > key(current)
}

// key — сравнимый слепок содержимого записи.
//
// Формат намеренно совпадает с тем, что строит веб-клиент: у обоих сторон
// равные (Rev, Writer) обязаны выбрать одного и того же победителя.
func key(item Item) string {
	tone := int64(-1)
	if item.Tone != nil {
		tone = int64(*item.Tone)
	}
	return fmt.Sprintf("%d|%s|%s|%d|%t", tone, item.Quote, item.Note, item.CreatedAt, item.Deleted)
}

// clip обрезает строку до предела по рунам.
func clip(value string, limit int) string {
	runes := []rune(value)
	if len(runes) > limit {
		runes = runes[:limit]
	}
	return string(runes)
}
