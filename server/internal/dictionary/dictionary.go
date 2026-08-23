// Package dictionary раздаёт ту же базу толкований, которую Rust читает
// локально. Сервер нужен как fallback до загрузки файла на устройство.
package dictionary

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type Sense struct {
	POS        string `json:"pos"`
	Definition string `json:"definition"`
}

type Entry struct {
	Word          string   `json:"word"`
	Pronunciation string   `json:"pronunciation"`
	Translations  []string `json:"translations"`
	Senses        []Sense  `json:"senses"`
}

// Service неизменяем после открытия, поэтому параллельные чтения безопасны.
type Service struct {
	entries     map[string]Entry
	archivePath string
}

// Open загружает индекс статей и проверяет архив для клиентов.
func Open(path string) (*Service, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("открыть словарь: %w", err)
	}
	defer file.Close()

	entries := make(map[string]Entry, 80_000)
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64<<10), 1<<20)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Split(line, "\t")
		if len(fields) < 3 {
			continue
		}
		entry := Entry{
			Word:          fields[0],
			Pronunciation: fields[1],
		}
		for _, raw := range fields[2:] {
			parts := strings.SplitN(raw, "|", 2)
			if len(parts) != 2 || strings.TrimSpace(parts[1]) == "" {
				continue
			}
			if parts[0] == "t" {
				entry.Translations = append(entry.Translations, parts[1])
				continue
			}
			entry.Senses = append(entry.Senses, Sense{
				POS:        posName(parts[0]),
				Definition: parts[1],
			})
		}
		if len(entry.Senses) > 0 {
			entries[entry.Word] = entry
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("прочитать словарь: %w", err)
	}
	if len(entries) == 0 {
		return nil, fmt.Errorf("словарь не содержит статей")
	}

	archive := path + ".gz"
	if info, err := os.Stat(archive); err != nil || !info.Mode().IsRegular() {
		return nil, fmt.Errorf("архив словаря %s недоступен", archive)
	}
	return &Service{entries: entries, archivePath: filepath.Clean(archive)}, nil
}

// Unavailable оставляет сервер рабочим, если словарь не развёрнут.
func Unavailable() *Service { return &Service{} }

func (s *Service) Configured() bool {
	return s != nil && len(s.entries) > 0 && s.archivePath != ""
}

func (s *Service) Define(word string) (Entry, bool) {
	if !s.Configured() {
		return Entry{}, false
	}
	entry, ok := s.entries[strings.ToLower(strings.TrimSpace(word))]
	return entry, ok
}

func (s *Service) OpenArchive() (*os.File, error) {
	if !s.Configured() {
		return nil, fmt.Errorf("словарь не настроен")
	}
	return os.Open(s.archivePath)
}

func posName(code string) string {
	switch code {
	case "n":
		return "NOUN"
	case "v":
		return "VERB"
	case "a", "s":
		return "ADJ"
	case "r":
		return "ADV"
	default:
		return ""
	}
}
