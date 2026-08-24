package translate

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestTooLargeTextRejectedBeforeExternal(t *testing.T) {
	service := New(nil, "key", "http://example.invalid", time.Second)
	large := strings.Repeat("a", MaxTextRunes+1)
	_, err := service.Translate(context.Background(), Request{Text: large, Target: "RU"})
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("ожидалась ErrTooLarge для текста, получено %v", err)
	}
}

func TestTooLargeContextRejected(t *testing.T) {
	service := New(nil, "key", "http://example.invalid", time.Second)
	largeCtx := strings.Repeat("b", MaxContextRunes+1)
	_, err := service.Translate(context.Background(), Request{Text: "hello", Context: largeCtx, Target: "RU"})
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("ожидалась ErrTooLarge для контекста, получено %v", err)
	}
}

func TestTextAtLimitPassesValidation(t *testing.T) {
	// Граничный размер не должен отклоняться до проверки кэша; без ключа
	// сервис вернёт ErrUnavailable после кэша, а не ErrTooLarge.
	service := New(nil, "", "http://example.invalid", time.Second)
	text := strings.Repeat("a", MaxTextRunes)
	_, err := service.Translate(context.Background(), Request{Text: text, Target: "RU"})
	if errors.Is(err, ErrTooLarge) {
		t.Fatalf("текст на границе ошибочно отклонён: %v", err)
	}
	if !errors.Is(err, ErrUnavailable) {
		// Ожидаем ErrUnavailable из-за отсутствия ключа, а не TooLarge.
		t.Fatalf("ожидалась ErrUnavailable, получено %v", err)
	}
}

func TestContextAtLimitPasses(t *testing.T) {
	service := New(nil, "", "http://example.invalid", time.Second)
	ctx := strings.Repeat("c", MaxContextRunes)
	_, err := service.Translate(context.Background(), Request{Text: "hi", Context: ctx, Target: "RU"})
	if errors.Is(err, ErrTooLarge) {
		t.Fatalf("контекст на границе ошибочно отклонён: %v", err)
	}
}

func TestUnicodeRunesCounted(t *testing.T) {
	service := New(nil, "key", "http://example.invalid", time.Second)
	// Каждый emoji — один rune, но 4 байта. Проверка должна быть по runes.
	large := strings.Repeat("😀", MaxTextRunes+1)
	_, err := service.Translate(context.Background(), Request{Text: large, Target: "RU"})
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("юникод не посчитан по runes: %v", err)
	}
}
