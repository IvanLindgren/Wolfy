package discovery

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/wolfy/server/internal/store"
)

func книга() gutendexBook {
	нет := false
	return gutendexBook{
		ID:      84,
		Title:   "Frankenstein; Or, The Modern Prometheus",
		Authors: []gutendexPerson{{Name: "Shelley, Mary Wollstonecraft"}},
		Summaries: []string{
			"A young scientist creates a living creature. (This is an automatically generated summary.)",
		},
		Bookshelves: []string{"Category: British Literature", "Category: Novels"},
		Languages:   []string{"en"},
		Copyright:   &нет,
		Formats: map[string]string{
			"application/epub+zip": "https://www.gutenberg.org/ebooks/84.epub3.images",
			"image/jpeg":           "https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg",
			"text/html":            "https://www.gutenberg.org/ebooks/84.html.images",
		},
		Downloads: 58824,
	}
}

func TestЗаписьКаталогаСтановитсяКарточкой(t *testing.T) {
	item, ok := itemOfBook(книга(), "Готика")
	if !ok {
		t.Fatal("карточка не собралась")
	}
	if item.ContentType != "book" || item.ID == "" {
		t.Fatalf("не книга: %+v", item)
	}
	// Каталожное «Shelley, Mary Wollstonecraft» читателю показывать нельзя.
	if item.Author != "Mary Wollstonecraft Shelley" {
		t.Fatalf("имя автора не развёрнуто: %q", item.Author)
	}
	// Служебная приписка Гутенберга в карточке ничего не сообщает.
	if strings.Contains(item.Summary, "automatically generated") {
		t.Fatalf("служебная приписка осталась: %q", item.Summary)
	}
	if item.DownloadURL == "" || item.CoverURL == "" {
		t.Fatalf("нет файла или обложки: %+v", item)
	}
	if item.PageURL != "https://www.gutenberg.org/ebooks/84" {
		t.Fatalf("страница книги не та: %q", item.PageURL)
	}
	if item.Genres[0] != "Готика" {
		t.Fatalf("жанр запроса не первый: %+v", item.Genres)
	}
}

// Жанр в карточке русский тем же словом, что и в профиле, — иначе прибавка за
// любимый жанр в `rank` не срабатывает вовсе. Пока источник отдавал английские
// темы, это и происходило: настройка ленты ни на что не влияла.
func TestРусскийЖанрПоднимаетКнигуВЛенте(t *testing.T) {
	готика, ok := itemOfBook(книга(), "Готика")
	if !ok {
		t.Fatal("карточка не собралась")
	}
	другая := книга()
	другая.ID = 1342
	другая.Title = "Pride and Prejudice"
	другая.Formats["application/epub+zip"] = "https://www.gutenberg.org/ebooks/1342.epub3.images"
	роман, ok := itemOfBook(другая, "Роман")
	if !ok {
		t.Fatal("вторая карточка не собралась")
	}

	ranked := rank([]Item{роман, готика}, store.DiscoveryProfile{
		EnglishLevel: "B2", Genres: []string{"Готика"}, OnboardingComplete: true,
	}, nil, "reader")
	if ranked[0].ID != готика.ID {
		t.Fatalf("книга выбранного жанра не поднялась: %+v", ranked)
	}
}

func TestКнигаБезEpubИПодПравамиНеПопадаетВЛенту(t *testing.T) {
	безФайла := книга()
	delete(безФайла.Formats, "application/epub+zip")
	if _, ok := itemOfBook(безФайла, "Готика"); ok {
		t.Fatal("книга без EPUB попала в ленту — читалке нечего открыть")
	}

	да := true
	подПравами := книга()
	подПравами.Copyright = &да
	if _, ok := itemOfBook(подПравами, "Готика"); ok {
		t.Fatal("книга под правами попала в ленту")
	}
}

// Адрес файла приходит из чужого каталога. Без проверки хоста ответ Gutendex
// заставил бы наш сервер сходить куда угодно и сохранить это как книгу.
func TestЧужойАдресЗагрузкиОтбрасывается(t *testing.T) {
	for _, адрес := range []string{
		"https://gutenberg.org.evil.example/84.epub",
		"http://www.gutenberg.org/ebooks/84.epub3.images",
		"https://127.0.0.1/84.epub",
		"https://metadata.google.internal/84.epub",
		"https://gutendex.com/84.epub",
	} {
		подмена := книга()
		подмена.Formats["application/epub+zip"] = адрес
		if _, ok := itemOfBook(подмена, "Готика"); ok {
			t.Fatalf("принят чужой адрес загрузки: %s", адрес)
		}
	}
	if err := trustedDownload("https://www.gutenberg.org/cache/epub/84/pg84-images-3.epub"); err != nil {
		t.Fatalf("свой адрес отвергнут: %v", err)
	}
}

// Каталог и загрузка — разные списки хостов: метаданные отдаёт Gutendex, файлы
// Гутенберг, и разрешать каждому чужое незачем.
func TestКаталогИЗагрузкаРазведены(t *testing.T) {
	if trustedCatalogue("https://gutendex.com/books/") != nil {
		t.Fatal("свой каталог отвергнут")
	}
	if trustedCatalogue("https://www.gutenberg.org/ebooks/84.epub3.images") == nil {
		t.Fatal("адрес загрузки принят как каталог")
	}
	if trustedDownload("https://gutendex.com/books/") == nil {
		t.Fatal("адрес каталога принят как загрузка")
	}
}

func TestЗапросКаталогаСобранПравильно(t *testing.T) {
	source := NewGutenbergSource("", time.Second)
	address, err := source.address("gothic")
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(address)
	if err != nil {
		t.Fatal(err)
	}
	// Без косой черты на конце Gutendex отвечает 301 на каждый жанр.
	if !strings.HasSuffix(parsed.Path, "/") {
		t.Fatalf("нет косой черты на конце: %q", address)
	}
	query := parsed.Query()
	if query.Get("languages") != "en" || query.Get("topic") != "gothic" {
		t.Fatalf("неверный запрос: %q", address)
	}
	// Без mime_type в ленту попадают записи, у которых есть только скан.
	if query.Get("mime_type") != "application/epub+zip" {
		t.Fatalf("не запрошен EPUB: %q", address)
	}
	if query.Get("sort") != "popular" {
		t.Fatalf("не запрошены популярные: %q", address)
	}
}

func TestАдресКаталогаНеHttpsОтвергается(t *testing.T) {
	source := NewGutenbergSource("http://gutendex.com/books/", time.Second)
	if _, err := source.address("gothic"); err == nil {
		t.Fatal("принят http-адрес каталога")
	}
}

// Сбой одной темы не должен отменять ленту: семь жанров из восьми — это лента.
func TestОтказОднойТемыНеОтменяетЛенту(t *testing.T) {
	var сломана string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("topic") == сломана {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(gutendexPage{Count: 1, Results: []gutendexBook{книга()}})
	}))
	defer server.Close()

	source := &GutenbergSource{base: server.URL + "/books/", http: server.Client()}
	сломана = "gothic"

	items, err := source.Items(context.Background())
	if err != nil {
		t.Fatalf("лента не собралась: %v", err)
	}
	// Одна и та же книга во всех темах — карточка обязана остаться одна,
	// собрав жанры всех тем, где встретилась.
	if len(items) != 1 {
		t.Fatalf("книга размножилась по темам: %d", len(items))
	}
	if len(items[0].Genres) < 2 {
		t.Fatalf("жанры тем не собрались: %+v", items[0].Genres)
	}
	for _, genre := range items[0].Genres {
		if genre == "Готика" {
			t.Fatal("жанр сломанной темы попал в карточку")
		}
	}
}

func TestПолныйОтказКаталогаЭтоОшибка(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer server.Close()

	source := &GutenbergSource{base: server.URL + "/books/", http: server.Client()}
	if _, err := source.Items(context.Background()); err == nil {
		t.Fatal("пустой каталог выдан за ленту")
	}
}

// Устаревшая лента лучше пустого раздела: источник чужой и падает не по нашей
// вине, а читатель за это платить не должен.
func TestПриСбоеОтдаётсяПрежняяЛента(t *testing.T) {
	упал := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if упал {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(gutendexPage{Count: 1, Results: []gutendexBook{книга()}})
	}))
	defer server.Close()

	source := &GutenbergSource{base: server.URL + "/books/", http: server.Client()}
	first, err := source.Items(context.Background())
	if err != nil || len(first) == 0 {
		t.Fatalf("первая лента не собралась: %v", err)
	}

	упал = true
	source.until = time.Time{} // срок кэша вышел
	second, err := source.Items(context.Background())
	if err != nil {
		t.Fatalf("при сбое источника лента пропала: %v", err)
	}
	if len(second) != len(first) {
		t.Fatalf("отдана не прежняя лента: %d вместо %d", len(second), len(first))
	}
}
