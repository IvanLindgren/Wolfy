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
	// MaxItems — потолок состояния книги: и того, что приходит в одном
	// запросе, и того, что хранится после слияния.
	//
	// Одно число на оба конца намеренно: протокол обмена — «отправь весь
	// список», поэтому всё, что сервер способен выдать, клиент обязан быть
	// способным отправить обратно. Разные числа на входе и выходе однажды
	// выдали бы список, который клиент физически не может вернуть, и
	// синхронизация умерла бы навсегда с ошибкой «слишком много».
	//
	// Пятьсот отметок на одну книгу — это больше, чем ставит самый дотошный
	// читатель, и меньше, чем нужно, чтобы испорченная вкладка забила базу.
	// Истинный потолок байтов задаёт тело запроса (4 МиБ на маршруте):
	// MaxItems — контракт состояния, а не защита от объёма.
	MaxItems = 500

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

	// Generation — поколение серверного снимка, в котором запись была
	// записана последний раз. Ставит только сервер: устройство шлёт записи с
	// нулём и получает назад уже проштампованные. По поколениям, а не по
	// версиям правок, работает сборщик мусора пометок удалений.
	Generation int64 `json:"generation,omitempty"`

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
		if item.Generation < 0 {
			return nil, fmt.Errorf("%w: %s", ErrInvalid, item.ID)
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
// большее содержимое по полному порядку [compare]. Правило детерминировано и
// не зависит от порядка сторон: иначе два устройства после синхронизации
// увидят разные списки, и следующая синхронизация начнёт бессмысленно их
// переписывать.
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
// Единственный звучащий критерий — наблюдение, а не возраст. Пометка с
// поколением G перестаёт быть нужной, когда каждое устройство,
// зарегистрированное для этой книги, подтвердило, что долговечно сохранило
// снимок поколения не ниже G. Порог — минимум по реестру подтверждений;
// считается вызывающим слоем, здесь только отсев.
//
// Поколение здесь надёжнее версии правки: поколения выдаёт один сервер и
// растут они монотонно для всех записей сразу, поэтому «устройство видело
// снимок G» значит ровно то, что сказано, — не больше и не меньше.
func PruneTombstones(items []Item, confirmedGeneration int64) []Item {
	kept := make([]Item, 0, len(items))
	for _, item := range items {
		// Запись поколения 0 сервер никогда не хранит; пропускать её из
		// сборки — просто лишняя защита от старых строк без поколений.
		if item.Deleted && item.Generation > 0 && item.Generation <= confirmedGeneration {
			continue
		}
		kept = append(kept, item)
	}
	return kept
}

// later отвечает, кого из двух кандидатов оставить в слиянии.
func later(candidate, current Item) bool {
	if candidate.Rev != current.Rev {
		return candidate.Rev > current.Rev
	}
	if candidate.Writer != current.Writer {
		return candidate.Writer > current.Writer
	}
	return compare(candidate, current) > 0
}

// compare — полный порядок по содержимому записи.
//
// Покрывает все поля, от которых запись может отличаться: два кандидата с
// равными (Rev, Writer) обязаны сравниться одинаково на обеих сторонах.
// Формат намеренно совпадает с веб-клиентом — он сравнивает те же поля в том
// же порядке. Строки сравниваются байтами UTF-8 в Go и кодами UTF-16 в JS;
// оба порядка совпадают с порядком кодовых точек.
//
// Порядок полей: место, краска, тексты, время, пометка удаления, поколение.
// Поколение — последний тай-брейк: при полностью одинаковом содержимом
// выигрывает запись из более свежего снимка.
func compare(a, b Item) int {
	if d := cmp(int64(a.Chapter), int64(b.Chapter)); d != 0 {
		return d
	}
	if d := cmp(int64(a.Start), int64(b.Start)); d != 0 {
		return d
	}
	if d := cmp(int64(a.End), int64(b.End)); d != 0 {
		return d
	}
	if d := cmp(toneOf(a), toneOf(b)); d != 0 {
		return d
	}
	if d := strings.Compare(a.Quote, b.Quote); d != 0 {
		return d
	}
	if d := strings.Compare(a.Note, b.Note); d != 0 {
		return d
	}
	if d := cmp(a.CreatedAt, b.CreatedAt); d != 0 {
		return d
	}
	if d := cmp(a.UpdatedAt, b.UpdatedAt); d != 0 {
		return d
	}
	if d := cmp(boolOf(a.Deleted), boolOf(b.Deleted)); d != 0 {
		return d
	}
	return cmp(a.Generation, b.Generation)
}

func toneOf(item Item) int64 {
	if item.Tone == nil {
		return -1
	}
	return int64(*item.Tone)
}

func boolOf(value bool) int64 {
	if value {
		return 1
	}
	return 0
}

func cmp(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

// clip обрезает строку до предела по рунам.
func clip(value string, limit int) string {
	runes := []rune(value)
	if len(runes) > limit {
		runes = runes[:limit]
	}
	return string(runes)
}
