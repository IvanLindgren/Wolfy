// Package library — синхронизация библиотеки между устройствами.
//
// Модель простая и намеренно не «умная»: у каждого пользователя монотонный
// счётчик ревизий, каждая изменённая запись получает номер, а устройство
// просит «всё, что новее моего курсора». Ни векторных часов, ни слияния
// текстов — потому что сливать здесь нечего: книга и карточка это набор полей,
// и две версии одной карточки различаются тем, какая записана позже.
//
// Обмен идёт одним запросом в обе стороны. Устройство присылает свои изменения
// и свой курсор, получает назад чужие. Разделять на «отправить» и «получить»
// значило бы удвоить число походов в сеть ради симметрии, которая никому не
// нужна.
package library

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"github.com/wolfy/server/internal/store"
)

// ErrTooLarge — отправка не влезает в разумные пределы.
var ErrTooLarge = errors.New("слишком большая отправка")

// Пределы одной отправки.
//
// Библиотека читателя — это десятки книг и тысячи слов, а не миллионы. Предел
// защищает не от злого умысла, а от ошибки в клиенте: цикл, отправляющий одну
// и ту же книгу, найдётся быстрее, если сервер откажет, чем если он молча
// примет двести тысяч записей.
const (
	MaxBooks = 2_000
	MaxCards = 50_000
	// Длина контекста: предложение, а не глава.
	MaxTextLength = 4_000
)

// uuidPattern — идентификаторы записей.
//
// Номер книги придумывает устройство, а не сервер, и это осознанно: книга
// должна получить номер до того, как впервые дойдёт до сети, иначе её нельзя
// открыть в самолёте. Раз номер приходит снаружи, его форма проверяется — иначе
// в базе окажется что угодно, а колонка там uuid.
var uuidPattern = regexp.MustCompile(
	`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// Service — синхронизация библиотеки.
type Service struct {
	store *store.Store
}

func New(s *store.Store) *Service {
	return &Service{store: s}
}

// Sync принимает изменения устройства и возвращает чужие.
//
// Порядок именно такой: сначала записываем присланное, потом читаем всё, что
// новее курсора. Тогда устройство получает назад и свои записи — уже с
// присвоенными ревизиями, — и ему не нужно гадать, дошли ли они.
func (s *Service) Sync(ctx context.Context, userID string, incoming store.Changes) (store.Changes, error) {
	if err := validate(incoming); err != nil {
		return store.Changes{}, err
	}

	if _, err := s.store.Apply(ctx, userID, incoming); err != nil {
		return store.Changes{}, err
	}

	books, err := s.store.BooksSince(ctx, userID, incoming.Cursor)
	if err != nil {
		return store.Changes{}, err
	}
	cards, err := s.store.CardsSince(ctx, userID, incoming.Cursor)
	if err != nil {
		return store.Changes{}, err
	}
	reading, err := s.store.Reading(ctx, userID)
	if err != nil {
		return store.Changes{}, err
	}
	cursor, err := s.store.CurrentRev(ctx, userID)
	if err != nil {
		return store.Changes{}, err
	}

	return store.Changes{
		Cursor:  cursor,
		Books:   books,
		Cards:   cards,
		Reading: reading,
	}, nil
}

func validate(changes store.Changes) error {
	if len(changes.Books) > MaxBooks {
		return fmt.Errorf("%w: книг %d, предел %d", ErrTooLarge, len(changes.Books), MaxBooks)
	}
	if len(changes.Cards) > MaxCards {
		return fmt.Errorf("%w: карточек %d, предел %d", ErrTooLarge, len(changes.Cards), MaxCards)
	}
	if changes.Cursor < 0 {
		return errors.New("курсор не может быть отрицательным")
	}

	for _, book := range changes.Books {
		if !uuidPattern.MatchString(book.ID) {
			return fmt.Errorf("книга с непонятным номером: %q", book.ID)
		}
		if len(book.Title) > MaxTextLength || len(book.Author) > MaxTextLength {
			return fmt.Errorf("%w: слишком длинное название или автор", ErrTooLarge)
		}
	}

	for _, card := range changes.Cards {
		if !uuidPattern.MatchString(card.ID) {
			return fmt.Errorf("карточка с непонятным номером: %q", card.ID)
		}
		// Пустая книга допустима — это карточка из общей колоды.
		if card.BookID != "" && !uuidPattern.MatchString(card.BookID) {
			return fmt.Errorf("карточка ссылается на непонятную книгу: %q", card.BookID)
		}
		if kind := strings.TrimSpace(card.Kind); kind != "" && kind != "word" && kind != "phrase" {
			return fmt.Errorf("неизвестный вид карточки: %q", card.Kind)
		}
		if len(card.Context) > MaxTextLength {
			return fmt.Errorf("%w: контекст длиннее предложения", ErrTooLarge)
		}
	}

	if len(changes.Reading) > 0 && !json.Valid(changes.Reading) {
		return errors.New("настройки чтения не разобраны")
	}
	return nil
}
