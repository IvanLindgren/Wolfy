package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"

	"github.com/wolfy/server/internal/annotations"
	"github.com/wolfy/server/internal/auth"
)

// Заметки живут по номеру книги, который придумывает устройство: uuid на всех
// платформах. Шаблон проверяет форму, а не существование — заметки к книге,
// которой ещё нет на сервере, законны: книга приходит синхронизацией позже.
var bookIDPattern = regexp.MustCompile(`^[A-Za-z0-9-]{8,64}$`)

// Устройство и его подтверждение приходят параметрами запроса на обоих
// маршрутах. Подтверждение — поколение серверного снимка, которое устройство
// долговечно сохранило: без него сервер не сможет собирать пометки удалений,
// не рискуя их воскрешением на старых копиях.
func syncParams(r *http.Request) (device string, seenGeneration int64, ok bool) {
	device = r.URL.Query().Get("device")
	if !bookIDPattern.MatchString(device) {
		return "", 0, false
	}
	seen, err := strconv.ParseInt(r.URL.Query().Get("seen"), 10, 64)
	if err != nil || seen < 0 {
		return "", 0, false
	}
	return device, seen, true
}

// getBookAnnotations отдаёт сохранённый список отметок книги.
func (s *Server) getBookAnnotations(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	bookID := r.PathValue("bookId")
	if !bookIDPattern.MatchString(bookID) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "номер книги не разобран"})
		return
	}
	device, seen, ok := syncParams(r)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "устройство не представилось"})
		return
	}

	items, generation, err := s.store.BookAnnotationsSync(r.Context(), user.ID, bookID, device, seen)
	if err != nil {
		s.log.Error("заметки книги не прочитаны", "error", err, "user", user.ID)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "Заметки сейчас недоступны"})
		return
	}
	if items == nil {
		items = []annotations.Item{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items":      items,
		"generation": generation,
	})
}

// putBookAnnotations принимает список отметок целиком и возвращает слитый.
//
// Клиент шлёт весь список, а не разницу: отметок у книги десятки, а не
// тысячи, и протокол с курсорами стоил бы дороже экономии в три килобайта.
// Потолок списка один на обе стороны: всё, что сервер выдаёт, клиент обязан
// мочь отправить обратно.
func (s *Server) putBookAnnotations(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}
	bookID := r.PathValue("bookId")
	if !bookIDPattern.MatchString(bookID) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "номер книги не разобран"})
		return
	}
	device, seen, ok := syncParams(r)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "устройство не представилось"})
		return
	}

	var request struct {
		Items []annotations.Item `json:"items"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<20)).Decode(&request); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "список заметок не разобран"})
		return
	}
	items, err := annotations.Normalize(request.Items)
	if err != nil {
		switch {
		case errors.Is(err, annotations.ErrTooMany):
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": err.Error()})
		default:
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		}
		return
	}

	merged, generation, err := s.store.SaveBookAnnotations(r.Context(), user.ID, bookID, device, seen, items)
	switch {
	case errors.Is(err, annotations.ErrTooMany):
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": err.Error()})
		return
	case err != nil:
		s.log.Error("заметки книги не сохранены", "error", err, "user", user.ID)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "Заметки сейчас не сохраняются"})
		return
	}
	if merged == nil {
		merged = []annotations.Item{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items":      merged,
		"generation": generation,
	})
}
