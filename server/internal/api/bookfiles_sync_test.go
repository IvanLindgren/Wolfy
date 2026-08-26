package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/wolfy/server/internal/auth"
	"github.com/wolfy/server/internal/bookfiles"
	"github.com/wolfy/server/internal/store"
)

// bookFilesDatabaseURL — адрес базы для интеграционных тестов файлов книг.
//
// Схема и обработчики работают на настоящем Postgres; без базы тесты
// пропускаются, как и в пакете store.
func bookFilesDatabaseURL(t *testing.T) string {
	t.Helper()
	url := os.Getenv("WOLFY_TEST_DB_URL")
	if url == "" {
		url = os.Getenv("WOLFY_DB_URL")
	}
	if url == "" {
		t.Skip("нет WOLFY_TEST_DB_URL — пропускаем тесты с базой (docker compose up -d)")
	}
	return url
}

type bookFileFixture struct {
	srv    *Server
	userID string
	bookID string
	root   string
	total  int64
	body   []byte
	digest string
}

func newBookFileFixture(t *testing.T) *bookFileFixture {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	st, err := store.Open(ctx, bookFilesDatabaseURL(t))
	if err != nil {
		t.Fatalf("база не открылась: %v", err)
	}
	t.Cleanup(st.Close)

	root := t.TempDir()
	fx := &bookFileFixture{srv: &Server{store: st, bookFiles: bookfiles.New(st, root), log: slog.Default()}, root: root}
	fx.userID = newUUID(t)
	fx.bookID = newUUID(t)

	// Владелец книги нужен только как строка в wolfy.books: ownership
	// проверяется перед приёмом любого чанка.
	if _, err := st.Pool.Exec(ctx,
		`INSERT INTO wolfy.books (id, user_id, title) VALUES ($1::uuid, $2::uuid, 'тест')`,
		fx.bookID, fx.userID); err != nil {
		t.Fatalf("книга не создалась: %v", err)
	}
	t.Cleanup(func() {
		if _, err := st.Pool.Exec(context.Background(), `DELETE FROM wolfy.book_files WHERE user_id=$1::uuid`, fx.userID); err != nil {
			t.Logf("cleanup файлов не прошёл: %v", err)
		}
	})
	t.Cleanup(func() {
		if _, err := st.Pool.Exec(context.Background(), `DELETE FROM wolfy.books WHERE id=$1::uuid`, fx.bookID); err != nil {
			t.Logf("cleanup книги не прошёл: %v", err)
		}
	})

	const mega = int64(1 << 20)
	fx.total = 3*mega + mega/2 // 3.5 MiB — гарантированно больше одного чанка
	fx.body = make([]byte, fx.total)
	if _, err := rand.Read(fx.body); err != nil {
		t.Fatalf("тело файла не сгенерировалось: %v", err)
	}
	sum := sha256.Sum256(fx.body)
	fx.digest = hex.EncodeToString(sum[:])
	return fx
}

// putChunk отправляет один чанк с честным хешем всего файла.
func (fx *bookFileFixture) putChunk(t *testing.T, offset, length int64) *httptest.ResponseRecorder {
	return fx.putChunkFinalHash(t, offset, length, fx.digest)
}

// putChunkFinalHash отправляет один чанк; на последнем чанке именно этот
// заголовок сверяется с SHA-256 уже собранного файла.
func (fx *bookFileFixture) putChunkFinalHash(t *testing.T, offset, length int64, hash string) *httptest.ResponseRecorder {
	t.Helper()
	chunk := fx.body[offset : offset+length]
	req := httptest.NewRequest(http.MethodPut, "/v1/books/"+fx.bookID+"/file", bytes.NewReader(chunk))
	req.SetPathValue("bookId", fx.bookID)
	req.Header.Set("X-Wolfy-Offset", strconv.FormatInt(offset, 10))
	req.Header.Set("X-Wolfy-Total", strconv.FormatInt(fx.total, 10))
	req.Header.Set("X-Wolfy-File-Name", "роман.epub")
	req.Header.Set("X-Wolfy-SHA256", hash)
	req = req.WithContext(auth.WithUser(req.Context(), auth.User{ID: fx.userID}))
	w := httptest.NewRecorder()
	fx.srv.putBookFile(w, req)
	return w
}

// uploadAll льёт файл чанками по мегабайту; в последний подставляется hash,
// если тот задан.
func (fx *bookFileFixture) uploadAll(t *testing.T, lastHash string) []*httptest.ResponseRecorder {
	t.Helper()
	const mega = int64(1 << 20)
	responses := make([]*httptest.ResponseRecorder, 0, fx.total/mega+1)
	for offset := int64(0); offset < fx.total; {
		length := min(mega, fx.total-offset)
		hash := fx.digest
		if lastHash != "" && offset+length == fx.total {
			hash = lastHash
		}
		responses = append(responses, fx.putChunkFinalHash(t, offset, length, hash))
		offset += length
	}
	return responses
}

func TestКнигаБольшеЧанкаСохраняетПолныйРазмерИSHA(t *testing.T) {
	fx := newBookFileFixture(t)

	for i, w := range fx.uploadAll(t, "") {
		if w.Code != http.StatusNoContent && w.Code != http.StatusOK {
			t.Fatalf("чанк %d не принят: %d, %s", i, w.Code, w.Body.String())
		}
	}

	files := fx.listFiles(t)
	if len(files) != 1 {
		t.Fatalf("в списке %d файлов, ждали один", len(files))
	}
	file := files[0]
	if file.BookID != fx.bookID {
		t.Fatalf("файл вернулся для другой книги: %s", file.BookID)
	}
	// Регрессия P0: раньше здесь оказывался размер последнего чанка.
	if file.Size != fx.total {
		t.Fatalf("size_bytes = %d, а должен быть полный размер %d", file.Size, fx.total)
	}
	if !equalFold(file.SHA256, fx.digest) {
		t.Fatalf("sha256 в метаданных не совпал")
	}

	final := filepath.Join(fx.root, fx.userID, fx.bookID+".book")
	info, err := os.Stat(final)
	if err != nil || info.Size() != fx.total {
		t.Fatalf("финальный файл на диске неправильного размера: %v", err)
	}
}

func TestСкачиваниеПоДиапазонамСобираетФайлССоответствующимSHA(t *testing.T) {
	fx := newBookFileFixture(t)
	for i, w := range fx.uploadAll(t, "") {
		if w.Code >= http.StatusBadRequest {
			t.Fatalf("чанк %d отклонён: %d, %s", i, w.Code, w.Body.String())
		}
	}

	// Второе устройство листает список и качает строго до size из метаданных.
	files := fx.listFiles(t)
	var size int64
	for _, f := range files {
		if f.BookID == fx.bookID {
			size = f.Size
		}
	}
	if size != fx.total {
		t.Fatalf("размер из списка %d, ждали %d", size, fx.total)
	}

	reassembled := make([]byte, 0, size)
	const rangeBytes = int64(512 * 1024)
	for offset := int64(0); offset < size; offset += rangeBytes {
		end := min(offset+rangeBytes-1, size-1)
		reassembled = append(reassembled, fx.getRange(t, offset, end)...)
	}
	sum := sha256.Sum256(reassembled)
	if hex.EncodeToString(sum[:]) != fx.digest {
		t.Fatal("собранный после скачивания файл не совпал по SHA-256 с оригиналом")
	}
}

func TestНеСходящийсяSHAНаФинишеНеОставляетНиФайлаНиМетаданных(t *testing.T) {
	fx := newBookFileFixture(t)

	// Последний чанк с неверным хешем отвергается целиком.
	wrong := "0000000000000000000000000000000000000000000000000000000000000000"
	responses := fx.uploadAll(t, wrong)
	for i, w := range responses[:len(responses)-1] {
		if w.Code >= http.StatusBadRequest {
			t.Fatalf("чанк %d до финала был отвергнут: %d", i, w.Code)
		}
	}
	if w := responses[len(responses)-1]; w.Code == http.StatusNoContent || w.Code == http.StatusOK {
		t.Fatal("неверный хеш был принят как успешная фиксация")
	}
	if files := fx.listFiles(t); len(files) != 0 {
		t.Fatalf("метаданные появились после отказа: %+v", files)
	}
	if final := filepath.Join(fx.root, fx.userID, fx.bookID+".book"); exists(final) {
		t.Fatal("финальный файл остался после отказа проверки хеша")
	}
	if part := filepath.Join(fx.root, fx.userID, fx.bookID+".book.part"); exists(part) {
		t.Fatal("временный .part остался после отказа проверки хеша")
	}

	// Повторная честная загрузка тем же путём должна пройти целиком.
	for i, w := range fx.uploadAll(t, "") {
		if w.Code == http.StatusNoContent || w.Code == http.StatusOK {
			continue
		}
		t.Fatalf("повторная загрузка сорвалась на чанке %d: %d, %s", i, w.Code, w.Body.String())
	}
	if files := fx.listFiles(t); len(files) != 1 {
		t.Fatal("после повторной честной загрузки метаданных нет")
	}
}

func (fx *bookFileFixture) getRange(t *testing.T, start, end int64) []byte {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/v1/books/"+fx.bookID+"/file", http.NoBody)
	req.SetPathValue("bookId", fx.bookID)
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
	req = req.WithContext(auth.WithUser(req.Context(), auth.User{ID: fx.userID}))
	w := httptest.NewRecorder()
	fx.srv.getBookFile(w, req)
	if w.Code != http.StatusPartialContent {
		t.Fatalf("диапазон %d-%d не отдан частично: %d", start, end, w.Code)
	}
	body, err := io.ReadAll(w.Result().Body)
	if err != nil {
		t.Fatalf("диапазон не дочитан: %v", err)
	}
	if int64(len(body)) != end-start+1 {
		t.Fatalf("диапазон %d-%d пришёл длиной %d", start, end, len(body))
	}
	return body
}

func (fx *bookFileFixture) listFiles(t *testing.T) []bookFileInfo {
	t.Helper()
	files, err := fx.srv.bookFiles.List(context.Background(), fx.userID)
	if err != nil {
		t.Fatalf("список файлов не прочитался: %v", err)
	}
	out := make([]bookFileInfo, 0, len(files))
	for _, f := range files {
		out = append(out, bookFileInfo{BookID: f.BookID, Size: f.Size, SHA256: f.SHA256})
	}
	return out
}

type bookFileInfo struct {
	BookID string
	Size   int64
	SHA256 string
}

func equalFold(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := 0; i < len(a); i++ {
		ca, cb := a[i]|0x20, b[i]|0x20
		if ca != cb {
			return false
		}
	}
	return true
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func newUUID(t *testing.T) string {
	t.Helper()
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		t.Fatalf("uuid не сгенерировался: %v", err)
	}
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	h := hex.EncodeToString(raw[:])
	return fmt.Sprintf("%s-%s-%s-%s-%s", h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])
}
