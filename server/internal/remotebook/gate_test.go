package remotebook

import (
	"context"
	"testing"

	"github.com/wolfy/server/internal/gate"
)

func TestFetchRespectsGlobalGate(t *testing.T) {
	g := gate.New(1)
	// occupy
	if err := g.Acquire(context.Background()); err != nil {
		t.Fatalf("acquire: %v", err)
	}
	defer g.Release()

	svc := &Service{
		http: nil,
		gate: g,
	}
	_, err := svc.Fetch(context.Background(), "https://books.example/book.pdf")
	if err == nil {
		t.Fatal("ожидалась ErrBusy при занятом gate")
	}
	if err != ErrBusy {
		t.Fatalf("ожидалась ErrBusy, получено %v", err)
	}
}

func TestFetchRespectsContextCancellationBeforeGate(t *testing.T) {
	g := gate.New(1)
	// occupy to make next Fetch block on gate (TryAcquire fails immediately)
	_ = g.Acquire(context.Background())
	defer g.Release()

	svc := &Service{gate: g}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := svc.Fetch(ctx, "https://books.example/book.pdf")
	if err == nil {
		t.Fatal("отменённый контекст должен прервать Fetch")
	}
	// Should be context.Canceled
	if err != context.Canceled {
		t.Fatalf("ожидался context.Canceled, получено %v", err)
	}
}
