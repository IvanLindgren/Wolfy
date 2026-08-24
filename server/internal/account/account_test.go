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

func TestSocialEndpointsStayWithCitavuk(t *testing.T) {
	var visited string
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		visited = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"token":"ctv_social"}`))
	}))
	defer upstream.Close()

	service := New("https://example.invalid/login", "", "", time.Second).
		WithSocial(upstream.URL+"/google", upstream.URL+"/yandex/start", upstream.URL+"/yandex/complete").
		WithYandexWebReturn(true)
	service.client = upstream.Client()
	if !service.CanGoogle() || !service.CanYandex() {
		t.Fatal("настроенные социальные способы скрыты")
	}
	result, err := service.Google(context.Background(), []byte(`{"idToken":"id"}`))
	if err != nil || visited != "/google" || result.Status != http.StatusOK {
		t.Fatalf("Google не прошёл через Читавук: %q %d %v", visited, result.Status, err)
	}
	_, err = service.YandexStart(context.Background(), []byte(`{"returnTarget":"desktop"}`))
	if err != nil || visited != "/yandex/start" {
		t.Fatalf("Yandex start ушёл не туда: %q %v", visited, err)
	}
}

func TestYandexWebStaysHiddenUntilCitavukSupportsTrustedReturn(t *testing.T) {
	service := New("https://example.invalid/login", "", "", time.Second).
		WithSocial("", "https://example.invalid/yandex/start", "https://example.invalid/yandex/complete")

	if !service.CanYandex() {
		t.Fatal("desktop endpoints настроены, но способ скрыт")
	}
	if service.CanYandexWeb() {
		t.Fatal("web-вход объявлен без поддержки trusted returnUrl у Читавука")
	}
	_, err := service.YandexStart(context.Background(), []byte(
		`{"returnTarget":"web","returnUrl":"https://wolfy.citavuk.ru/auth/return"}`,
	))
	if !errors.Is(err, ErrNotConfigured) {
		t.Fatalf("небезопасный web-start не остановлен: %v", err)
	}

	service.WithYandexWebReturn(true)
	if !service.CanYandexWeb() {
		t.Fatal("обновлённый контракт Читавука не включил web-вход")
	}
}
