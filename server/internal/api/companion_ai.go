package api

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/companionai"
	"github.com/wolfy/server/internal/readingai"
)

// Beta-действия компаньона. Все требуют входа, ограничены по телу запроса и
// делят общий дневной лимит с остальными Beta-подсказками: квота одна на
// пользователя, обойти её через соседний эндпоинт невозможно.

type companionOpinionRequest struct {
	BookID   string `json:"bookId"`
	Title    string `json:"title"`
	Position struct {
		Chapter int `json:"chapter"`
		Offset  int `json:"offset"`
	} `json:"position"`
	PageText  string `json:"pageText"`
	Memory    string `json:"memory"`
	Companion struct {
		Name        string         `json:"name"`
		Locale      string         `json:"locale"`
		Personality map[string]int `json:"personality"`
		MBTI        string         `json:"mbti"`
		Description string         `json:"description"`
	} `json:"companion"`
}

type companionQuestionRequest struct {
	BookID   string `json:"bookId"`
	Title    string `json:"title"`
	Position struct {
		Chapter int `json:"chapter"`
		Offset  int `json:"offset"`
	} `json:"position"`
	Question  string `json:"question"`
	Context   string `json:"context"`
	Memory    string `json:"memory"`
	Companion struct {
		Name        string         `json:"name"`
		Locale      string         `json:"locale"`
		Personality map[string]int `json:"personality"`
		MBTI        string         `json:"mbti"`
		Description string         `json:"description"`
	} `json:"companion"`
}

type companionPackRequest struct {
	Profile json.RawMessage `json:"profile"`
	Locale  string          `json:"locale"`
}

func (s *Server) postAICompanionOpinion(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	var request companionOpinionRequest
	if decodeCompanionBody(w, r, 16<<10, &request) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "страница не разобрана"})
		return
	}
	opinion := companionai.OpinionRequest{
		BookID:   request.BookID,
		Title:    request.Title,
		PageText: request.PageText,
		Memory:   request.Memory,
		Companion: companionai.PersonaIn{
			Name:        request.Companion.Name,
			Locale:      request.Companion.Locale,
			Personality: request.Companion.Personality,
			MBTI:        request.Companion.MBTI,
			Description: request.Companion.Description,
		},
	}
	opinion.Position.Chapter, opinion.Position.Offset = request.Position.Chapter, request.Position.Offset
	result, err := s.companionAI.Opinion(r.Context(), user.ID, opinion)
	s.writeAI(w, result, err)
}

func (s *Server) postAICompanionQuestion(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	var request companionQuestionRequest
	if decodeCompanionBody(w, r, 48<<10, &request) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "вопрос не разобран"})
		return
	}
	question := companionai.QuestionRequest{
		BookID:   request.BookID,
		Title:    request.Title,
		Question: request.Question,
		Context:  request.Context,
		Memory:   request.Memory,
		Companion: companionai.PersonaIn{
			Name:        request.Companion.Name,
			Locale:      request.Companion.Locale,
			Personality: request.Companion.Personality,
			MBTI:        request.Companion.MBTI,
			Description: request.Companion.Description,
		},
	}
	question.Position.Chapter, question.Position.Offset = request.Position.Chapter, request.Position.Offset
	result, err := s.companionAI.Question(r.Context(), user.ID, question)
	s.writeAI(w, result, err)
}

func (s *Server) postAICompanionPack(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	var request companionPackRequest
	if decodeCompanionBody(w, r, 32<<10, &request) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "профиль не разобран"})
		return
	}
	result, err := s.companionAI.Pack(r.Context(), user.ID, companionai.PackRequest{
		Profile: request.Profile,
		Locale:  request.Locale,
	})
	if err == nil && result.Cached {
		// Кэш по тому же характеру бесплатен: leftover квоты не трогаем,
		// но и сообщение об ошибке не нужно.
		writeJSON(w, http.StatusOK, result)
		return
	}
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, result)
	case err == readingai.ErrLimit:
		writeJSON(w, http.StatusTooManyRequests, map[string]string{
			"error": readingai.LimitMessage(),
			"code":  "quota",
		})
	case err == readingai.ErrInvalid:
		writeJSON(w, http.StatusBadGateway, map[string]string{
			"error": "Не удалось собрать набор реплик. Приложение продолжит работать с базовым набором.",
			"code":  "invalid_answer",
		})
	default:
		s.writeAI(w, nil, err)
	}
}

// decodeCompanionBody запрещает неизвестные поля и второй JSON-объект. Так
// клиенты не могут незаметно разойтись с контрактом Beta-эндпоинтов.
func decodeCompanionBody(w http.ResponseWriter, r *http.Request, limit int64, target any) error {
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		if err != nil {
			return err
		}
		return io.ErrUnexpectedEOF
	}
	return nil
}
