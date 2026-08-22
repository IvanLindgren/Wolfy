// Package api — транспортный слой: маршруты, middleware, разбор запроса и
// сериализация ответа.
//
// Здесь нет SQL и нет решений о том, что считать правильным ответом. Задача
// пакета — принять HTTP, позвать нужный слой и вернуть JSON.
package api

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/library"
	"github.com/wolfy/server/internal/store"
	"github.com/wolfy/server/internal/translate"
)

// Server связывает слои и раздаёт маршруты.
type Server struct {
	store     *store.Store
	verifier  *auth.Verifier
	translate *translate.Service
	library   *library.Service
	log       *slog.Logger
}

func NewServer(
	s *store.Store,
	v *auth.Verifier,
	t *translate.Service,
	l *library.Service,
	log *slog.Logger,
) *Server {
	return &Server{store: s, verifier: v, translate: t, library: l, log: log}
}

// Handler собирает маршруты сервиса.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	// Проверка живости. Без авторизации намеренно: её дёргает балансировщик,
	// у которого токена нет и быть не может.
	mux.HandleFunc("GET /healthz", s.health)

	// Всё остальное — только для вошедших.
	private := http.NewServeMux()
	private.HandleFunc("GET /v1/me", s.me)
	private.HandleFunc("POST /v1/translate", s.postTranslate)
	private.HandleFunc("POST /v1/sync", s.postSync)

	mux.Handle("/v1/", s.verifier.Middleware(private))

	return s.withLogging(mux)
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
