package auth_test

import (
	"context"
	"crypto/sha256"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/store"
)

func TestРазборЗаголовкаAuthorization(t *testing.T) {
	cases := []struct {
		header string
		want   string
	}{
		{"Bearer ctv_abc", "ctv_abc"},
		// RFC 7235 объявляет схему case-insensitive, и клиенты этим пользуются.
		{"bearer ctv_abc", "ctv_abc"},
		{"  Bearer   ctv_abc  ", "ctv_abc"},
		{"Basic ctv_abc", ""},
		{"ctv_abc", ""},
		{"", ""},
	}
	for _, c := range cases {
		if got := auth.BearerToken(c.header); got != c.want {
			t.Errorf("BearerToken(%q) = %q, ожидалось %q", c.header, got, c.want)
		}
	}
}

func TestТокенЗапросаБерётсяИзCookie(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/v1/me", nil)
	r.AddCookie(&http.Cookie{Name: auth.SessionCookie, Value: "ctv_cookie"})
	if got := auth.RequestToken(r); got != "ctv_cookie" {
		t.Fatalf("cookie token = %q", got)
	}
	r.Header.Set("Authorization", "Bearer ctv_header")
	if got := auth.RequestToken(r); got != "ctv_header" {
		t.Fatalf("Bearer не получил приоритет: %q", got)
	}
}

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

// выдатьТокен заводит пользователя и живую сессию — ровно так, как это делает
// Читавук при входе.
func выдатьТокен(t *testing.T, s *store.Store, expires time.Time) (token, userID string) {
	t.Helper()
	ctx := context.Background()

	err := s.Pool.QueryRow(ctx, `
        INSERT INTO users (id, email, display_name)
        VALUES (gen_random_uuid(), $1, 'Читатель')
        RETURNING id::text`,
		"auth-"+time.Now().Format("150405.000000000")+"@example.com").Scan(&userID)
	if err != nil {
		t.Fatalf("пользователь не создан: %v", err)
	}
	t.Cleanup(func() {
		_, _ = s.Pool.Exec(context.Background(), `DELETE FROM users WHERE id = $1`, userID)
	})

	token = "ctv_" + time.Now().Format("150405.000000000")
	sum := sha256.Sum256([]byte(token))
	_, err = s.Pool.Exec(ctx, `
        INSERT INTO sessions (token_hash, user_id, expires_at)
        VALUES ($1, $2, $3)`, sum[:], userID, expires)
	if err != nil {
		t.Fatalf("сессия не создана: %v", err)
	}
	return token, userID
}

func TestЖивойТокенЧитавукаОпознаётся(t *testing.T) {
	s := openStore(t)
	token, userID := выдатьТокен(t, s, time.Now().Add(24*time.Hour))

	user, err := auth.NewVerifier(s.Pool).Verify(context.Background(), token)
	if err != nil {
		t.Fatalf("токен Читавука не опознан: %v", err)
	}
	if user.ID != userID {
		t.Errorf("опознан не тот пользователь: %s вместо %s", user.ID, userID)
	}
	if user.DisplayName != "Читатель" {
		t.Errorf("имя не прочитано: %q", user.DisplayName)
	}
}

func TestПросроченныйТокенНеПускает(t *testing.T) {
	s := openStore(t)
	token, _ := выдатьТокен(t, s, time.Now().Add(-time.Hour))

	_, err := auth.NewVerifier(s.Pool).Verify(context.Background(), token)
	if !errors.Is(err, auth.ErrUnauthorized) {
		t.Fatalf("просроченная сессия пустила внутрь: %v", err)
	}
}

func TestЧужойИПустойТокенНеПускают(t *testing.T) {
	s := openStore(t)
	verifier := auth.NewVerifier(s.Pool)

	for _, token := range []string{"", "ctv_никогда-не-выдавался", "мусор"} {
		if _, err := verifier.Verify(context.Background(), token); !errors.Is(err, auth.ErrUnauthorized) {
			t.Errorf("токен %q не отклонён: %v", token, err)
		}
	}
}

func TestMiddlewareКладётПользователяВКонтекст(t *testing.T) {
	s := openStore(t)
	token, userID := выдатьТокен(t, s, time.Now().Add(24*time.Hour))

	var seen string
	handler := auth.NewVerifier(s.Pool).Middleware(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Идентификатор берётся только отсюда и никогда из тела запроса:
			// иначе любой вошедший читал бы чужую библиотеку.
			if user, ok := auth.FromContext(r.Context()); ok {
				seen = user.ID
			}
			w.WriteHeader(http.StatusOK)
		}))

	request := httptest.NewRequest(http.MethodGet, "/v1/me", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()

	handler.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("вошедшего не пустили: %d", recorder.Code)
	}
	if seen != userID {
		t.Errorf("в контексте не тот пользователь: %s вместо %s", seen, userID)
	}
}

func TestMiddlewareОтклоняетЗапросБезТокена(t *testing.T) {
	s := openStore(t)

	handler := auth.NewVerifier(s.Pool).Middleware(
		http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			t.Error("запрос без токена дошёл до обработчика")
		}))

	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/v1/me", nil))

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("ожидался 401, получен %d", recorder.Code)
	}
	if recorder.Header().Get("WWW-Authenticate") == "" {
		t.Error("не сказано, какая схема авторизации нужна")
	}
}
