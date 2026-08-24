// Package openlibrary ищет книги в Открытой библиотеке и называет адрес
// скачивания.
//
// Открытая библиотека — свободный каталог с открытым API, ключей ей не нужно,
// но клиент всё равно не ходит в неё сам: поиск идёт через сервер, чтобы у
// приложения был один канал наружу, один ограничитель частоты и одна точка,
// где ответ каталога превращается в понятные клиенту поля.
package openlibrary

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	defaultBase = "https://openlibrary.org"
	// Архив.орг хранит отсканированные издания; у большинства публичных книг
	// есть производный EPUB, у остальных — послойный текст.
	archiveBase = "https://archive.org/download"
)

var ErrUnavailable = fmt.Errorf("Открытая библиотека сейчас недоступна")

// Book — находка поиска: что за книга и откуда её качать.
type Book struct {
	// Номер работы в каталоге вида «OL267218W».
	ID     string
	Title  string
	Author string
	Year   int
	// Ссылки на скачивание по убыванию предпочтительности:
	// сначала EPUB из архива, затем простой текст.
	URLs []string
}

type Service struct {
	client *http.Client
	base   string
}

// New создаёт сервис поиска с таймаутом на весь запрос: каталог отвечает
// быстро, и висящее соединение читателю ничего не даёт.
func New(timeout time.Duration) *Service {
	return &Service{
		client: &http.Client{Timeout: timeout},
		base:   defaultBase,
	}
}

// для тестов: base подменяется на сервер httptest.
func withBase(base string, timeout time.Duration) *Service {
	return &Service{client: &http.Client{Timeout: timeout}, base: base}
}

type searchResponse struct {
	Docs []doc `json:"docs"`
}

type doc struct {
	Key    string   `json:"key"`
	Title  string   `json:"title"`
	Author []string `json:"author_name"`
	Year   int      `json:"first_publish_year"`
	Access string   `json:"ebook_access"`
	IA     []string `json:"ia"`
}

// Search ищет книги по строке запроса.
//
// Отбираются только книги со статусом «public»: заимствуемые издания Открытой
// библиотеки отдают файл с шифрованием на срок займа, и такой файл Wolfy
// открыть не сможет, а предложить то, что не откроется, значит обмануть.
func (s *Service) Search(ctx context.Context, query string, limit int) ([]Book, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return nil, fmt.Errorf("пустой поисковый запрос")
	}
	if limit <= 0 {
		limit = 20
	}
	if limit > 50 {
		limit = 50
	}

	address := s.base + "/search.json?" + url.Values{
		"q":      {query},
		"limit":  {fmt.Sprint(limit)},
		"fields": {"key,title,author_name,first_publish_year,ebook_access,ia"},
	}.Encode()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return nil, ErrUnavailable
	}
	request.Header.Set("User-Agent", "Wolfy/1.0 (book catalogue)")

	response, err := s.client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("%w: каталог ответил %d", ErrUnavailable, response.StatusCode)
	}
	if response.Body == nil {
		return nil, ErrUnavailable
	}

	var parsed searchResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&parsed); err != nil {
		return nil, fmt.Errorf("%w: неожиданный ответ каталога", ErrUnavailable)
	}

	return convert(parsed.Docs), nil
}

func convert(docs []doc) []Book {
	books := make([]Book, 0, len(docs))
	for _, entry := range docs {
		if entry.Title == "" || len(entry.IA) == 0 || entry.Access != "public" {
			continue
		}
		identifier := entry.IA[0]
		if identifier == "" {
			continue
		}
		book := Book{
			ID:    strings.TrimPrefix(entry.Key, "/works/"),
			Title: entry.Title,
			Year:  entry.Year,
			URLs: []string{
				archiveBase + "/" + identifier + "/" + identifier + ".epub",
				archiveBase + "/" + identifier + "/" + identifier + "_djvu.txt",
			},
		}
		for _, name := range entry.Author {
			book.Author = strings.TrimSpace(name)
			break
		}
		books = append(books, book)
	}
	return books
}
