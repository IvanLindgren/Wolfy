// Package auth опознаёт пользователя по сессионному токену Читавука.
//
// Своей регистрации у Wolfy нет и не будет: аккаунт один на два приложения,
// и заводить второй способ входа значило бы получить двух пользователей с
// одной почтой. Токен выпускает Читавук, Wolfy его только проверяет.
//
// Механизм проверки продиктован тем, как Читавук устроен: токен непрозрачный
// (`ctv_<base64>`), а в базе лежит его SHA-256. Значит проверка — это хеш плюс
// один запрос к таблице sessions. Ни общего секрета, ни разбора JWT здесь не
// нужно, и это лучше: секрет пришлось бы синхронизировать между двумя
// деплоями, а хеш работает с уже выпущенными токенами как есть.
package auth

import (
	"context"
	"crypto/sha256"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrUnauthorized — токена нет, он просрочен или не наш.
var ErrUnauthorized = errors.New("нужен вход")

// User — то, что сервису нужно знать о пользователе.
type User struct {
	ID          string
	Email       string
	DisplayName string
}

// Verifier проверяет токены по базе Читавука.
type Verifier struct {
	pool *pgxpool.Pool
}

func NewVerifier(pool *pgxpool.Pool) *Verifier {
	return &Verifier{pool: pool}
}

// BearerToken достаёт токен из заголовка Authorization.
//
// Схема сравнивается без учёта регистра: RFC 7235 объявляет её
// case-insensitive, и разные HTTP-клиенты шлют то "Bearer", то "bearer".
func BearerToken(header string) string {
	scheme, value, ok := strings.Cut(strings.TrimSpace(header), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return ""
	}
	return strings.TrimSpace(value)
}

// Verify опознаёт пользователя по токену.
func (v *Verifier) Verify(ctx context.Context, token string) (User, error) {
	if token == "" {
		return User{}, ErrUnauthorized
	}
	sum := sha256.Sum256([]byte(token))

	var user User
	err := v.pool.QueryRow(ctx, `
        SELECT u.id::text, u.email, u.display_name
          FROM sessions s
          JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = $1
           AND s.expires_at > now()`,
		sum[:]).Scan(&user.ID, &user.Email, &user.DisplayName)

	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, ErrUnauthorized
	}
	if err != nil {
		return User{}, err
	}

	// Отмечаем, что сессия жива. Ошибку намеренно игнорируем: не смогли
	// обновить отметку — пользователь всё равно вошёл, и ронять запрос из-за
	// статистики неправильно.
	go v.touch(sum[:])

	return user, nil
}

func (v *Verifier) touch(hash []byte) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, _ = v.pool.Exec(ctx,
		`UPDATE sessions SET last_seen_at = now() WHERE token_hash = $1`, hash)
}

// ключ пользователя в контексте запроса. Свой тип, чтобы чужой пакет не мог
// подменить значение, положив в контекст строку с тем же именем.
type contextKey struct{}

// WithUser кладёт пользователя в контекст запроса.
func WithUser(ctx context.Context, user User) context.Context {
	return context.WithValue(ctx, contextKey{}, user)
}

// FromContext достаёт пользователя, опознанного middleware.
func FromContext(ctx context.Context) (User, bool) {
	user, ok := ctx.Value(contextKey{}).(User)
	return user, ok
}

// Middleware пропускает дальше только опознанных пользователей.
//
// Идентификатор пользователя после этого берётся исключительно из контекста и
// никогда из тела запроса: иначе любой вошедший читал бы чужую библиотеку,
// подставив чужой user_id.
func (v *Verifier) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := BearerToken(r.Header.Get("Authorization"))
		user, err := v.Verify(r.Context(), token)
		if err != nil {
			if !errors.Is(err, ErrUnauthorized) {
				http.Error(w, `{"error":"база недоступна"}`, http.StatusServiceUnavailable)
				return
			}
			w.Header().Set("WWW-Authenticate", "Bearer")
			http.Error(w, `{"error":"нужен вход"}`, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r.WithContext(WithUser(r.Context(), user)))
	})
}
