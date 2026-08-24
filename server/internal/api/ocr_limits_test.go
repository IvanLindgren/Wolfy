package api

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/ocr"
)

func TestOCRPerUserRateLimit(t *testing.T) {
	svc := ocr.New("key", "http://example.invalid", "model", time.Second)
	limiter := newRateLimiter(2, 0, time.Minute) // burst 2, no refill
	srv := &Server{
		ocr:      svc,
		ocrLimit: limiter,
		log:      slog.Default(),
	}
	// Build a valid tiny jpeg body
	img := []byte{0xFF, 0xD8, 0xFF}
	b64 := base64.StdEncoding.EncodeToString(img)
	payload, _ := json.Marshal(map[string]string{"image": b64, "mime": "image/jpeg"})

	makeReq := func(userID string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/v1/ocr", bytes.NewReader(payload))
		req.Header.Set("Content-Type", "application/json")
		// inject user into context as auth middleware does
		ctx := auth.WithUser(req.Context(), auth.User{ID: userID})
		req = req.WithContext(ctx)
		w := httptest.NewRecorder()
		srv.postOCR(w, req)
		return w
	}

	// first two should not be rate limited (may be 503 or 400 due to invalid provider, but not 429)
	w1 := makeReq("user1")
	if w1.Code == http.StatusTooManyRequests {
		t.Fatal("первый OCR запрос за-rate-limited")
	}
	w2 := makeReq("user1")
	if w2.Code == http.StatusTooManyRequests {
		t.Fatal("второй OCR запрос за-rate-limited")
	}
	// third should be 429
	w3 := makeReq("user1")
	if w3.Code != http.StatusTooManyRequests {
		t.Fatalf("третий запрос user1 должен быть 429, получено %d", w3.Code)
	}
	// different user should still pass
	wOther := makeReq("user2")
	if wOther.Code == http.StatusTooManyRequests {
		t.Fatal("другой пользователь ошибочно за-rate-limited")
	}
}

func TestOCRRejectsTooLargeImage(t *testing.T) {
	svc := ocr.New("key", "http://example.invalid", "model", time.Second)
	srv := &Server{
		ocr:      svc,
		ocrLimit: newRateLimiter(10, 1, time.Minute),
		log:      slog.Default(),
	}
	large := make([]byte, ocr.MaxImageBytes+1)
	// fill with jpeg header to pass mime check but size will be checked before mime? Actually size checked before mime in handler and service
	b64 := base64.StdEncoding.EncodeToString(large)
	payload, _ := json.Marshal(map[string]string{"image": b64, "mime": "image/jpeg"})
	req := httptest.NewRequest(http.MethodPost, "/v1/ocr", bytes.NewReader(payload))
	ctx := auth.WithUser(req.Context(), auth.User{ID: "u1"})
	req = req.WithContext(ctx)
	// Need to bypass handler's json limit? It checks ContentLength and body limit
	// Our payload is huge (~11MB), handler should return 413
	w := httptest.NewRecorder()
	srv.postOCR(w, req)
	if w.Code != http.StatusRequestEntityTooLarge && w.Code != http.StatusBadRequest {
		t.Fatalf("большой снимок должен отклоняться 413, получено %d", w.Code)
	}
}

func TestOCRRejectsInvalidMime(t *testing.T) {
	svc := ocr.New("key", "http://example.invalid", "model", time.Second)
	srv := &Server{
		ocr:      svc,
		ocrLimit: newRateLimiter(10, 1, time.Minute),
		log:      slog.Default(),
	}
	img := []byte{0xFF, 0xD8, 0xFF}
	b64 := base64.StdEncoding.EncodeToString(img)
	payload, _ := json.Marshal(map[string]string{"image": b64, "mime": "image/gif"})
	req := httptest.NewRequest(http.MethodPost, "/v1/ocr", bytes.NewReader(payload))
	ctx := auth.WithUser(req.Context(), auth.User{ID: "u1"})
	req = req.WithContext(ctx)
	w := httptest.NewRecorder()
	srv.postOCR(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("невалидный mime должен давать 400, получено %d", w.Code)
	}
}
