package discovery

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// Источник ленты — Project Gutenberg.
//
// Почему не Standard Ebooks. Их открытая лента — это «новые поступления»,
// несколько десятков книг, и она не растёт: полный каталог отдаётся только по
// выданным реквизитам. Лента, собранная из такого источника, у постоянного
// читателя заканчивается за неделю.
//
// Гутенберг — десятки тысяч английских книг с EPUB, все в общественном
// достоянии, никаких ключей и регистраций. Метаданные берём у Gutendex: у
// самого Гутенберга JSON-API нет, а разбирать его OPDS ради тех же полей
// дороже. Файлы при этом качаются прямо с gutenberg.org — Gutendex отдаёт
// только ссылки и в передаче книги не участвует.
type GutenbergSource struct {
	base   string
	http   *http.Client
	mu     sync.Mutex
	cached []Item
	until  time.Time
}

// gutenbergTopics — по одному запросу на жанр.
//
// Названия жанров русские не по недосмотру: их же выбирает читатель при
// настройке ленты, и `rank` сверяет жанр книги со списком в профиле обычным
// сравнением строк. Пока источник отдавал английские темы, это сравнение не
// совпадало никогда, и прибавка за любимый жанр не работала вовсе. Тема
// Gutendex остаётся здесь же, рядом, — она нужна только запросу.
var gutenbergTopics = []struct{ Genre, Topic string }{
	{"Приключения", "adventure"},
	{"Фантастика", "science fiction"},
	{"Детектив", "detective"},
	{"Роман", "romance"},
	{"История", "history"},
	{"Юмор", "humor"},
	{"Готика", "gothic"},
	{"Поэзия", "poetry"},
}

const gutendexDefaultURL = "https://gutendex.com/books/"

func NewGutenbergSource(baseURL string, timeout time.Duration) *GutenbergSource {
	base := strings.TrimSpace(baseURL)
	if base == "" {
		base = gutendexDefaultURL
	}
	return &GutenbergSource{base: base, http: catalogueHTTPClient(timeout)}
}

func (s *GutenbergSource) Items(ctx context.Context) ([]Item, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if time.Now().Before(s.until) && len(s.cached) > 0 {
		return append([]Item(nil), s.cached...), nil
	}
	items, err := s.fetch(ctx)
	if err != nil {
		// Устаревшая лента лучше пустого раздела при коротком сбое источника.
		if len(s.cached) > 0 {
			return append([]Item(nil), s.cached...), nil
		}
		return nil, err
	}
	s.cached = items
	s.until = time.Now().Add(30 * time.Minute)
	return append([]Item(nil), items...), nil
}

// fetch собирает по странице на каждый жанр.
//
// Темы запрашиваются разом, а не по очереди. Gutendex обычно отвечает за долю
// секунды, но случается, что один запрос висит до самого таймаута; по очереди
// такая тема задерживала бы и все следующие, и обновление ленты упиралось бы
// в сумму задержек вместо самой долгой из них.
//
// Книга, попавшая сразу в несколько тем, остаётся одной карточкой и забирает
// все свои жанры: «Дракула» — это и готика, и роман, и прятать одно за другим
// незачем.
//
// Сбой одной темы не отменяет ленту: семь жанров из восьми — это лента, а не
// ошибка. Пустым результат станет, только если не ответила ни одна.
func (s *GutenbergSource) fetch(ctx context.Context) ([]Item, error) {
	pages := make([]*gutendexPage, len(gutenbergTopics))
	errs := make([]error, len(gutenbergTopics))

	var wait sync.WaitGroup
	for at, topic := range gutenbergTopics {
		wait.Add(1)
		go func(at int, topic string) {
			defer wait.Done()
			page, err := s.fetchTopic(ctx, topic)
			if err != nil {
				errs[at] = err
				return
			}
			pages[at] = &page
		}(at, topic.Topic)
	}
	wait.Wait()

	// Слияние — по порядку тем, а не по порядку ответов: иначе одна и та же
	// лента выглядела бы каждый раз иначе из-за случайностей сети.
	items := make([]Item, 0, len(gutenbergTopics)*32)
	index := map[string]int{}
	var lastErr error
	answered := 0

	for at, topic := range gutenbergTopics {
		if pages[at] == nil {
			if errs[at] != nil {
				lastErr = errs[at]
			}
			continue
		}
		answered++
		for _, book := range pages[at].Results {
			item, ok := itemOfBook(book, topic.Genre)
			if !ok {
				continue
			}
			if seen, ok := index[item.ID]; ok {
				items[seen].Genres = cleanGenres(append(items[seen].Genres, topic.Genre))
				continue
			}
			index[item.ID] = len(items)
			items = append(items, item)
		}
	}

	if answered == 0 {
		if lastErr != nil {
			return nil, lastErr
		}
		return nil, errors.New("каталог Gutenberg не отвечает")
	}
	if len(items) == 0 {
		return nil, errors.New("каталог Gutenberg пуст")
	}
	return items, nil
}

func (s *GutenbergSource) fetchTopic(ctx context.Context, topic string) (gutendexPage, error) {
	address, err := s.address(topic)
	if err != nil {
		return gutendexPage{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return gutendexPage{}, fmt.Errorf("подготовка каталога: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", catalogueUserAgent)

	response, err := s.http.Do(req)
	if err != nil {
		return gutendexPage{}, fmt.Errorf("получение каталога: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return gutendexPage{}, fmt.Errorf("Gutendex ответил %d", response.StatusCode)
	}

	var page gutendexPage
	if err := json.NewDecoder(io.LimitReader(response.Body, 8<<20)).Decode(&page); err != nil {
		return gutendexPage{}, fmt.Errorf("разбор каталога: %w", err)
	}
	return page, nil
}

// address собирает запрос к каталогу.
//
// Запрос идёт с косой чертой на конце: без неё Gutendex отвечает 301, а лишнее
// перенаправление на каждый жанр — это восемь лишних обращений к чужому
// сервису при каждом обновлении кэша.
func (s *GutenbergSource) address(topic string) (string, error) {
	parsed, err := url.Parse(s.base)
	if err != nil || parsed.Host == "" {
		return "", errors.New("адрес каталога Gutendex не настроен")
	}
	// http допустим только на локальной петле — так же, как у адресов возврата
	// OAuth. Наружу каталог ходит по https, а подставной сервер в тестах и
	// зеркало на той же машине от этого требования освобождены.
	if parsed.Scheme != "https" && !(parsed.Scheme == "http" && isLoopbackHost(parsed.Hostname())) {
		return "", errors.New("адрес каталога Gutendex не настроен")
	}
	if !strings.HasSuffix(parsed.Path, "/") {
		parsed.Path += "/"
	}
	query := url.Values{
		"languages": {"en"},
		// Только то, что читалка откроет: без этого в ленту попадают записи,
		// у которых есть лишь скан в виде картинок.
		"mime_type": {"application/epub+zip"},
		"sort":      {"popular"},
		"topic":     {topic},
	}
	parsed.RawQuery = query.Encode()
	return parsed.String(), nil
}

type gutendexPage struct {
	Count   int            `json:"count"`
	Results []gutendexBook `json:"results"`
}

type gutendexBook struct {
	ID          int               `json:"id"`
	Title       string            `json:"title"`
	Authors     []gutendexPerson  `json:"authors"`
	Summaries   []string          `json:"summaries"`
	Subjects    []string          `json:"subjects"`
	Bookshelves []string          `json:"bookshelves"`
	Languages   []string          `json:"languages"`
	Copyright   *bool             `json:"copyright"`
	Formats     map[string]string `json:"formats"`
	Downloads   int               `json:"download_count"`
}

type gutendexPerson struct {
	Name string `json:"name"`
}

// itemOfBook переводит запись каталога в карточку ленты.
//
// Возвращает false для всего, что читателю показать нельзя: книга под правами,
// книга без EPUB, запись без названия.
func itemOfBook(book gutendexBook, genre string) (Item, bool) {
	title := strings.TrimSpace(book.Title)
	if book.ID <= 0 || title == "" {
		return Item{}, false
	}
	// nil — «сведений о правах нет», и это не то же самое, что «права есть».
	// Такие записи у Гутенберга встречаются у старых текстов; в общественном
	// достоянии они те же самые.
	if book.Copyright != nil && *book.Copyright {
		return Item{}, false
	}
	download := strings.TrimSpace(book.Formats["application/epub+zip"])
	if download == "" || trustedDownload(download) != nil {
		return Item{}, false
	}

	summary := summaryOf(book.Summaries)
	item := Item{
		ID:          stableID(fmt.Sprintf("gutenberg:%d", book.ID)),
		ContentType: "book",
		Title:       title,
		Author:      authorOf(book.Authors),
		Summary:     summary,
		Genres:      cleanGenres(append([]string{genre}, shelfGenres(book.Bookshelves)...)),
		Level:       estimatedLevel(summary),
		CoverURL:    strings.TrimSpace(book.Formats["image/jpeg"]),
		PageURL:     fmt.Sprintf("https://www.gutenberg.org/ebooks/%d", book.ID),
		DownloadURL: download,
	}
	return item, true
}

// authorOf разворачивает каталожную запись имени.
//
// Гутенберг хранит «Shelley, Mary Wollstonecraft» — так удобно сортировать
// полку, но не читать карточку. Переставляем обратно, и только когда запятая
// одна: «Dumas, Alexandre, fils» перестановкой испортится.
func authorOf(authors []gutendexPerson) string {
	if len(authors) == 0 {
		return ""
	}
	name := strings.TrimSpace(authors[0].Name)
	parts := strings.Split(name, ",")
	if len(parts) != 2 {
		return name
	}
	family := strings.TrimSpace(parts[0])
	given := strings.TrimSpace(parts[1])
	if family == "" || given == "" {
		return name
	}
	return given + " " + family
}

// summaryOf берёт первую аннотацию и убирает служебную приписку.
//
// Гутенберг помечает машинные пересказы словами «(This is an automatically
// generated summary.)». Читателю эта строка ничего не сообщает, а место в
// карточке занимает.
func summaryOf(summaries []string) string {
	if len(summaries) == 0 {
		return ""
	}
	summary := strings.TrimSpace(summaries[0])
	const note = "(This is an automatically generated summary.)"
	summary = strings.TrimSpace(strings.TrimSuffix(summary, note))
	return cleanHTML(summary)
}

// shelfGenres достаёт дополнительные жанры с полок Гутенберга.
//
// Полки приходят как «Category: Gothic Fiction» — приставку убираем, а сами
// названия оставляем английскими: в профиле их нет, на ранжирование они не
// влияют, но в карточке говорят читателю о книге больше, чем одна тема
// запроса.
func shelfGenres(shelves []string) []string {
	result := make([]string, 0, len(shelves))
	for _, shelf := range shelves {
		name := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(shelf), "Category:"))
		if name != "" {
			result = append(result, strings.TrimSpace(name))
		}
	}
	return result
}

func isLoopbackHost(host string) bool {
	host = strings.Trim(host, "[]")
	return host == "127.0.0.1" || host == "::1" || host == "localhost"
}
