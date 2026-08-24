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
