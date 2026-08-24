package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/wolfy/server/internal/auth"
)

func TestCookieSessionBodyRemovesToken(t *testing.T) {
	token, body := cookieSessionBody([]byte(`{"token":"ctv_secret","email":"reader@example.test"}`))
	if token != "ctv_secret" {
		t.Fatalf("token = %q", token)
	}
	var payload map[string]any
	if json.Unmarshal(body, &payload) != nil || payload["token"] != nil || payload["email"] != "reader@example.test" {
		t.Fatalf("секрет остался или профиль потерян: %s", body)
	}
}

func TestSessionCookieIsHttpOnlyAndLax(t *testing.T) {
	r := httptest.NewRequest(http.MethodPost, "/v1/auth/login", nil)
	cookie := sessionCookie(r, "ctv_secret")
	if cookie.Name != auth.SessionCookie || !cookie.HttpOnly || cookie.SameSite != http.SameSiteLaxMode || cookie.Path != "/" {
		t.Fatalf("небезопасная cookie: %+v", cookie)
	}
}

func TestSessionCookieSecureЗаЛокальнымTLSProxy(t *testing.T) {
	proxied := httptest.NewRequest(http.MethodPost, "/v1/auth/google", nil)
	proxied.RemoteAddr = "127.0.0.1:43120"
	proxied.Header.Set("X-Forwarded-Proto", "https")
	if !sessionCookie(proxied, "ctv_secret").Secure {
		t.Fatal("production cookie за HTTPS Nginx осталась без Secure")
	}

	direct := httptest.NewRequest(http.MethodPost, "/v1/auth/google", nil)
	direct.RemoteAddr = "198.51.100.9:43120"
	direct.Header.Set("X-Forwarded-Proto", "https")
	if sessionCookie(direct, "ctv_secret").Secure {
		t.Fatal("прямой HTTP-клиент подделал X-Forwarded-Proto")
	}
}
