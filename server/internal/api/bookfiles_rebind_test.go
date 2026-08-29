package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"testing"
	"time"

	"github.com/wolfy/server/internal/bookfiles"
	"github.com/wolfy/server/internal/store"
)

// Книга, залитая на одном устройстве, остаётся доступной второму даже после
// того, как синхронизация свела два номера в один.
//
// Два устройства офлайн заводят одну и ту же книгу и дают ей разные случайные
// номера. Совпавший source_key сводит их вместе: победитель забирает карточки,
// заметки и устройства проигравшего. Файл раньше не забирал никто - путь к
// нему собирался из номера книги, а номер поменялся, - и серверная копия
// пропадала: второе устройство книгу не находило, а залитый файл оставался
// лежать под номером, которого больше нет.
func TestФайлКнигиПереживаетСхлопываниеНомеров(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	st, err := store.Open(ctx, bookFilesDatabaseURL(t))
	if err != nil {
		t.Fatalf("база не открылась: %v", err)
	}
	t.Cleanup(st.Close)

	files := bookfiles.New(st, t.TempDir())
	user := newUUID(t)
	phoneBook := newUUID(t)
	laptopBook := newUUID(t)
	sourceKey := "rebind-" + newUUID(t)

	t.Cleanup(func() {
		clean := context.Background()
		_, _ = st.Pool.Exec(clean, `DELETE FROM wolfy.book_files WHERE user_id=$1::uuid`, user)
		_, _ = st.Pool.Exec(clean, `DELETE FROM wolfy.books WHERE user_id=$1::uuid`, user)
		_, _ = st.Pool.Exec(clean, `DELETE FROM wolfy.user_state WHERE user_id=$1::uuid`, user)
	})

	book := func(id string) store.Book {
		return store.Book{ID: id, Title: "Одна и та же книга", SourceKey: sourceKey, Format: "epub", ChapterCount: 3, Rev: 1}
	}

	// Телефон завёл книгу и залил файл.
	if _, err := st.Sync(ctx, user, store.Changes{Books: []store.Book{book(phoneBook)}}); err != nil {
		t.Fatalf("синхронизация телефона: %v", err)
	}
	body := bytes.Repeat([]byte("глава про волка\n"), 64)
	digest := sha256.Sum256(body)
	if err := files.PutChunk(ctx, user, phoneBook, "wolf.epub", hex.EncodeToString(digest[:]),
		bytes.NewReader(body), int64(len(body)), 0, int64(len(body))); err != nil {
		t.Fatalf("заливка файла с телефона: %v", err)
	}
	fresh, _, err := files.Open(ctx, user, phoneBook)
	if err != nil {
		t.Fatalf("файл не читается сразу после заливки: %v", err)
	}
	// Закрыть обязательно: на Windows открытый описатель не даёт удалить файл,
	// и уборка временного каталога споткнулась бы о него.
	fresh.Close()

	// Ноутбук был офлайн и завёл ту же книгу под своим номером.
	if _, err := st.Sync(ctx, user, store.Changes{Books: []store.Book{book(laptopBook)}}); err != nil {
		t.Fatalf("синхронизация ноутбука: %v", err)
	}

	// Кто из двух номеров выжил, решает сервер. Спрашиваем у него.
	seen, err := st.Sync(ctx, user, store.Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("наблюдатель: %v", err)
	}
	winner := ""
	for _, item := range seen.Books {
		if !item.Deleted && item.SourceKey == sourceKey {
			winner = item.ID
		}
	}
	if winner == "" {
		t.Fatalf("после схлопывания не осталось живой книги: %+v", seen.Books)
	}
	if winner == phoneBook {
		t.Skip("схлопывание оставило номер телефона: перепривязки файла не было")
	}

	// Главное: файл доступен под тем номером, под которым книга живёт теперь.
	handle, info, err := files.Open(ctx, user, winner)
	if err != nil {
		t.Fatalf("файл потерялся при схлопывании номеров: %v", err)
	}
	defer handle.Close()
	got, err := io.ReadAll(handle)
	if err != nil {
		t.Fatalf("файл не читается: %v", err)
	}
	if !bytes.Equal(got, body) {
		t.Fatalf("содержимое файла изменилось: %d байт вместо %d", len(got), len(body))
	}
	if info.SHA256 != hex.EncodeToString(digest[:]) {
		t.Errorf("отпечаток файла разошёлся: %s", info.SHA256)
	}

	// Строка не должна остаться и под старым номером: иначе список файлов
	// показывал бы устройству книгу, которой у него уже нет.
	listed, err := files.List(ctx, user)
	if err != nil {
		t.Fatalf("список файлов: %v", err)
	}
	if len(listed) != 1 || listed[0].BookID != winner {
		t.Fatalf("список файлов не сошёлся с книгами: %+v", listed)
	}
}
