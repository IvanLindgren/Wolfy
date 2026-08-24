package api

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/wolfy/server/internal/translate"
)

func TestTranslateHandlerRejectsTooLargeText(t *testing.T) {
	// Use nil store (pool nil) — our translate will handle nil pool for limits
	svc := translate.New(nil, "key", "http://example.invalid", 5_000_000)
	// Need to bypass DB, so we use a Server with minimal deps
	// store can be nil for this handler test? Server health checks store, but postTranslate doesn't use store directly
	// Pass dummy store (not used for translate)
	srv := &Server{
		translate:      svc,
		translateLimit: newRateLimiter(200, 1, 30_000_000_000),
		log:            slog.Default(),
	}
	large := strings.Repeat("a", translate.MaxTextRunes+1)
	body, _ := json.Marshal(map[string]string{"text": large, "target": "RU"})
	req := httptest.NewRequest(http.MethodPost, "/v1/translate", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	srv.postTranslate(w, req)
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("ожидался 413 для слишком большого текста, получено %d body %s", w.Code, w.Body.String())
	}
}

func TestTranslateHandlerRejectsTooLargeContext(t *testing.T) {
	svc := translate.New(nil, "key", "http://example.invalid", 5_000_000)
	srv := &Server{
		translate:      svc,
		translateLimit: newRateLimiter(200, 1, 30_000_000_000),
		log:            slog.Default(),
	}
	largeCtx := strings.Repeat("b", translate.MaxContextRunes+1)
	body, _ := json.Marshal(map[string]string{"text": "hi", "context": largeCtx, "target": "RU"})
	req := httptest.NewRequest(http.MethodPost, "/v1/translate", bytes.NewReader(body))
	w := httptest.NewRecorder()
	srv.postTranslate(w, req)
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("ожидался 413 для слишком большого контекста, получено %d", w.Code)
	}
}

func TestTranslateHandlerRateLimitsByCost(t *testing.T) {
	svc := translate.New(nil, "", "http://example.invalid", 5_000_000) // no key, will return Unavailable after rate limit not hit
	limiter := newRateLimiter(3, 0, 30_000_000_000)                    // burst 3, no refill
	srv := &Server{
		translate:      svc,
		translateLimit: limiter,
		log:            slog.Default(),
	}
	// Small request cost 1, should pass 3 times
	for i := 0; i < 3; i++ {
		body, _ := json.Marshal(map[string]string{"text": "hi", "target": "RU"})
		req := httptest.NewRequest(http.MethodPost, "/v1/translate", bytes.NewReader(body))
		req.RemoteAddr = "1.2.3.4:1234"
		w := httptest.NewRecorder()
		srv.postTranslate(w, req)
		// First three may be 503 (unavailable) but not 429
		if w.Code == http.StatusTooManyRequests {
			t.Fatalf("маленький запрос %d ошибочно за-rate-limited", i)
		}
	}
	// Large request cost ~5, should be rate limited even though bucket empty
	large := strings.Repeat("a", 1500) // cost ~ 3+?
	body, _ := json.Marshal(map[string]string{"text": large, "target": "RU"})
	req := httptest.NewRequest(http.MethodPost, "/v1/translate", bytes.NewReader(body))
	req.RemoteAddr = "1.2.3.4:1234"
	w := httptest.NewRecorder()
	srv.postTranslate(w, req)
	if w.Code != http.StatusTooManyRequests && w.Code != http.StatusRequestEntityTooLarge {
		// If large is within limit but cost exceeds remaining tokens, should be 429
		// If large exceeds MaxTextRunes, it would be 413; our 1500 <2000 so 429 expected
		t.Fatalf("большой запрос не за-rate-limited: %d", w.Code)
	}
}
