package account

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLoginPreservesCitavukContract(t *testing.T) {
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("Content-Type") != "application/json" {
			t.Fatalf("неверный запрос: %s %s", r.Method, r.Header.Get("Content-Type"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"token":"ctv_test"}`))
	}))
	defer upstream.Close()

	service := New(upstream.URL, upstream.URL, upstream.URL, time.Second)
	service.client = upstream.Client()
	result, err := service.Login(context.Background(), []byte(`{"email":"a@b.c","password":"secret"}`))
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != http.StatusOK || string(result.Body) != `{"token":"ctv_test"}` {
		t.Fatalf("ответ изменён: %d %s", result.Status, result.Body)
	}
}

// Регистрация идёт по своему адресу, а не по адресу входа: перепутать их
// значит отправить пароль на создание аккаунта туда, где ждут вход.
func TestRegisterGoesToItsOwnAddress(t *testing.T) {
	var visited string
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		visited = r.URL.Path
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"verificationRequired":true,"email":"a@b.c"}`))
	}))
	defer upstream.Close()

	service := New(upstream.URL+"/login", upstream.URL+"/register", upstream.URL+"/resend", time.Second)
	service.client = upstream.Client()

	result, err := service.Register(context.Background(), []byte(`{"email":"a@b.c","password":"secret12"}`))
	if err != nil {
		t.Fatal(err)
	}
	if visited != "/register" {
		t.Fatalf("запрос ушёл по адресу %q", visited)
	}
	// 202 значит «письмо ушло, сессии пока нет» — статус обязан дойти до
	// клиента нетронутым, иначе тот покажет вход вместо ожидания письма.
	if result.Status != http.StatusAccepted {
		t.Fatalf("статус изменён: %d", result.Status)
	}
}

// Ненастроенная возможность и недоступный Читавук — разные ответы: в первом
// случае ждать бесполезно, и читателю надо предложить другой путь.
func TestUnconfiguredIsNotTheSameAsUnavailable(t *testing.T) {
	service := New("https://example.invalid/login", "", "", time.Second)
	if !service.Configured() {
		t.Fatal("вход настроен, а сервис говорит обратное")
	}
	if service.CanRegister() {
		t.Fatal("пустой адрес регистрации считается настроенным")
	}
	if _, err := service.Register(context.Background(), []byte(`{}`)); !errors.Is(err, ErrNotConfigured) {
		t.Fatalf("ожидалась ErrNotConfigured, получено %v", err)
	}
}
