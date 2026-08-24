package ocr_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/wolfy/server/internal/ocr"
)

// провайдер поднимает заглушку, отвечающую как OpenAI-совместимый сервис.
func провайдер(t *testing.T, answer string) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)

		// Снимок обязан уйти data-URL-ом: без типа модель картинку не примет.
		if !strings.Contains(string(body), "data:image/") {
			t.Errorf("в запросе нет картинки: %s", truncate(string(body)))
		}
		if r.Header.Get("Authorization") == "" {
			t.Error("запрос ушёл без ключа")
		}

		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]string{"content": answer}},
			},
		})
	}))
	t.Cleanup(server.Close)
	return server
}

func truncate(s string) string {
	if len(s) > 200 {
		return s[:200] + "…"
	}
	return s
}

func TestСтраницаРаспознаётся(t *testing.T) {
	server := провайдер(t, "The library smelled of dust.\n\nEvelyn pushed the door.")
	service := ocr.New("ключ", server.URL, "тест/модель", 5*time.Second)

	result, err := service.Recognize(context.Background(), []byte{0xFF, 0xD8, 0xFF}, "image/jpeg")
	if err != nil {
		t.Fatalf("распознавание: %v", err)
	}
	if !strings.Contains(result.Text, "Evelyn") {
		t.Fatalf("текст не приехал: %q", result.Text)
	}
	if result.Model != "тест/модель" {
		t.Fatalf("модель в ответе: %q", result.Model)
	}
}

func TestБезКлючаРаспознаваниеНедоступно(t *testing.T) {
	// Сервис без ключа обязан жить дальше и честно отвечать: чтение и разбор
	// слов считаются на устройстве и от распознавания не зависят.
	service := ocr.New("", "http://example.invalid", "модель", time.Second)

	if service.Configured() {
		t.Fatal("сервис без ключа считает себя настроенным")
	}
	_, err := service.Recognize(context.Background(), []byte{1}, "image/jpeg")
	if err == nil {
		t.Fatal("распознавание без ключа не отказало")
	}
}

func TestСлишкомБольшойСнимокОтклоняется(t *testing.T) {
	service := ocr.New("ключ", "http://example.invalid", "модель", time.Second)

	_, err := service.Recognize(context.Background(), make([]byte, ocr.MaxImageBytes+1), "image/jpeg")
	if err == nil {
		t.Fatal("снимок сверх предела прошёл")
	}
}

func TestОграждениеИзКавычекСнимается(t *testing.T) {
	// Модель просили вернуть только текст, и обычно она так и делает. Но
	// «обычно» тут мало: читатель увидел бы кавычки на странице книги.
	cases := map[string]string{
		"```\nThe old library.\n```":     "The old library.",
		"```text\nThe old library.\n```": "The old library.",
		"  The old library.  ":           "The old library.",
	}

	for input, expected := range cases {
		if got := ocr.Clean(input); got != expected {
			t.Errorf("Clean(%q) = %q, ожидалось %q", input, got, expected)
		}
	}
}

func TestЛишниеПустыеСтрокиУбираются(t *testing.T) {
	// Абзац отделяется одним пустым рядом. Три подряд — это дырка в наборе,
	// которую видно на газетной полосе сразу.
	got := ocr.Clean("First.\n\n\n\nSecond.")
	if got != "First.\n\nSecond." {
		t.Fatalf("получилось %q", got)
	}
}

func TestПереводыСтрокПриводятсяКОдномуВиду(t *testing.T) {
	// Клиент считает смещения в символах, и «\r\n» сдвинул бы подсветку на
	// каждой строке.
	got := ocr.Clean("First.\r\n\r\nSecond.")
	if strings.Contains(got, "\r") {
		t.Fatalf("возврат каретки остался: %q", got)
	}
}

func TestМолчаниеПровайдераНеПадениеСервиса(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(`{"error":"upstream"}`))
	}))
	defer server.Close()

	service := ocr.New("ключ", server.URL, "модель", 5*time.Second)
	_, err := service.Recognize(context.Background(), []byte{0xFF, 0xD8, 0xFF, 0xD8}, "image/jpeg")

	if err == nil {
		t.Fatal("ошибка провайдера не дошла до вызывающего")
	}
	if !strings.Contains(err.Error(), "недоступно") {
		t.Fatalf("ошибку не отличить от прочих: %v", err)
	}
}
