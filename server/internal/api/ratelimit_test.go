package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Ограничитель пропускает залп и останавливает перебор.
//
// Проверяется не «работает ли код», а обещание: читатель, разбирающий трудную
// страницу подряд, помех не замечает, а тот, кто гонит через маршрут книгу
// целиком, упирается в стену.
func TestОграничительПропускаетЗалпИОстанавливаетПеребор(t *testing.T) {
	now := time.Now()
	limiter := newRateLimiter(200, 1, 30*time.Minute)
	limiter.now = func() time.Time { return now }

	for i := 0; i < 200; i++ {
		if !limiter.allow("1.2.3.4") {
			t.Fatalf("залп остановлен на %d-м запросе из 200", i+1)
		}
	}
	if limiter.allow("1.2.3.4") {
		t.Fatal("перебор не остановлен: запас оказался бесконечным")
	}

	// Запас восстанавливается со временем: через минуту молчания читатель
	// снова переводит.
	now = now.Add(time.Minute)
	for i := 0; i < 60; i++ {
		if !limiter.allow("1.2.3.4") {
			t.Fatalf("запас не восстановился, отказ на %d-м из 60", i+1)
		}
	}
}

func TestАдресаНеМешаютДругДругу(t *testing.T) {
	now := time.Now()
	limiter := newRateLimiter(2, 1, 30*time.Minute)
	limiter.now = func() time.Time { return now }

	limiter.allow("1.2.3.4")
	limiter.allow("1.2.3.4")
	if limiter.allow("1.2.3.4") {
		t.Fatal("свой запас не кончился")
	}
	if !limiter.allow("5.6.7.8") {
		t.Fatal("чужой адрес наказан за соседа")
	}
}

// Молчащие адреса забываются: иначе память растёт ровно как популярность.
func TestМолчащиеАдресаЗабываются(t *testing.T) {
	now := time.Now()
	limiter := newRateLimiter(2, 1, time.Minute)
	limiter.now = func() time.Time { return now }

	limiter.allow("1.2.3.4")
	now = now.Add(2 * time.Minute)
	limiter.allow("5.6.7.8")

	if _, still := limiter.buckets["1.2.3.4"]; still {
		t.Fatal("давно молчащий адрес остался в памяти")
	}
}

// Только локальному reverse proxy разрешено сообщать реальный адрес. Чужие
// X-Forwarded-For/X-Real-IP не должны превращать rate limit в декорацию.
func TestАдресБерётсяТолькоОтДоверенногоПрокси(t *testing.T) {
	r := httptest.NewRequest(http.MethodPost, "/v1/translate", nil)
	r.RemoteAddr = "127.0.0.1:5555"
	r.Header.Set("X-Real-IP", "203.0.113.7")
	r.Header.Set("X-Forwarded-For", "203.0.113.7, 10.0.0.1")

	if got := clientIP(r); got != "203.0.113.7" {
		t.Fatalf("взят адрес %q вместо адреса от локального proxy", got)
	}

	bare := httptest.NewRequest(http.MethodPost, "/v1/translate", nil)
	bare.RemoteAddr = "198.51.100.9:4444"
	bare.Header.Set("X-Real-IP", "1.2.3.4")
	bare.Header.Set("X-Forwarded-For", "5.6.7.8")
	if got := clientIP(bare); got != "198.51.100.9" {
		t.Fatalf("прямой клиент подменил адрес заголовком: %q", got)
	}
}

// Исчерпавшему запас отвечают 429, а не 403: отказ временный.
func TestИсчерпанныйЗапасОтвечает429(t *testing.T) {
	limiter := newRateLimiter(1, 0, time.Minute)
	handler := limiter.withRateLimit(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	first := httptest.NewRecorder()
	handler.ServeHTTP(first, httptest.NewRequest(http.MethodPost, "/v1/translate", nil))
	if first.Code != http.StatusOK {
		t.Fatalf("первый запрос отвергнут: %d", first.Code)
	}

	second := httptest.NewRecorder()
	handler.ServeHTTP(second, httptest.NewRequest(http.MethodPost, "/v1/translate", nil))
	if second.Code != http.StatusTooManyRequests {
		t.Fatalf("ответ %d вместо 429", second.Code)
	}
}
