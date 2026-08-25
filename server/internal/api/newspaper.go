package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/wolfy/server/internal/newspaper"
)

// getNewspaper отдаёт свежий номер.
//
// Разделы приходят списком через запятую; пустой список — весь номер.
// Читатель, который ничего не выбирал, должен увидеть газету, а не
// приглашение сперва настроить газету.
func (s *Server) getNewspaper(w http.ResponseWriter, r *http.Request) {
	topics := splitTopics(r.URL.Query().Get("topics"))

	limit, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || limit <= 0 {
		limit = 6
	}

	issue, err := s.newspaper.Issue(r.Context(), topics, limit)
	switch {
	case err == nil:
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		writeJSON(w, http.StatusRequestTimeout, map[string]string{"error": "запрос отменён"})
		return
	default:
		s.log.Warn("номер газеты не собрался", "error", err)
		writeJSON(w, http.StatusBadGateway, map[string]string{
			"error": newspaper.ErrUnavailable.Error(),
		})
		return
	}

	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{
		"date":     issue.Date,
		"sections": issue.Sections,
		"topics":   newspaper.Topics,
	})
}

// postNewspaperArticle достаёт полный текст заметки для читалки.
//
// Адрес приходит от клиента, но проверяется по списку наших источников:
// без этого маршрут стал бы открытым прокси, которым можно ходить куда
// угодно от имени сервера.
func (s *Server) postNewspaperArticle(w http.ResponseWriter, r *http.Request) {
	var request struct {
		URL string `json:"url"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil || strings.TrimSpace(request.URL) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "ссылка на заметку не задана"})
		return
	}

	reading, err := s.newspaper.Read(r.Context(), request.URL)
	switch {
	case err == nil:
	case errors.Is(err, newspaper.ErrUnknownHost):
		writeJSON(w, http.StatusForbidden, map[string]string{"error": err.Error()})
		return
	case errors.Is(err, newspaper.ErrEmpty):
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": err.Error()})
		return
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		writeJSON(w, http.StatusRequestTimeout, map[string]string{"error": "запрос отменён"})
		return
	default:
		s.log.Warn("заметка не открылась", "error", err)
		writeJSON(w, http.StatusBadGateway, map[string]string{
			"error": newspaper.ErrUnavailable.Error(),
		})
		return
	}

	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, reading)
}

// splitTopics разбирает `world,sport` в список кодов.
//
// Лишние пробелы и пустые куски выбрасываются здесь, а не в службе: служба
// не должна знать, что коды пришли одной строкой из адреса.
func splitTopics(raw string) []string {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	topics := make([]string, 0, len(parts))
	for _, part := range parts {
		if code := strings.TrimSpace(part); code != "" {
			topics = append(topics, code)
		}
		// Больше десятка разделов не бывает; длинный список — это не читатель,
		// а перебор.
		if len(topics) >= 16 {
			break
		}
	}
	return topics
}
