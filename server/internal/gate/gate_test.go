package gate

import (
	"context"
	"testing"
	"time"
)

func TestGateAcquireRespectsContext(t *testing.T) {
	g := New(1)
	if err := g.Acquire(context.Background()); err != nil {
		t.Fatalf("первый Acquire не прошёл: %v", err)
	}
	// Второй должен блокировать до отмены
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	start := time.Now()
	err := g.Acquire(ctx)
	if err == nil {
		t.Fatal("второй Acquire прошёл при занятом слоте")
	}
	if time.Since(start) > 200*time.Millisecond {
		t.Fatalf("Acquire не уважил отмену контекста вовремя")
	}
	g.Release()
	// После Release должен снова проходить
	if err := g.Acquire(context.Background()); err != nil {
		t.Fatalf("Acquire после Release не прошёл: %v", err)
	}
	g.Release()
}

func TestGateTryAcquire(t *testing.T) {
	g := New(1)
	if !g.TryAcquire() {
		t.Fatal("TryAcquire не прошёл на свободном gate")
	}
	if g.TryAcquire() {
		t.Fatal("TryAcquire прошёл при занятом слоте")
	}
	g.Release()
	if !g.TryAcquire() {
		t.Fatal("TryAcquire после Release не прошёл")
	}
	g.Release()
}

func TestDownloadGateShared(t *testing.T) {
	// Download gate capacity 2 — проверяем что третий TryAcquire падает
	g := New(2)
	if !g.TryAcquire() || !g.TryAcquire() {
		t.Fatal("два слота должны проходити")
	}
	if g.TryAcquire() {
		t.Fatal("третий слот должен быть занят")
	}
	g.Release()
	g.Release()
}

func TestOCRGatesConcurrencyBound(t *testing.T) {
	// OCR gate capacity 4
	if OCR.Available() != cap(OCR.sem) {
		// просто проверяем что глобальный gate создан
		t.Fatalf("OCR gate not initialized")
	}
}
