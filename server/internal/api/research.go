package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/researchai"
)

type researchStartRequest struct {
	SourceSHA256    string `json:"sourceSha256"`
	AnalysisVersion string `json:"analysisVersion"`
	SourceProtocol  string `json:"sourceProtocol"`
	RequestID       string `json:"requestId"`
}

func (s *Server) researchUser(w http.ResponseWriter, r *http.Request) (auth.User, bool) {
	user, ok := auth.FromContext(r.Context())
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "нужен вход"})
	}
	if !ok || s.researchAI == nil {
		if ok {
			writeJSON(w, http.StatusNotImplemented, map[string]string{"error": "Исследование книг пока не включено."})
		}
		return auth.User{}, false
	}
	return user, true
}

func (s *Server) postResearchStart(w http.ResponseWriter, r *http.Request) {
	user, ok := s.researchUser(w, r)
	if !ok {
		return
	}
	var req researchStartRequest
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&req) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Не удалось прочитать параметры исследования."})
		return
	}
	result, err := s.researchAI.Start(r.Context(), user.ID, r.PathValue("bookId"), req.SourceSHA256, req.AnalysisVersion, req.SourceProtocol, req.RequestID)
	s.writeResearch(w, result, err)
}

func (s *Server) getResearchStatus(w http.ResponseWriter, r *http.Request) {
	user, ok := s.researchUser(w, r)
	if !ok {
		return
	}
	value, err := s.researchAI.Status(r.Context(), user.ID, r.PathValue("analysisId"))
	if err == nil && value.BookID != r.PathValue("bookId") {
		err = researchai.ErrNotFound
	}
	s.writeResearch(w, value, err)
}

func (s *Server) putResearchSource(w http.ResponseWriter, r *http.Request) {
	user, ok := s.researchUser(w, r)
	if !ok {
		return
	}
	status, err := s.researchAI.Status(r.Context(), user.ID, r.PathValue("analysisId"))
	if err == nil && status.BookID != r.PathValue("bookId") {
		err = researchai.ErrNotFound
	}
	if err != nil {
		s.writeResearch(w, nil, err)
		return
	}
	index, err := strconv.Atoi(r.PathValue("index"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Неверный номер части текста."})
		return
	}
	if r.Header.Get("X-Wolfy-Research-Source-Protocol") != researchai.SourceProtocol {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Версия источника не совпадает."})
		return
	}
	if r.ContentLength <= 0 || r.ContentLength > 1<<20 {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "Часть текста слишком велика."})
		return
	}
	err = s.researchAI.PutChunk(r.Context(), user.ID, r.PathValue("analysisId"), index, r.Header.Get("X-Wolfy-Chunk-SHA256"), r.Body, r.ContentLength)
	if err == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	s.writeResearch(w, nil, err)
}

func (s *Server) postResearchComplete(w http.ResponseWriter, r *http.Request) {
	user, ok := s.researchUser(w, r)
	if !ok {
		return
	}
	var req researchai.SourceComplete
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&req) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Не удалось проверить источник книги."})
		return
	}
	value, err := s.researchAI.Complete(r.Context(), user.ID, r.PathValue("analysisId"), req)
	if err == nil && value.BookID != r.PathValue("bookId") {
		err = researchai.ErrNotFound
	}
	s.writeResearch(w, value, err)
}

func (s *Server) getResearchArtifact(w http.ResponseWriter, r *http.Request) {
	user, ok := s.researchUser(w, r)
	if !ok {
		return
	}
	status, err := s.researchAI.Status(r.Context(), user.ID, r.PathValue("analysisId"))
	if err == nil && status.BookID != r.PathValue("bookId") {
		err = researchai.ErrNotFound
	}
	if err != nil {
		s.writeResearch(w, nil, err)
		return
	}
	artifact, etag, err := s.researchAI.Artifact(r.Context(), user.ID, status.ID)
	if err != nil {
		s.writeResearch(w, nil, err)
		return
	}
	tag := `"` + etag + `"`
	w.Header().Set("ETag", tag)
	w.Header().Set("Cache-Control", "private, max-age=0, must-revalidate")
	if strings.TrimSpace(r.Header.Get("If-None-Match")) == tag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	writeJSON(w, http.StatusOK, artifact)
}

func (s *Server) getResearchState(w http.ResponseWriter, r *http.Request) {
	user, ok := s.researchUser(w, r)
	if !ok {
		return
	}
	status, err := s.researchAI.Status(r.Context(), user.ID, r.PathValue("analysisId"))
	if err == nil && status.BookID != r.PathValue("bookId") {
		err = researchai.ErrNotFound
	}
	if err != nil {
		s.writeResearch(w, nil, err)
		return
	}
	state, err := s.researchAI.GetState(r.Context(), user.ID, status.ID)
	s.writeResearch(w, state, err)
}

func (s *Server) putResearchState(w http.ResponseWriter, r *http.Request) {
	user, ok := s.researchUser(w, r)
	if !ok {
		return
	}
	status, err := s.researchAI.Status(r.Context(), user.ID, r.PathValue("analysisId"))
	if err == nil && status.BookID != r.PathValue("bookId") {
		err = researchai.ErrNotFound
	}
	if err != nil {
		s.writeResearch(w, nil, err)
		return
	}
	var state researchai.UserState
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&state) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Состояние исследования не разобрано."})
		return
	}
	value, err := s.researchAI.PutState(r.Context(), user.ID, status.ID, state)
	s.writeResearch(w, value, err)
}

func (s *Server) writeResearch(w http.ResponseWriter, value any, err error) {
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, value)
	case errors.Is(err, researchai.ErrLimit):
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "На этой неделе доступно до 2 исследований."})
	case errors.Is(err, researchai.ErrNotFound):
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "Исследование или книга не найдены."})
	case errors.Is(err, researchai.ErrConflict):
		writeJSON(w, http.StatusConflict, map[string]string{"error": "Исследование уже обновляется. Попробуйте чуть позже."})
	case errors.Is(err, researchai.ErrInvalid):
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Источник или ответ ИИ не прошёл проверку."})
	default:
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "Исследование книг сейчас недоступно."})
	}
}
