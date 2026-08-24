package social

import (
	"encoding/json"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/wolfy/server/internal/account"
)

func configuredGoogle() *Google {
	accounts := account.New("https://example.com/login", "", "", time.Second).
		WithSocial("https://example.com/google", "", "")
	return NewGoogle(
		accounts,
		"client-id", "client-secret", "https://wolfy.example/v1/auth/google/callback",
		strings.Repeat("s", 32), time.Second,
	)
}

func TestStartUsesPKCEAndEncryptedState(t *testing.T) {
	g := configuredGoogle()
	address, err := g.Start(StartRequest{
		ReturnURL: "http://127.0.0.1:43117/oauth/random",
		Device:    json.RawMessage(`{"id":"install-1","name":"Laptop","platform":"windows"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	parsed, _ := url.Parse(address)
	query := parsed.Query()
	if query.Get("code_challenge_method") != "S256" || query.Get("code_challenge") == "" {
		t.Fatalf("PKCE не включён: %s", address)
	}
	state := query.Get("state")
	if state == "" || strings.Contains(state, "43117") || strings.Contains(state, "install-1") {
		t.Fatalf("state отсутствует или раскрывает контекст: %q", state)
	}
	returnURL, err := g.ReturnURL(state)
	if err != nil || returnURL != "http://127.0.0.1:43117/oauth/random" {
		t.Fatalf("state не открылся: %q %v", returnURL, err)
	}
}

func TestStartRejectsNonLoopbackReturn(t *testing.T) {
	g := configuredGoogle()
	_, err := g.Start(StartRequest{
		ReturnURL: "https://attacker.example/steal",
		Device:    json.RawMessage(`{"id":"install-1"}`),
	})
	if err != ErrInvalidReturn {
		t.Fatalf("ожидался отказ небезопасному адресу, получено %v", err)
	}
}

func TestStartAllowsOnlyConfiguredWebReturn(t *testing.T) {
	g := configuredGoogle().WithWebOrigin("https://app.wolfy.example")
	address, err := g.Start(StartRequest{
		ReturnURL:    "https://app.wolfy.example/auth/return?next=%2Fdiscovery",
		ReturnTarget: "web",
		Device:       json.RawMessage(`{"id":"browser-1"}`),
	})
	if err != nil || address == "" {
		t.Fatalf("веб-возврат не принят: %q %v", address, err)
	}
	_, err = g.Start(StartRequest{
		ReturnURL:    "https://attacker.example/auth/return",
		ReturnTarget: "web",
		Device:       json.RawMessage(`{"id":"browser-1"}`),
	})
	if err != ErrInvalidReturn {
		t.Fatalf("чужой origin принят: %v", err)
	}
}

func TestStateCannotBeChanged(t *testing.T) {
	g := configuredGoogle()
	address, err := g.Start(StartRequest{
		ReturnURL: "http://127.0.0.1:43117/oauth/random",
		Device:    json.RawMessage(`{"id":"install-1"}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	parsed, _ := url.Parse(address)
	state := parsed.Query().Get("state")
	replacement := "A"
	if strings.HasSuffix(state, "A") {
		replacement = "B"
	}
	_, err = g.ReturnURL(state[:len(state)-1] + replacement)
	if err != ErrInvalidState {
		t.Fatalf("изменённый state принят: %v", err)
	}
}
