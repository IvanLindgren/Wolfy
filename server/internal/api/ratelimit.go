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
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	b, known := l.buckets[key]
	if !known {
		l.buckets[key] = &bucket{left: l.burst - 1, seen: now}
		l.sweep(now)
		return true
	}

	b.left += now.Sub(b.seen).Seconds() * l.refill
	if b.left > l.burst {
		b.left = l.burst
	}
	b.seen = now

	if b.left < 1 {
		return false
	}
	b.left--
	return true
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
			"error": "слишком много переводов подряд, подождите минуту",
		})
	})
}

// clientIP достаёт адрес запроса.
//
// X-Forwarded-For учитывается, потому что сервис стоит за обратным прокси и
// без этого все запросы пришли бы с одного адреса — самого прокси, — и первый
// же читатель исчерпал бы запас на всех. Берётся первый адрес цепочки: его
// подставляет ближайший к клиенту прокси.
//
// Заголовок подделывается тривиально, и полагаться на него как на защиту
// нельзя. Здесь он и не защита: настоящий предел ставит DeepL своей квотой, а
// это — вежливая просьба не частить.
func clientIP(r *http.Request) string {
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		for i := 0; i < len(forwarded); i++ {
			if forwarded[i] == ',' {
				return trimSpace(forwarded[:i])
			}
		}
		return trimSpace(forwarded)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
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
