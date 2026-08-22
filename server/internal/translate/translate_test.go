package translate_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/wolfy/server/internal/store"
	"github.com/wolfy/server/internal/translate"
)

func openStore(t *testing.T) *store.Store {
	t.Helper()
	url := os.Getenv("WOLFY_TEST_DB_URL")
	if url == "" {
		url = os.Getenv("WOLFY_DB_URL")
	}
	if url == "" {
		t.Skip("нет WOLFY_TEST_DB_URL — пропускаем тесты с базой (docker compose up -d)")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	s, err := store.Open(ctx, url)
	if err != nil {
		t.Fatalf("база не открылась: %v", err)
	}
	t.Cleanup(s.Close)
	return s
}

// фейковыйDeepL считает обращения и отвечает так же, как настоящий.
func фейковыйDeepL(t *testing.T, answer string, status int) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var calls atomic.Int32

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if status != http.StatusOK {
			w.WriteHeader(status)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"translations":[{"text":"` + answer +
			`","detected_source_language":"EN"}]}`))
	}))
	t.Cleanup(server.Close)
	return server, &calls
}

// уникальныйТекст нужен, чтобы тесты не подхватывали кэш друг друга.
func уникальныйТекст() string {
	return "The library smelled of dust " + time.Now().Format("150405.000000000")
}

func TestПереводВозвращаетсяИКэшируется(t *testing.T) {
	s := openStore(t)
	deepl, calls := фейковыйDeepL(t, "Библиотека пахла пылью", http.StatusOK)

	service := translate.New(s.Pool, "test-key", deepl.URL, 5*time.Second)
	text := уникальныйТекст()

	first, err := service.Translate(context.Background(),
		translate.Request{Text: text, Source: "EN", Target: "RU"})
	if err != nil {
		t.Fatalf("первый перевод не удался: %v", err)
	}
	if first.Text != "Библиотека пахла пылью" {
		t.Errorf("неожиданный перевод: %q", first.Text)
	}
	if first.Cached {
		t.Error("первый перевод не мог прийти из кэша")
	}

	second, err := service.Translate(context.Background(),
		translate.Request{Text: text, Source: "EN", Target: "RU"})
	if err != nil {
		t.Fatalf("повторный перевод не удался: %v", err)
	}
	if !second.Cached {
		t.Error("повторный перевод обязан приходить из кэша")
	}
	if second.Text != first.Text {
		t.Errorf("кэш вернул другой текст: %q вместо %q", second.Text, first.Text)
	}

	// Ради этого кэш и делался: читатели одной книги жмут по одним и тем же
	// фразам, и платить за них дважды незачем.
	if got := calls.Load(); got != 1 {
		t.Errorf("к DeepL обратились %d раза вместо одного", got)
	}
}

func TestРазныеНаправленияПереводаНеПутаются(t *testing.T) {
	s := openStore(t)
	deepl, calls := фейковыйDeepL(t, "перевод", http.StatusOK)

	service := translate.New(s.Pool, "test-key", deepl.URL, 5*time.Second)
	text := уникальныйТекст()

	ctx := context.Background()
	if _, err := service.Translate(ctx, translate.Request{Text: text, Target: "RU"}); err != nil {
		t.Fatalf("перевод на русский: %v", err)
	}
	if _, err := service.Translate(ctx, translate.Request{Text: text, Target: "DE"}); err != nil {
		t.Fatalf("перевод на немецкий: %v", err)
	}

	// Один и тот же текст на разные языки — это два разных перевода, и кэш
	// обязан их различать.
	if got := calls.Load(); got != 2 {
		t.Errorf("к DeepL обратились %d раз вместо двух", got)
	}
}

func TestБезКлючаСервисОтвечаетПонятнойОшибкой(t *testing.T) {
	s := openStore(t)
	service := translate.New(s.Pool, "", "https://example.invalid", time.Second)

	if service.Configured() {
		t.Fatal("сервис без ключа не должен считаться настроенным")
	}

	_, err := service.Translate(context.Background(),
		translate.Request{Text: уникальныйТекст(), Target: "RU"})
	if !errors.Is(err, translate.ErrUnavailable) {
		t.Fatalf("ожидалась ErrUnavailable, получено: %v", err)
	}
}

func TestИсчерпанныйЛимитОтличаетсяОтПоломки(t *testing.T) {
	s := openStore(t)
	// 456 — код DeepL «закончилась квота символов».
	deepl, _ := фейковыйDeepL(t, "", 456)

	service := translate.New(s.Pool, "test-key", deepl.URL, 5*time.Second)
	_, err := service.Translate(context.Background(),
		translate.Request{Text: уникальныйТекст(), Target: "RU"})

	if !errors.Is(err, translate.ErrUnavailable) {
		t.Fatalf("ожидалась ErrUnavailable, получено: %v", err)
	}
	// Сообщение должно объяснять причину: «перевод сломался» и «кончились
	// символы» требуют разных действий от владельца сервиса.
	if err == nil || !strings.Contains(err.Error(), "лимит") {
		t.Errorf("причина не объяснена: %v", err)
	}
}

func TestПустойТекстНеУходитВоВнешнийСервис(t *testing.T) {
	s := openStore(t)
	deepl, calls := фейковыйDeepL(t, "", http.StatusOK)

	service := translate.New(s.Pool, "test-key", deepl.URL, 5*time.Second)
	if _, err := service.Translate(context.Background(),
		translate.Request{Text: "   ", Target: "RU"}); err == nil {
		t.Fatal("пустой текст должен отклоняться")
	}
	if calls.Load() != 0 {
		t.Error("пустой запрос ушёл к провайдеру")
	}
}
