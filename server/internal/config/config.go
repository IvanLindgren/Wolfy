// Package config собирает настройки сервиса из переменных окружения.
//
// Всё, что отличает разработку от продакшена, живёт здесь и больше нигде:
// адрес базы, ключи внешних сервисов, порт. Код за пределами этого пакета
// в окружение не заглядывает — иначе однажды окажется, что настройка читается
// в трёх местах и в двух из них по-другому.
package config

import (
	"fmt"
	"os"
	"strings"
	"time"
)

// Config — полный набор настроек сервиса.
type Config struct {
	// Addr — что слушать, например ":8080".
	Addr string
	// Env — "dev" или "prod". Влияет только на подробность логов.
	Env string

	// DatabaseURL — подключение к Postgres. В разработке это контейнер из
	// docker-compose, в продакшене — тот же инстанс, что у Читавука.
	DatabaseURL string

	// DeepLKey — ключ контекстного перевода. Пустой ключ не ломает сервис:
	// перевод просто отвечает понятной ошибкой, а чтение и разбор слов
	// работают дальше, потому что считаются на устройстве.
	DeepLKey string
	// DeepLURL — endpoint DeepL. Бесплатный и платный различаются доменом.
	DeepLURL string

	// OCR* — распознавание страницы по фото через OpenAI-совместимого
	// провайдера. Пустой ключ прячет съёмку в приложении.
	OCRKey   string
	OCRModel string
	OCRURL   string

	// RequestTimeout — сколько сервис готов ждать ответа от внешнего API.
	// Клиент не должен ждать дольше, чем ему обещано.
	RequestTimeout time.Duration
}

// Load читает настройки и проверяет обязательные.
func Load() (Config, error) {
	cfg := Config{
		Addr:        envOr("WOLFY_ADDR", ":8080"),
		Env:         envOr("WOLFY_ENV", "dev"),
		DatabaseURL: strings.TrimSpace(os.Getenv("WOLFY_DB_URL")),

		DeepLKey: strings.TrimSpace(os.Getenv("DEEPL_API_KEY")),
		DeepLURL: envOr("WOLFY_DEEPL_URL", "https://api-free.deepl.com/v2/translate"),

		OCRKey:   strings.TrimSpace(os.Getenv("WOLFY_OCR_KEY")),
		OCRModel: envOr("WOLFY_OCR_MODEL", "google/gemini-3.7-flash"),
		OCRURL:   envOr("WOLFY_OCR_URL", "https://api.polza.ai/api/v1/chat/completions"),

		RequestTimeout: 20 * time.Second,
	}

	// База — единственное, без чего сервис бесполезен: в ней и аккаунты, и
	// синхронизация, и кэш переводов.
	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("не задан WOLFY_DB_URL: без базы сервис работать не может")
	}
	return cfg, nil
}

// Development — включён ли режим разработки.
func (c Config) Development() bool {
	return c.Env != "prod"
}

func envOr(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}
