package api

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/readingai"
)

type phraseAIRequest struct {
	Phrase  string `json:"phrase"`
	Context string `json:"context"`
}
type recapAIRequest struct {
	Title   string `json:"title"`
	Excerpt string `json:"excerpt"`
}

func (s *Server) postAIPhrase(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	var request phraseAIRequest
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&request) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "фраза не разобрана"})
		return
	}
	result, err := s.readingAI.Phrase(r.Context(), user.ID, request.Phrase, request.Context)
	s.writeAI(w, result, err)
}

func (s *Server) postAIRecap(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	var request recapAIRequest
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 24<<10)).Decode(&request) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "фрагмент не разобран"})
		return
	}
	result, err := s.readingAI.Recap(r.Context(), user.ID, request.Title, request.Excerpt)
	s.writeAI(w, result, err)
}

func (s *Server) writeAI(w http.ResponseWriter, result any, err error) {
	var provider *readingai.ProviderError
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, result)
	case errors.Is(err, readingai.ErrLimit):
		writeJSON(w, http.StatusTooManyRequests, map[string]string{
			"error": "Лимит Beta: до 10 запросов в день.",
			"code":  "quota",
		})
	case errors.Is(err, readingai.ErrInvalid):
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "Не удалось проверить ответ ИИ. Попробуйте другой фрагмент.",
			"code":  "invalid_answer",
		})
	case errors.As(err, &provider):
		// Вместо одной «недоступно» — разные причины и честные коды: клиент
		// по ним показывает читателю конкретную подсказку.
		switch provider.Kind {
		case readingai.FailKey:
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "Провайдер не принял ключ сервера.", "code": "key"})
		case readingai.FailModel:
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "Модель ИИ сейчас недоступна.", "code": "model"})
		case readingai.FailLimit:
			writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "Провайдер ИИ ограничил запросы.", "code": "limit"})
		case readingai.FailTimeout:
			writeJSON(w, http.StatusGatewayTimeout, map[string]string{"error": "Gemini не успел ответить. Попробуйте ещё раз.", "code": "timeout"})
		case readingai.FailBadJSON:
			writeJSON(w, http.StatusBadGateway, map[string]string{"error": "Ответ ИИ не подошёл по формату.", "code": "badjson"})
		default:
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "Gemini сейчас не ответил.", "code": "provider"})
		}
	default:
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "Beta-подсказка сейчас недоступна."})
	}
}
