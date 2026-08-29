// Package bookfiles хранит личные EPUB/PDF вне JSON-синхронизации.
package bookfiles

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/wolfy/server/internal/store"
)

const MaxBookBytes int64 = 256 << 20

var uuid = regexp.MustCompile(`^[0-9a-fA-F-]{36}$`)

var (
	ErrNotFound = errors.New("файл книги не найден")
	ErrInvalid  = errors.New("некорректный файл книги")
)

type Service struct {
	store *store.Store
	root  string
}

func New(s *store.Store, root string) *Service {
	return &Service{store: s, root: root}
}

// paths — где лежат байты книги.
//
// Ключ хранения, а не номер книги. Номер книги изменчив: при совпадении
// source_key синхронизация переводит книгу на канонический номер, и всё, что
// было привязано к старому, переезжает. Файл переехать не может - его
// переименование не откатится вместе с транзакцией, - поэтому переезжает
// ссылка: `wolfy.book_files.storage_key` остаётся прежним, а book_id в той же
// строке меняется. Собери путь из book_id, и после первой же перепривязки
// сервер искал бы файл там, где его нет.
func (s *Service) paths(userID, storageKey string) (final, temporary string, err error) {
	if !uuid.MatchString(userID) || !uuid.MatchString(storageKey) {
		return "", "", ErrInvalid
	}
	dir := filepath.Join(s.root, strings.ToLower(userID))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", "", fmt.Errorf("каталог книг: %w", err)
	}
	final = filepath.Join(dir, strings.ToLower(storageKey)+".book")
	return final, final + ".part", nil
}

func safeName(name string) string {
	name = filepath.Base(strings.TrimSpace(name))
	name = strings.Map(func(r rune) rune {
		if r == '.' || r == '-' || r == '_' || r == ' ' || r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' {
			return r
		}
		return '_'
	}, name)
	if name == "" || name == "." {
		return "book.epub"
	}
	return name
}

// PutChunk дописывает очередную часть. Когда приходит последняя, временный
// файл сверяется по SHA-256 и атомарно становится доступным второму устройству.
func (s *Service) PutChunk(ctx context.Context, userID, bookID, name, expectedHash string, body io.Reader, length, offset, total int64) error {
	if length <= 0 || length > 2<<20 || total <= 0 || total > MaxBookBytes || offset < 0 || offset+length > total {
		return ErrInvalid
	}
	var ownsBook bool
	if err := s.store.Pool.QueryRow(ctx, `SELECT EXISTS (
        SELECT 1 FROM wolfy.books WHERE user_id=$1::uuid AND id=$2::uuid AND deleted_at IS NULL
    )`, userID, bookID).Scan(&ownsBook); err != nil || !ownsBook {
		return ErrNotFound
	}
	final, temporary, err := s.paths(userID, bookID)
	if err != nil {
		return err
	}
	flags := os.O_CREATE | os.O_WRONLY
	if offset == 0 {
		flags |= os.O_TRUNC
	} else {
		flags |= os.O_APPEND
	}
	if offset > 0 {
		if info, err := os.Stat(temporary); err != nil || info.Size() != offset {
			return ErrInvalid
		}
	}
	output, err := os.OpenFile(temporary, flags, 0o600)
	if err != nil {
		return fmt.Errorf("открытие файла: %w", err)
	}
	written, copyErr := io.Copy(output, io.LimitReader(body, length+1))
	syncErr := output.Sync()
	closeErr := output.Close()
	if copyErr != nil || syncErr != nil || closeErr != nil || written != length {
		_ = os.Remove(temporary)
		return ErrInvalid
	}
	if offset+written != total {
		return nil
	}
	// Метаданные должны описывать весь файл, а не последний чанк. На
	// промежуточных чанках written — это размер только этой части, поэтому
	// размер для записи берём из объявленного total: он уже сверен с offset.
	if info, err := os.Stat(temporary); err != nil || info.Size() != total {
		_ = os.Remove(temporary)
		return ErrInvalid
	}
	input, err := os.Open(temporary)
	if err != nil {
		return ErrInvalid
	}
	hash := sha256.New()
	_, copyHashErr := io.Copy(hash, input)
	// Файл надо закрыть до переименования: на Windows rename поверх
	// открытого описателя падает, и файл так и остался бы .part.
	closeErr = input.Close()
	if copyHashErr != nil || closeErr != nil {
		return ErrInvalid
	}
	digest := hex.EncodeToString(hash.Sum(nil))
	if expectedHash != "" && !strings.EqualFold(expectedHash, digest) {
		_ = os.Remove(temporary)
		return ErrInvalid
	}
	if err := os.Rename(temporary, final); err != nil {
		return fmt.Errorf("фиксация файла: %w", err)
	}
	// Заливка всегда кладёт байты под номер книги и им же назначает ключ
	// хранения. Прежний ключ мог отличаться - книгу перепривязали, а потом
	// залили заново, - и тогда старый файл больше никому не нужен.
	var previous string
	_ = s.store.Pool.QueryRow(ctx,
		`SELECT storage_key::text FROM wolfy.book_files WHERE user_id=$1::uuid AND book_id=$2::uuid`,
		userID, bookID).Scan(&previous)

	if _, err = s.store.Pool.Exec(ctx, `
        INSERT INTO wolfy.book_files (user_id, book_id, storage_key, file_name, size_bytes, sha256)
        VALUES ($1::uuid, $2::uuid, $2::uuid, $3, $4, $5)
        ON CONFLICT (user_id, book_id) DO UPDATE SET
          storage_key=EXCLUDED.storage_key, file_name=EXCLUDED.file_name,
          size_bytes=EXCLUDED.size_bytes, sha256=EXCLUDED.sha256,
          updated_at=now()`, userID, bookID, safeName(name), total, digest); err != nil {
		return err
	}
	if previous != "" && !strings.EqualFold(previous, bookID) {
		if stale, staleTemporary, pathErr := s.paths(userID, previous); pathErr == nil {
			_ = os.Remove(stale)
			_ = os.Remove(staleTemporary)
		}
	}
	return nil
}

func (s *Service) Open(ctx context.Context, userID, bookID string) (*os.File, store.BookFile, error) {
	var info store.BookFile
	var storageKey string
	err := s.store.Pool.QueryRow(ctx, `
        SELECT book_id::text, file_name, size_bytes, sha256, storage_key::text
        FROM wolfy.book_files WHERE user_id=$1::uuid AND book_id=$2::uuid`, userID, bookID).
		Scan(&info.BookID, &info.FileName, &info.Size, &info.SHA256, &storageKey)
	if err != nil {
		return nil, store.BookFile{}, ErrNotFound
	}
	final, _, err := s.paths(userID, storageKey)
	if err != nil {
		return nil, store.BookFile{}, err
	}
	file, err := os.Open(final)
	if err != nil {
		return nil, store.BookFile{}, ErrNotFound
	}
	return file, info, nil
}

func (s *Service) List(ctx context.Context, userID string) ([]store.BookFile, error) {
	rows, err := s.store.Pool.Query(ctx, `SELECT book_id::text, file_name, size_bytes, sha256 FROM wolfy.book_files WHERE user_id=$1::uuid`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	files := make([]store.BookFile, 0)
	for rows.Next() {
		var item store.BookFile
		if err := rows.Scan(&item.BookID, &item.FileName, &item.Size, &item.SHA256); err != nil {
			return nil, err
		}
		files = append(files, item)
	}
	return files, rows.Err()
}

func (s *Service) Delete(ctx context.Context, userID, bookID string) error {
	// Убирать надо тот файл, на который смотрит строка, а не тот, что назван
	// номером книги: после перепривязки это разные файлы.
	storageKey := bookID
	_ = s.store.Pool.QueryRow(ctx,
		`SELECT storage_key::text FROM wolfy.book_files WHERE user_id=$1::uuid AND book_id=$2::uuid`,
		userID, bookID).Scan(&storageKey)

	final, temporary, err := s.paths(userID, storageKey)
	if err != nil {
		return err
	}
	_ = os.Remove(final)
	_ = os.Remove(temporary)
	_, err = s.store.Pool.Exec(ctx, `DELETE FROM wolfy.book_files WHERE user_id=$1::uuid AND book_id=$2::uuid`, userID, bookID)
	return err
}
