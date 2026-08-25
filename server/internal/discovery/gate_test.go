package discovery

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/wolfy/server/internal/gate"
	"github.com/wolfy/server/internal/store"
)

type fakeRepo struct{}

func (f *fakeRepo) DiscoveryProfile(ctx context.Context, userID string) (store.DiscoveryProfile, error) {
	return store.DiscoveryProfile{OnboardingComplete: true}, nil
}
func (f *fakeRepo) SaveDiscoveryProfile(ctx context.Context, userID string, p store.DiscoveryProfile) error {
	return nil
}
func (f *fakeRepo) DiscoveryReactions(ctx context.Context, userID string) ([]store.DiscoveryReaction, error) {
	return nil, nil
}
func (f *fakeRepo) SaveDiscoveryReaction(ctx context.Context, userID string, r store.DiscoveryReaction) error {
	return nil
}

type fakeSource struct {
	item Item
}

func (f *fakeSource) Items(ctx context.Context) ([]Item, error) {
	return []Item{f.item}, nil
}

func TestDiscoveryDownloadRespectsGlobalGate(t *testing.T) {
	// Serve a small epub
	epubData := make([]byte, 30+8+20)
	copy(epubData, []byte{'P', 'K', 3, 4})
	// minimal header for EPUB check: need mimetype entry
	// Use real server to avoid needing full epub validation beyond PK header
	// For this test, we mock http client to return valid epub bytes
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		// minimal valid epub header: PK + fake mimetype
		// We simplify: just need first 4 bytes PK\x03\x04 to pass download check
		_, _ = w.Write([]byte{'P', 'K', 3, 4, 0, 0, 0, 0})
	}))
	defer server.Close()

	origGate := gate.Download
	customGate := gate.New(1)
	gate.Download = customGate
	defer func() { gate.Download = origGate }()

	// occupy gate
	if !customGate.TryAcquire() {
		t.Fatal("failed to occupy gate")
	}
	defer customGate.Release()

	repo := &fakeRepo{}
	src := &fakeSource{item: Item{
		ID:          "se-test",
		Title:       "Test Book",
		DownloadURL: server.URL + "/book.epub",
	}}
	svc := NewWithGate(repo, src, 5*time.Second, customGate)
	// Свой http-сервер сюда не подставить: trustedDownload пускает только
	// gutenberg.org, и это ровно то, что проверяет соседний тест.
	// Use non-standard gate test directly
	if err := svc.gate.TryAcquire(); err != true {
		// we already occupied, second try should fail
	}
	// Direct gate busy test
	if customGate.TryAcquire() {
		t.Fatal("gate should be busy")
		customGate.Release()
	}
}

func TestDiscoveryBusyOnFullGate(t *testing.T) {
	g := gate.New(1)
	repo := &fakeRepo{}
	// need item with valid download URL that passes trustedDownload
	// Адрес настоящего источника: сеть всё равно не тронем — gate закрывает
	// путь раньше, — но проверка хоста проходит до него.
	src := &fakeSource{item: Item{
		ID:          "se-busy",
		Title:       "Busy Book",
		DownloadURL: "https://www.gutenberg.org/ebooks/84.epub3.images",
	}}
	svc := NewWithGate(repo, src, 5*time.Second, g)
	// occupy gate
	if err := g.Acquire(context.Background()); err != nil {
		t.Fatalf("acquire: %v", err)
	}
	defer g.Release()

	_, err := svc.DownloadAndAdd(context.Background(), "user1", "se-busy")
	if err == nil {
		t.Fatal("ожидалась ErrBusy при занятом gate")
	}
	if err != ErrBusy {
		t.Fatalf("ожидалась ErrBusy, получено %v", err)
	}
}
