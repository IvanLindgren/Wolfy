package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/store"
)

// getPractice отдаёт per-device состояния тренировки.
//
// Go не мерджит — только хранит и отдаёт. Клиент скармливает каждый
// component Rust по одному через `MergePractice` (days union + max per device).
func (s *Server) getPractice(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}

	components, err := s.store.ListPracticeComponents(r.Context(), user.ID)
	if err != nil {
		s.log.Error("состояния тренировки не прочитаны", "error", err, "user", user.ID)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "тренировка сейчас недоступна"})
		return
	}
	if components == nil {
		components = []store.PracticeComponent{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"components": components,
	})
}

// putPractice сохраняет один per-device blob.
//
// Поддерживает два формата по §7 переходного периода:
//
//  1. PUT /v1/practice/{deviceId}  body = practice_json (прямо объект)
//  2. PUT /v1/practice             body = {"deviceId":"...","practice":{...}}
//
// Оба — opaque: сервер не сравнивает дни/счётчики, а только валидирует
// форму и размер и делает UPSERT по (user_id, device_id).
func (s *Server) putPractice(w http.ResponseWriter, r *http.Request) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
		return
	}

	// deviceId из пути имеет приоритет: PUT /v1/practice/{deviceId}
	deviceID := strings.TrimSpace(r.PathValue("deviceId"))
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, int64(store.MaxPracticeBytes+16<<10)))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "тело не разобрано"})
		return
	}
	raw = []byte(strings.TrimSpace(string(raw)))
	if len(raw) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "тело пустое"})
		return
	}

	var practice json.RawMessage
	if deviceID != "" {
		// Путь содержит deviceId, тело — practice JSON напрямую.
		if !json.Valid(raw) {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "practice не JSON"})
			return
		}
		practice = json.RawMessage(raw)
	} else {
		// Без пути — ожидаем обёртку {deviceId, practice}.
		var wrapper struct {
			DeviceID string          `json:"deviceId"`
			Practice json.RawMessage `json:"practice"`
		}
		if err := json.Unmarshal(raw, &wrapper); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "запрос не разобран"})
			return
		}
		deviceID = strings.TrimSpace(wrapper.DeviceID)
		practice = wrapper.Practice
		// Обёртка может содержать practice как объект; если wrapper.Practice пуст,
		// попробуем трактовать весь raw как practice при наличии deviceId в query?
		if deviceID == "" {
			// fallback: ?device=...
			deviceID = strings.TrimSpace(r.URL.Query().Get("device"))
		}
		if deviceID == "" || len(practice) == 0 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "deviceId и practice обязательны"})
			return
		}
		if !json.Valid(practice) {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "practice не JSON"})
			return
		}
	}

	if err := s.store.SavePractice(r.Context(), user.ID, deviceID, practice); err != nil {
		switch {
		case errors.Is(err, store.ErrPracticeTooLarge):
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": err.Error()})
			return
		case errors.Is(err, store.ErrTooManyDevices):
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": err.Error()})
			return
		}
		// Валидация deviceId / JSON — 400, остальное — 503.
		msg := err.Error()
		if strings.Contains(msg, "deviceId") || strings.Contains(msg, "practice") || strings.Contains(msg, "JSON") {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": msg})
			return
		}
		s.log.Error("состояние тренировки не сохранено", "error", err, "user", user.ID)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "тренировка сейчас не сохраняется"})
		return
	}

	// Отвечаем сохранённым — клиент может сразу пройтись merge без доп. GET.
	writeJSON(w, http.StatusOK, map[string]any{
		"deviceId": deviceID,
		"practice": practice,
	})
}
