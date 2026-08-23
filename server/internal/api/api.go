// Package api — транспортный слой: маршруты, middleware, разбор запроса и
// сериализация ответа.
//
// Здесь нет SQL и нет решений о том, что считать правильным ответом. Задача
// пакета — принять HTTP, позвать нужный слой и вернуть JSON.
package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/wolfy/server/internal/account"
	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/discovery"
	"github.com/wolfy/server/internal/library"
	"github.com/wolfy/server/internal/ocr"
	"github.com/wolfy/server/internal/store"
	"github.com/wolfy/server/internal/translate"
)

// Server связывает слои и раздаёт маршруты.
type Server struct {
	store     *store.Store
	verifier  *auth.Verifier
	translate *translate.Service
	library   *library.Service
	ocr       *ocr.Service
	account   *account.Service
	discovery *discovery.Service
	log       *slog.Logger

	// Ограничитель для открытого перевода — см. Handler.
	translateLimit *rateLimiter
}

func NewServer(
	s *store.Store,
	v *auth.Verifier,
	t *translate.Service,
	l *library.Service,
	o *ocr.Service,
	a *account.Service,
	d *discovery.Service,
	log *slog.Logger,
) *Server {
	return &Server{
		store: s, verifier: v, translate: t, library: l, ocr: o,
		account: a, discovery: d, log: log,
		// Двести переводов залпом и один в секунду сверху: страница книги
		// редко даёт больше двухсот незнакомых слов, а секунда — это дольше,
		// чем читатель успевает выбрать следующее слово, но много быстрее,
		// чем перебор словаря скриптом.
		translateLimit: newRateLimiter(200, 1, 30*time.Minute),
	}
}

// Handler собирает маршруты сервиса.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Проверка живости. Без авторизации намеренно: её дёргает балансировщик,
	// у которого токена нет и быть не может.
	mux.HandleFunc("GET /healthz", s.health)
	// Вход публичный по определению: токен как раз получается этим запросом.
	mux.HandleFunc("POST /v1/auth/login", s.postLogin)

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

	return s.withLogging(mux)
}

func (s *Server) postLogin(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 16<<10))
	if err != nil || !json.Valid(body) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "запрос не разобран"})
		return
	}
	result, err := s.account.Login(r.Context(), body)
	if err != nil {
		s.log.Warn("вход через Читавук недоступен", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "вход сейчас недоступен"})
		return
	}
	status := result.Status
	if status >= 500 {
		status = http.StatusBadGateway
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(result.Body)
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
		"ocr": s.ocr.Configured(),
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
	Text   string `json:"text"`
	Source string `json:"source"`
	Target string `json:"target"`
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
		Text:   req.Text,
		Source: req.Source,
		Target: req.Target,
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
