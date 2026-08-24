package translate

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

func TestКонтекстДоходитДоDeepLНоНеПодменяетТекст(t *testing.T) {
	var received url.Values
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Errorf("форма не разобрана: %v", err)
		}
		received = r.PostForm
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"translations":[{"text":"забронировать"}]}`))
	}))
	defer provider.Close()

	service := New(nil, "test-key", provider.URL, time.Second)
	result, err := service.fromDeepL(
		context.Background(),
		"book",
		"I will book a room.",
		"EN",
		"RU",
	)
	if err != nil {
		t.Fatal(err)
	}
	if result != "забронировать" {
		t.Fatalf("неожиданный перевод: %q", result)
	}
	if received.Get("text") != "book" {
		t.Errorf("в перевод ушёл %q вместо одного слова", received.Get("text"))
	}
	if received.Get("context") != "I will book a room." {
		t.Errorf("контекст потерян: %q", received.Get("context"))
	}
}

func TestКонтекстРазделяетКлючиКэша(t *testing.T) {
	book := string(cacheKey("book", "I read a book.", "EN", "RU"))
	reserve := string(cacheKey("book", "I will book a room.", "EN", "RU"))
	if book == reserve {
		t.Fatal("два смысла слова получили один ключ кэша")
	}
}
