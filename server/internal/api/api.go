// Package api — транспортный слой: маршруты, middleware, разбор запроса и
// сериализация ответа.
//
// Здесь нет SQL и нет решений о том, что считать правильным ответом. Задача
// пакета — принять HTTP, позвать нужный слой и вернуть JSON.
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"html"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/wolfy/server/internal/account"
	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/dictionary"
	"github.com/wolfy/server/internal/discovery"
	"github.com/wolfy/server/internal/library"
	"github.com/wolfy/server/internal/ocr"
	"github.com/wolfy/server/internal/remotebook"
	"github.com/wolfy/server/internal/social"
	"github.com/wolfy/server/internal/store"
	"github.com/wolfy/server/internal/translate"
	"github.com/wolfy/server/internal/updates"
)

// Server связывает слои и раздаёт маршруты.
type Server struct {
	store             *store.Store
	verifier          *auth.Verifier
	translate         *translate.Service
	library           *library.Service
	ocr               *ocr.Service
	account           *account.Service
	google            *social.Google
	discovery         *discovery.Service
	dictionary        *dictionary.Service
	remoteBooks       *remotebook.Service
	updates           *updates.Service
	log               *slog.Logger
	webOrigin         string
	googleWebClientID string

	// Ограничитель для открытого перевода — см. Handler.
	translateLimit *rateLimiter
	// И отдельный, куда более строгий, для входа и регистрации.
	authLimit *rateLimiter
	// Загрузка по ссылке расходует внешний трафик и память сервера.
	bookLimit *rateLimiter
}

// WithWebOrigin включает credentialed CORS только для одного известного
// origin. Основной деплой остаётся same-origin и обходится без CORS вовсе.
func (s *Server) WithWebOrigin(origin string) *Server {
	s.webOrigin = strings.TrimRight(strings.TrimSpace(origin), "/")
	return s
}

// WithGoogleWebClientID включает Google Identity Services в браузере. Client
// ID публичен; client secret веб-потоку не нужен и в SPA не передаётся.
func (s *Server) WithGoogleWebClientID(clientID string) *Server {
	s.googleWebClientID = strings.TrimSpace(clientID)
	return s
}

func NewServer(
	s *store.Store,
	v *auth.Verifier,
	t *translate.Service,
	l *library.Service,
	o *ocr.Service,
	a *account.Service,
	g *social.Google,
	d *discovery.Service,
	dict *dictionary.Service,
	updater *updates.Service,
	log *slog.Logger,
) *Server {
	return &Server{
		store: s, verifier: v, translate: t, library: l, ocr: o,
		account: a, google: g, discovery: d, dictionary: dict,
		remoteBooks: remotebook.New(30 * time.Second), updates: updater, log: log,
		// Двести переводов залпом и один в секунду сверху: страница книги
		// редко даёт больше двухсот незнакомых слов, а секунда — это дольше,
		// чем читатель успевает выбрать следующее слово, но много быстрее,
		// чем перебор словаря скриптом.
		translateLimit: newRateLimiter(200, 1, 30*time.Minute),
		authLimit:      newRateLimiter(8, 0.1, 30*time.Minute),
		bookLimit:      newRateLimiter(8, 0.05, 30*time.Minute),
	}
}

// Handler собирает маршруты сервиса.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Проверка живости. Без авторизации намеренно: её дёргает балансировщик,
	// у которого токена нет и быть не может.
	mux.HandleFunc("GET /healthz", s.health)
	// Вход публичный по определению: токен как раз получается этим запросом.
	// Вход, регистрация и повторное письмо — под общим ограничителем.
	// Читавук считает частоту и сам, но пропускать через себя перебор паролей
	// значит работать усилителем чужой атаки: адрес нападающего Читавук
	// увидит наш, и заблокирует тоже нас.
	//
	// Восемь попыток залпом и одна в десять секунд сверху: человек, забывший
	// пароль, пробует три-четыре раза подряд, а перебору такая скорость
	// бесполезна.
	mux.Handle("POST /v1/auth/login", s.authLimit.withRateLimit(
		http.HandlerFunc(s.postLogin),
	))
	mux.Handle("POST /v1/auth/register", s.authLimit.withRateLimit(
		http.HandlerFunc(s.postRegister),
	))
	mux.Handle("POST /v1/auth/resend-verification", s.authLimit.withRateLimit(
		http.HandlerFunc(s.postResendVerification),
	))
	mux.Handle("POST /v1/auth/google", s.authLimit.withRateLimit(
		http.HandlerFunc(s.postGoogleToken),
	))
	mux.Handle("POST /v1/auth/google/start", s.authLimit.withRateLimit(
		http.HandlerFunc(s.postGoogleStart),
	))
	// Google возвращает браузер сюда. Ограничитель входа не нужен: state
	// зашифрован, живёт пять минут и проверяется до обращения к провайдеру.
	mux.HandleFunc("GET /v1/auth/google/callback", s.getGoogleCallback)
	mux.Handle("POST /v1/auth/yandex/start", s.authLimit.withRateLimit(
		http.HandlerFunc(s.postYandexStart),
	))
	mux.Handle("POST /v1/auth/yandex/complete", s.authLimit.withRateLimit(
		http.HandlerFunc(s.postYandexComplete),
	))
	mux.HandleFunc("POST /v1/auth/logout", s.postLogout)

	// Перевод — без аккаунта. Читатель, поставивший приложение, должен
	// получить перевод в первую же минуту: без него книга на чужом языке
	// остаётся просто книгой на чужом языке, а требовать регистрацию за то,
	// ради чего приложение и ставят, — верный способ его удалить.
	//
	// За каждым запросом стоит платный сервис, поэтому маршрут открыт не
	// настежь, а под ограничителем частоты. Он пропускает залп — трудная
	// страница разбирается подряд — и останавливает того, кто гонит через
	// него книгу целиком.
	mux.Handle("POST /v1/translate", s.translateLimit.withRateLimit(
		http.HandlerFunc(s.postTranslate),
	))
	// Толкование публично: это fallback для тех, кто не скачал тот же словарь
	// на устройство. Архив тоже не требует аккаунта — в нём свободные данные,
	// а вход не должен быть условием офлайн-чтения.
	mux.HandleFunc("GET /v1/define", s.getDefinition)
	mux.HandleFunc("GET /v1/dictionary", s.getDictionary)
	// Локальная библиотека работает без аккаунта, поэтому и загрузка книги
	// по ссылке не требует входа. SSRF, размер и формат проверяет remotebook.
	mux.Handle("POST /v1/library/fetch", s.bookLimit.withRateLimit(
		http.HandlerFunc(s.postRemoteBook),
	))
	// Манифест и сами пакеты публичны: проверка обновлений начинается до
	// входа в аккаунт. Целостность пакета клиент отдельно сверяет по SHA-256.
	if s.updates != nil {
		mux.HandleFunc("GET /v1/update/latest", s.updates.Latest)
		mux.HandleFunc("GET /v1/update/files/{name}", s.updates.File)
	}

	// Всё остальное — только для вошедших.
	private := http.NewServeMux()
	private.HandleFunc("GET /v1/me", s.me)
	private.HandleFunc("POST /v1/sync", s.postSync)
	private.HandleFunc("POST /v1/ocr", s.postOCR)
	private.HandleFunc("GET /v1/discovery/profile", s.getDiscoveryProfile)
	private.HandleFunc("PUT /v1/discovery/profile", s.putDiscoveryProfile)
	private.HandleFunc("GET /v1/discovery/feed", s.getDiscoveryFeed)
	private.HandleFunc("POST /v1/discovery/items/{itemId}/like", s.postDiscoveryLike)
	private.HandleFunc("POST /v1/discovery/items/{itemId}/add", s.postDiscoveryAdd)

	mux.Handle("/v1/", s.verifier.Middleware(private))

	return s.withLogging(s.withCORS(mux))
}

func (s *Server) postLogin(w http.ResponseWriter, r *http.Request) {
	s.proxyAccount(w, r, "вход", s.account.Login)
}

func (s *Server) postRegister(w http.ResponseWriter, r *http.Request) {
	s.proxyAccount(w, r, "регистрация", s.account.Register)
}

func (s *Server) postResendVerification(w http.ResponseWriter, r *http.Request) {
	s.proxyAccount(w, r, "письмо с подтверждением", s.account.ResendVerification)
}

func (s *Server) postGoogleToken(w http.ResponseWriter, r *http.Request) {
	if s.googleWebClientID == "" {
		writeJSON(w, http.StatusNotImplemented, map[string]string{"error": "вход через Google не настроен"})
		return
	}
	s.proxyAccount(w, r, "вход через Google", s.account.Google)
}

func (s *Server) postGoogleStart(w http.ResponseWriter, r *http.Request) {
	if s.google == nil || !s.google.Configured() {
		writeJSON(w, http.StatusNotImplemented, map[string]string{"error": "вход через Google не настроен"})
		return
	}
	var request social.StartRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&request); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "запрос не разобран"})
		return
	}
	authorizationURL, err := s.google.Start(request)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"authorizationUrl": authorizationURL})
}

func (s *Server) getGoogleCallback(w http.ResponseWriter, r *http.Request) {
	state := r.URL.Query().Get("state")
	if s.google == nil || state == "" {
		http.Error(w, "Запрос входа не найден.", http.StatusBadRequest)
		return
	}
	if providerError := strings.TrimSpace(r.URL.Query().Get("error")); providerError != "" {
		returnURL, err := s.google.ReturnURL(state)
		if err != nil {
			http.Error(w, "Запрос входа устарел.", http.StatusBadRequest)
			return
		}
		if s.google.IsWebReturn(returnURL) {
			redirectSocialError(w, r, returnURL, "Вход через Google отменён.")
		} else {
			writeSocialReturn(w, returnURL, http.StatusBadRequest, nil, "Вход через Google отменён.")
		}
		return
	}
	returnURL, result, err := s.google.Complete(r.Context(), r.URL.Query().Get("code"), state)
	if err != nil {
		if returnURL == "" {
			http.Error(w, "Запрос входа устарел.", http.StatusBadRequest)
			return
		}
		s.log.Warn("вход через Google не завершён", "error", err)
		if s.google.IsWebReturn(returnURL) {
			redirectSocialError(w, r, returnURL, "Не удалось завершить вход через Google.")
		} else {
			writeSocialReturn(w, returnURL, http.StatusBadGateway, nil, "Не удалось завершить вход через Google.")
		}
		return
	}
	if s.google.IsWebReturn(returnURL) {
		token, _ := cookieSessionBody(result.Body)
		if result.Status < 200 || result.Status >= 300 || token == "" {
			redirectSocialError(w, r, returnURL, "Не удалось получить сессию Wolfy.")
			return
		}
		http.SetCookie(w, sessionCookie(r, token))
		http.Redirect(w, r, returnURL, http.StatusSeeOther)
		return
	}
	writeSocialReturn(w, returnURL, result.Status, result.Body, "")
}

func (s *Server) postYandexStart(w http.ResponseWriter, r *http.Request) {
	s.proxyAccount(w, r, "вход через Яндекс", s.account.YandexStart)
}

func (s *Server) postYandexComplete(w http.ResponseWriter, r *http.Request) {
	s.proxyAccount(w, r, "вход через Яндекс", s.account.YandexComplete)
}

func (s *Server) postLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, expiredSessionCookie(r))
	w.WriteHeader(http.StatusNoContent)
}

// writeSocialReturn не кладёт сессионный токен в URL и историю браузера.
// Браузер отправляет его POST-запросом только локальному слушателю приложения.
func writeSocialReturn(w http.ResponseWriter, returnURL string, status int, payload []byte, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Вход в Wolfy</title></head><body style="font-family:system-ui,sans-serif;max-width:34rem;margin:12vh auto;padding:2rem;color:#26221d;background:#f7f2e8"><form id="result" method="post" action="`+
		html.EscapeString(returnURL)+`"><input type="hidden" name="status" value="`+strconv.Itoa(status)+`"><input type="hidden" name="payload" value="`+
		html.EscapeString(string(payload))+`"><input type="hidden" name="error" value="`+html.EscapeString(message)+`"><button type="submit" style="font:inherit;padding:.8rem 1.2rem">Вернуться в Wolfy</button></form><p>Вход завершён. Сейчас вы вернётесь в приложение.</p><script>document.getElementById('result').submit()</script></body></html>`)
}

func redirectSocialError(w http.ResponseWriter, r *http.Request, returnURL, message string) {
	target, err := url.Parse(returnURL)
	if err != nil {
		http.Error(w, message, http.StatusBadRequest)
		return
	}
	query := target.Query()
	query.Set("error", message)
	target.RawQuery = query.Encode()
	http.Redirect(w, r, target.String(), http.StatusSeeOther)
}

func wantsCookie(r *http.Request) bool {
	return strings.EqualFold(strings.TrimSpace(r.Header.Get("X-Wolfy-Session")), "cookie")
}

// cookieSessionBody вынимает токен из ответа общего аккаунта и возвращает
// тот же JSON без секрета. Браузеру достаточно httpOnly-куки; отдавать токен
// в JavaScript после этого означало бы лишить куку смысла.
func cookieSessionBody(body []byte) (string, []byte) {
	var payload map[string]any
	if json.Unmarshal(body, &payload) != nil {
		return "", body
	}
	token, _ := payload["token"].(string)
	token = strings.TrimSpace(token)
	if token == "" {
		return "", body
	}
	delete(payload, "token")
	sanitized, err := json.Marshal(payload)
	if err != nil {
		return "", body
	}
	return token, sanitized
}

func sessionCookie(r *http.Request, token string) *http.Cookie {
	return &http.Cookie{
		Name: auth.SessionCookie, Value: token, Path: "/", HttpOnly: true,
		Secure: requestIsHTTPS(r), SameSite: http.SameSiteLaxMode,
	}
}

// TLS завершается в локальном Nginx, поэтому backend видит обычный HTTP.
// X-Forwarded-Proto принимается только от loopback-прокси; прямой клиент не
// может снять или навязать cookie-флаг поддельным заголовком.
func requestIsHTTPS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	host := net.ParseIP(remoteHost(r.RemoteAddr))
	return host != nil && host.IsLoopback() &&
		strings.EqualFold(trimSpace(r.Header.Get("X-Forwarded-Proto")), "https")
}

func expiredSessionCookie(r *http.Request) *http.Cookie {
	cookie := sessionCookie(r, "")
	cookie.MaxAge = -1
	cookie.Expires = time.Unix(1, 0)
	return cookie
}

// proxyAccount передаёт запрос Читавуку и возвращает его ответ как есть.
//
// Как есть — вплоть до текста ошибки: «Аккаунт с такой почтой уже есть» и
// «Подтвердите почту по ссылке из письма» написаны там для человека, и
// переписывать их здесь значит однажды разойтись с тем, что человек увидит на
// сайте того же аккаунта.
//
// Пятисотые заменяются на 502: чужая внутренняя поломка для нашего клиента —
// это неисправность вышестоящего сервиса, а не наша.
func (s *Server) proxyAccount(
	w http.ResponseWriter,
	r *http.Request,
	what string,
	forward func(context.Context, []byte) (account.Result, error),
) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 16<<10))
	if err != nil || !json.Valid(body) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "запрос не разобран"})
		return
	}
	result, err := forward(r.Context(), body)
	if errors.Is(err, account.ErrNotConfigured) {
		writeJSON(w, http.StatusNotImplemented, map[string]string{
			"error": what + " на этом сервере не настроен",
		})
		return
	}
	if err != nil {
		s.log.Warn("Читавук недоступен", "что", what, "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"error": what + " сейчас недоступен",
		})
		return
	}
	status := result.Status
	if status >= 500 {
		status = http.StatusBadGateway
	}
	responseBody := result.Body
	if wantsCookie(r) && status >= 200 && status < 300 {
		if token, sanitized := cookieSessionBody(result.Body); token != "" {
			http.SetCookie(w, sessionCookie(r, token))
			responseBody = sanitized
		}
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(responseBody)
}

func (s *Server) getDiscoveryProfile(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	profile, err := s.discovery.Profile(r.Context(), user.ID)
	if err != nil {
		s.log.Error("профиль рекомендаций не прочитан", "error", err, "user", user.ID)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "профиль недоступен"})
		return
	}
	writeJSON(w, http.StatusOK, profile)
}

func (s *Server) putDiscoveryProfile(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	var profile store.DiscoveryProfile
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&profile); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "профиль не разобран"})
		return
	}
	if err := s.discovery.SaveProfile(r.Context(), user.ID, profile); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	profile, _ = s.discovery.Profile(r.Context(), user.ID)
	writeJSON(w, http.StatusOK, profile)
}

func (s *Server) getDiscoveryFeed(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	cursor, _ := strconv.Atoi(r.URL.Query().Get("cursor"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	page, err := s.discovery.Feed(r.Context(), user.ID, cursor, limit)
	switch {
	case errors.Is(err, discovery.ErrOnboarding):
		writeJSON(w, http.StatusPreconditionRequired, map[string]string{"error": err.Error()})
	case err != nil:
		s.log.Warn("лента недоступна", "error", err, "user", user.ID)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "лента сейчас недоступна"})
	default:
		writeJSON(w, http.StatusOK, page)
	}
}

func (s *Server) postDiscoveryLike(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	if err := s.discovery.Like(r.Context(), user.ID, r.PathValue("itemId")); err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, discovery.ErrNotFound) {
			status = http.StatusNotFound
		}
		writeJSON(w, status, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"liked": true})
}

func (s *Server) postDiscoveryAdd(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	download, err := s.discovery.DownloadAndAdd(r.Context(), user.ID, r.PathValue("itemId"))
	switch {
	case errors.Is(err, discovery.ErrNotFound):
		writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
		return
	case errors.Is(err, discovery.ErrTooLarge):
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": err.Error()})
		return
	case err != nil:
		s.log.Warn("книга не загрузилась", "error", err, "item", r.PathValue("itemId"))
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "книга сейчас не загружается"})
		return
	}
	w.Header().Set("Content-Type", "application/epub+zip")
	w.Header().Set("Content-Length", strconv.Itoa(len(download.Bytes)))
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(download.FileName))
	w.Header().Set("X-Wolfy-Title", url.QueryEscape(download.Item.Title))
	w.Header().Set("X-Wolfy-Author", url.QueryEscape(download.Item.Author))
	w.Header().Set("X-Wolfy-Source", url.QueryEscape(download.Item.ID))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(download.Bytes)
}

// health отвечает, готов ли сервис принимать запросы.
func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	if err := s.store.Healthy(r.Context()); err != nil {
		s.log.Error("база не отвечает", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"status": "unhealthy",
			"error":  "база недоступна",
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":    "ok",
		"translate": s.translate.Configured(),
		// Клиент прячет съёмку, если распознавание не настроено: кнопка,
		// которая всегда отвечает ошибкой, хуже её отсутствия.
		"ocr":        s.ocr.Configured(),
		"dictionary": s.dictionary.Configured(),
		// По этим двум клиент решает, показывать ли «Создать аккаунт» и
		// «Выслать письмо ещё раз».
		"signIn":         s.account.Configured(),
		"register":       s.account.CanRegister(),
		"resend":         s.account.CanResend(),
		"google":         s.googleWebClientID != "" && s.account.CanGoogle(),
		"googleClientId": s.googleWebClientID,
		"yandex":         s.account.CanYandexWeb(),
	})
}

func (s *Server) getDefinition(w http.ResponseWriter, r *http.Request) {
	if !s.dictionary.Configured() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "словарь не настроен"})
		return
	}
	word := strings.TrimSpace(r.URL.Query().Get("word"))
	if word == "" || len(word) > 128 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "слово не задано"})
		return
	}
	entry, ok := s.dictionary.Define(word)
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "слово не найдено"})
		return
	}
	writeJSON(w, http.StatusOK, entry)
}

func (s *Server) getDictionary(w http.ResponseWriter, r *http.Request) {
	file, err := s.dictionary.OpenArchive()
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "словарь не настроен"})
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "словарь недоступен"})
		return
	}
	w.Header().Set("Content-Type", "application/gzip")
	w.Header().Set("Content-Disposition", `attachment; filename="wolfy_dictionary.tsv.gz"`)
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	http.ServeContent(w, r, "wolfy_dictionary.tsv.gz", info.ModTime(), file)
}

func (s *Server) withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := strings.TrimRight(strings.TrimSpace(r.Header.Get("Origin")), "/")
		if s.webOrigin != "" && origin == s.webOrigin {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Wolfy-Session")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
			w.Header().Add("Vary", "Origin")
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// me возвращает пользователя, опознанного по токену Читавука. Клиент зовёт
// его при старте, чтобы понять, действителен ли ещё сохранённый вход.
func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"id":          user.ID,
		"email":       user.Email,
		"displayName": user.DisplayName,
	})
}

type translateRequest struct {
	Text    string `json:"text"`
	Context string `json:"context"`
	Source  string `json:"source"`
	Target  string `json:"target"`
}

// postTranslate переводит предложение или слово в контексте.
func (s *Server) postTranslate(w http.ResponseWriter, r *http.Request) {
	var req translateRequest
	// Ограничение на размер тела: переводим предложение, а не книгу целиком.
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "запрос не разобран"})
		return
	}

	result, err := s.translate.Translate(r.Context(), translate.Request{
		Text:    req.Text,
		Context: req.Context,
		Source:  req.Source,
		Target:  req.Target,
	})
	switch {
	case errors.Is(err, translate.ErrUnavailable):
		// 503, а не 500: это временная недоступность внешнего сервиса, и
		// клиенту стоит показать «перевод сейчас недоступен», а не ошибку
		// приложения.
		s.log.Warn("перевод недоступен", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"error": "перевод сейчас недоступен",
		})
		return
	case err != nil:
		s.log.Error("перевод не удался", "error", err)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

// postSync обменивается изменениями библиотеки.
//
// Один маршрут на отправку и получение: устройство присылает своё и свой
// курсор, получает назад чужое. Разделять на два запроса значило бы удвоить
// походы в сеть ради симметрии, которая никому не нужна.
func (s *Server) postSync(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}

	var incoming store.Changes
	// Предел на тело: библиотека читателя это десятки книг и тысячи слов.
	// Восемь мегабайт с запасом покрывают первую отправку с полной колодой.
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<20)).Decode(&incoming); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "запрос не разобран"})
		return
	}

	result, err := s.library.Sync(r.Context(), user.ID, incoming)
	switch {
	case errors.Is(err, library.ErrTooLarge):
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": err.Error()})
		return
	case err != nil:
		s.log.Error("синхронизация не удалась", "error", err, "user", user.ID)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

type ocrRequest struct {
	// Image — снимок в base64. Не multipart намеренно: всё остальное в
	// сервисе говорит на JSON, и заводить второй формат ради одного маршрута
	// значит держать в голове два способа разбора запроса.
	Image string `json:"image"`
	Mime  string `json:"mime"`
}

// postOCR распознаёт страницу бумажной книги по фотографии.
func (s *Server) postOCR(w http.ResponseWriter, r *http.Request) {
	var req ocrRequest
	// Предел с запасом на base64: он раздувает данные на треть.
	limit := int64(ocr.MaxImageBytes) * 4 / 3
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit+4096)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "снимок не разобран"})
		return
	}

	image, err := base64.StdEncoding.DecodeString(strings.TrimSpace(req.Image))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "снимок не разобран"})
		return
	}

	result, err := s.ocr.Recognize(r.Context(), image, req.Mime)
	switch {
	case errors.Is(err, ocr.ErrTooLarge):
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": err.Error()})
		return
	case errors.Is(err, ocr.ErrUnavailable):
		s.log.Warn("распознавание недоступно", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"error": "распознавание сейчас недоступно",
		})
		return
	case err != nil:
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, result)
}

// withLogging пишет строку на каждый запрос.
func (s *Server) withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}

		next.ServeHTTP(recorder, r)

		s.log.Info("запрос",
			"method", r.Method,
			"path", r.URL.Path,
			"status", recorder.status,
			"ms", time.Since(started).Milliseconds())
	})
}

// statusRecorder запоминает код ответа для лога.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
