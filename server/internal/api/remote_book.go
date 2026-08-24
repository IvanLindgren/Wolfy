package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/wolfy/server/internal/remotebook"
)

// postRemoteBook скачивает поддерживаемую книгу с публичного HTTPS-адреса.
// Ответ остаётся бинарным: base64 увеличил бы и файл, и пиковую память ещё на
// треть как раз на маршруте, где документы бывают десятками мегабайт.
func (s *Server) postRemoteBook(w http.ResponseWriter, r *http.Request) {
	var request struct {
		URL string `json:"url"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil || strings.TrimSpace(request.URL) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "HTTPS-ссылка на книгу не задана"})
		return
	}

	download, err := s.remoteBooks.Fetch(r.Context(), request.URL)
	switch {
	case errors.Is(err, remotebook.ErrUnsafeURL):
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": remotebook.ErrUnsafeURL.Error()})
		return
	case errors.Is(err, remotebook.ErrTooLarge):
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": remotebook.ErrTooLarge.Error()})
		return
	case errors.Is(err, remotebook.ErrUnsupported):
		writeJSON(w, http.StatusUnsupportedMediaType, map[string]string{"error": remotebook.ErrUnsupported.Error()})
		return
	case errors.Is(err, remotebook.ErrBusy):
		w.Header().Set("Retry-After", "2")
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": remotebook.ErrBusy.Error()})
		return
	case err != nil:
		s.log.Warn("книга по ссылке не загрузилась", "error", err)
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "Книгу по этой ссылке сейчас не удалось скачать"})
		return
	}

	w.Header().Set("Content-Type", download.ContentType)
	w.Header().Set("Content-Length", stringInt(len(download.Bytes)))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(download.FileName))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(download.Bytes)
}

func stringInt(value int) string {
	// strconv.Itoa в отдельной крошечной функции оставляет обработчик легче
	// читать и не даёт форматированию случайно добавить пробелы в заголовок.
	return strconv.Itoa(value)
}
