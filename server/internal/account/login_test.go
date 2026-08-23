package account

import (
	"context"
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

	service := New(upstream.URL, time.Second)
	service.client = upstream.Client()
	result, err := service.Login(context.Background(), []byte(`{"email":"a@b.c","password":"secret"}`))
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != http.StatusOK || string(result.Body) != `{"token":"ctv_test"}` {
		t.Fatalf("ответ изменён: %d %s", result.Status, result.Body)
	}
}
