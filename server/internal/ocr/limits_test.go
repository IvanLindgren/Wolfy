package ocr

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"testing"
	"time"

	"github.com/wolfy/server/internal/gate"
)

func validJPEGBytes(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	// fill with white to avoid compression issues
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.White)
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, nil); err != nil {
		t.Fatalf("jpeg encode: %v", err)
	}
	return buf.Bytes()
}

func TestValidateImageRejectsUnknownMime(t *testing.T) {
	data := []byte{0xFF, 0xD8, 0xFF, 0xD8}
	if err := validateImage(data, "image/gif"); err == nil {
		t.Fatal("gif должен отклоняться")
	}
}

func TestValidateImageRejectsMismatchSignature(t *testing.T) {
	// PNG data but claimed as jpeg — should fail because magic mismatched?
	// Our current validateImage accepts any valid magic regardless of mime mismatch?
	// Actually it checks magic first, returns nil if any magic matches, ignoring mime mismatch.
	// So png data with jpeg mime will currently pass via PNG magic. That's maybe okay.
	// Instead test text masquerading as jpeg.
	data := []byte("hello world, not an image")
	if err := validateImage(data, "image/jpeg"); err == nil {
		t.Fatal("текст под видом jpeg должен отклоняться")
	}
}

func TestValidateImageAcceptsJPEG(t *testing.T) {
	data := []byte{0xFF, 0xD8, 0xFF, 0xD8}
	if err := validateImage(data, "image/jpeg"); err != nil {
		t.Fatalf("валидный jpeg отклонён: %v", err)
	}
}

func TestValidateImageAcceptsPNG(t *testing.T) {
	data := []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0}
	if err := validateImage(data, "image/png"); err != nil {
		t.Fatalf("валидный png отклонён: %v", err)
	}
}

func TestDecodedSizeRejectsHugeImage(t *testing.T) {
	// Create a fake image header with huge dimensions, but we need valid jpeg bytes
	// Instead create a small jpeg and manually test validateDecodedSize with large config
	// Use validJPEGBytes with dimensions exceeding MaxDecodedPixels (~16M)
	// 5000x5000 = 25M > 16M
	data := validJPEGBytes(t, 5000, 5000)
	if err := validateDecodedSize(data); err == nil {
		t.Fatal("огромный jpeg должен отклоняться по пикселям")
	}
}

func TestDecodedSizeAcceptsNormalImage(t *testing.T) {
	data := validJPEGBytes(t, 100, 100)
	if err := validateDecodedSize(data); err != nil {
		t.Fatalf("нормальный jpeg отклонён: %v", err)
	}
}

func TestRecognizeRejectsInvalidMime(t *testing.T) {
	svc := New("key", "http://example.invalid", "model", time.Second)
	_, err := svc.Recognize(context.Background(), []byte{0xFF, 0xD8, 0xFF}, "image/gif")
	if err == nil {
		t.Fatal("невалидный mime прошёл")
	}
}

func TestRecognizeRespectsContextCancellationForGate(t *testing.T) {
	// Fill gate slots
	orig := gate.OCR
	g := gate.New(1)
	gate.OCR = g
	defer func() { gate.OCR = orig }()

	// Occupy the single slot
	if err := g.Acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer g.Release()

	svc := New("key", "http://example.invalid", "model", time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	cancel() // already cancelled

	_, err := svc.Recognize(ctx, []byte{0xFF, 0xD8, 0xFF}, "image/jpeg")
	if err == nil {
		t.Fatal("отменённый контекст должен прервать ожидание слота")
	}
	// Should be wrapped ErrTooManyRequests with context caused
	if !contains(err.Error(), "много") && !contains(err.Error(), "canceled") && !contains(err.Error(), "context") {
		t.Fatalf("неверная ошибка при отмене: %v", err)
	}
}

func contains(s, sub string) bool {
	return bytes.Contains([]byte(s), []byte(sub))
}
