package openlibrary

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSearchKeepsOnlyPublicBooks(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/search.json") {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("q"); got != "sherlock holmes" {
			t.Errorf("запрос дошёл искажённым: %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"docs":[
			{"key":"/works/OL267218W","title":"The Adventures of Sherlock Holmes",
			 "author_name":["Arthur Conan Doyle"],"first_publish_year":1892,
			 "ebook_access":"public","ia":["adventuresofsher00doyl"]},
			{"key":"/works/OL1W","title":"Заимствуемая книга",
			 "author_name":["Кто-то"],"first_publish_year":2000,
			 "ebook_access":"borrowable","ia":["borrowed-book"]},
			{"key":"/works/OL2W","title":"Без скана",
			 "author_name":["Кто-то"],"first_publish_year":2000,
			 "ebook_access":"public","ia":[]}
		]}`))
	}))
	defer server.Close()

	service := withBase(server.URL, 5*time.Second)
	books, err := service.Search(context.Background(), "sherlock holmes", 20)
	if err != nil {
		t.Fatalf("поиск не удался: %v", err)
	}
	if len(books) != 1 {
		t.Fatalf("ожидали одну публичную книгу, получили %d", len(books))
	}

	book := books[0]
	if book.ID != "OL267218W" {
		t.Errorf("номер работы: %q", book.ID)
	}
	if book.Author != "Arthur Conan Doyle" {
		t.Errorf("автор: %q", book.Author)
	}
	if book.Year != 1892 {
		t.Errorf("год: %d", book.Year)
	}
	if len(book.URLs) != 2 ||
		book.URLs[0] != "https://archive.org/download/adventuresofsher00doyl/adventuresofsher00doyl.epub" ||
		book.URLs[1] != "https://archive.org/download/adventuresofsher00doyl/adventuresofsher00doyl_djvu.txt" {
		t.Errorf("ссылки на скачивание: %v", book.URLs)
	}
}

func TestSearchReportsUnavailableCatalogue(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer server.Close()

	service := withBase(server.URL, 5*time.Second)
	if _, err := service.Search(context.Background(), "hobbit", 20); err == nil {
		t.Fatal("ожидали ошибку недоступного каталога")
	} else if !strings.Contains(err.Error(), "недоступна") {
		t.Fatalf("сообщение без человеческого текста: %v", err)
	}
}

func TestSearchRefusesEmptyQuery(t *testing.T) {
	service := withBase("https://openlibrary.org", time.Second)
	if _, err := service.Search(context.Background(), "   ", 20); err == nil {
		t.Fatal("пустой запрос должен быть отклонён до похода в каталог")
	}
}
