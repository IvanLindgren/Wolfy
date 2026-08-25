package discovery

import (
	"context"
	"os"
	"testing"
	"time"
)

// Живая проверка каталога. Запускается только с WOLFY_LIVE_CATALOGUE=1:
// в обычном прогоне тесты не должны зависеть от чужого сервиса.
func TestЖивойКаталогОтвечает(t *testing.T) {
	if os.Getenv("WOLFY_LIVE_CATALOGUE") == "" {
		t.Skip("нет WOLFY_LIVE_CATALOGUE — пропускаем обращение к чужому сервису")
	}
	source := NewGutenbergSource("", 20*time.Second)
	items, err := source.Items(context.Background())
	if err != nil {
		t.Fatalf("каталог не ответил: %v", err)
	}
	t.Logf("книг в ленте: %d", len(items))
	for i, item := range items {
		if i == 3 {
			break
		}
		t.Logf("  %s — %s [%s] %s", item.Title, item.Author, item.Level, item.Genres)
	}
	if len(items) < 50 {
		t.Fatalf("лента подозрительно короткая: %d", len(items))
	}
}
