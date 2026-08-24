package api

import (
	"net"
	"net/http"
	"sync"
	"time"
)

// Ограничитель частоты запросов по адресу.
//
// Нужен ровно одному маршруту — переводу. Он открыт без аккаунта, потому что
// читатель, поставивший приложение, должен получить перевод сразу, а не после
// регистрации. Но за каждым запросом стоит платный внешний сервис, и открытый
// маршрут без ограничения — это чужой счёт, выставленный владельцу ключа.
//
// Считается «дырявым ведром»: у адреса есть запас обращений, который тратится
// на каждый запрос и понемногу восстанавливается со временем. Так читатель,
// разбирающий трудную страницу залпом, проходит без помех, а тот, кто гонит
// перевод книги целиком в цикле, упирается в стену.
type rateLimiter struct {
	// Сколько запросов можно сделать залпом.
	burst float64
	// С какой скоростью запас восстанавливается, запросов в секунду.
	refill float64
	// Через сколько молчания адрес забывается: держать в памяти всех, кто
	// когда-либо заходил, — это утечка, растущая ровно как популярность.
	forget time.Duration

	mu      sync.Mutex
	buckets map[string]*bucket
	now     func() time.Time
}

type bucket struct {
	left float64
	seen time.Time
}

func newRateLimiter(burst, refill float64, forget time.Duration) *rateLimiter {
	return &rateLimiter{
		burst:   burst,
		refill:  refill,
		forget:  forget,
		buckets: make(map[string]*bucket),
		now:     time.Now,
	}
}

// allow говорит, пропускать ли очередной запрос с этого адреса.
func (l *rateLimiter) allow(key string) bool {
	return l.allowN(key, 1)
}

// allowN списывает cost токенов. Нужно для тарификации по размеру:
// перевод «привет» и «десяток килобайт» не должны стоить одинаково.
func (l *rateLimiter) allowN(key string, cost float64) bool {
	if cost < 1 {
		cost = 1
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	b, known := l.buckets[key]
	if !known {
		if cost > l.burst {
			l.buckets[key] = &bucket{left: 0, seen: now}
			l.sweep(now)
			return false
		}
		l.buckets[key] = &bucket{left: l.burst - cost, seen: now}
		l.sweep(now)
		return true
	}

	b.left += now.Sub(b.seen).Seconds() * l.refill
	if b.left > l.burst {
		b.left = l.burst
	}
	b.seen = now

	if b.left < cost {
		return false
	}
	b.left -= cost
	return true
}

// translateCost грубо отражает платную стоимость запроса: короткий текст
// стоит 1, большой — в несколько токенов, чтобы объём тарифицировался.
func translateCost(text, contextText string) float64 {
	total := len([]rune(text)) + len([]rune(contextText))
	if total <= 400 {
		return 1
	}
	// Каждые дополнительные ~400 символов — ещё один токен.
	extra := float64(total-400) / 400.0
	cost := 1 + extra
	if cost > 8 {
		cost = 8
	}
	return cost
}

// sweep выбрасывает адреса, которые давно молчат.
//
// Зовётся при появлении нового адреса, а не по таймеру: отдельный таймер
// пришлось бы останавливать вместе с сервером, а забывать записи нужно ровно
// тогда, когда их количество растёт.
func (l *rateLimiter) sweep(now time.Time) {
	for key, b := range l.buckets {
		if now.Sub(b.seen) > l.forget {
			delete(l.buckets, key)
		}
	}
}

// withRateLimit пропускает запрос или отвечает 429.
func (l *rateLimiter) withRateLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if l.allow(clientIP(r)) {
			next.ServeHTTP(w, r)
			return
		}
		// 429, а не 403: читателю отказано временно, и через минуту он снова
		// сможет переводить. Сообщение написано для него, а не для отладки.
		writeJSON(w, http.StatusTooManyRequests, map[string]string{
			"error": "слишком много запросов подряд, подождите минуту",
		})
	})
}

// clientIP достаёт адрес запроса.
//
// Production-сервис слушает loopback за нашим Nginx. Только от loopback можно
// доверять X-Real-IP, который vhost всегда перезаписывает через $remote_addr.
// Клиентский X-Forwarded-For намеренно игнорируется: `$proxy_add_x_forwarded_for`
// сохраняет подложенный первый адрес и позволил бы обходить лимит новым
// заголовком на каждом запросе. При прямом подключении источником остаётся
// RemoteAddr независимо от любых заголовков.
func clientIP(r *http.Request) string {
	host := remoteHost(r.RemoteAddr)
	parsed := net.ParseIP(host)
	if parsed != nil && parsed.IsLoopback() {
		real := net.ParseIP(trimSpace(r.Header.Get("X-Real-IP")))
		if real != nil {
			return real.String()
		}
	}
	return host
}

func remoteHost(address string) string {
	host, _, err := net.SplitHostPort(address)
	if err == nil {
		return host
	}
	return address
}

func trimSpace(text string) string {
	start, end := 0, len(text)
	for start < end && (text[start] == ' ' || text[start] == '\t') {
		start++
	}
	for end > start && (text[end-1] == ' ' || text[end-1] == '\t') {
		end--
	}
	return text[start:end]
}
