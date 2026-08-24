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
	"path/filepath"
	"strconv"
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

	// Читавук выдаёт общую сессию и заводит аккаунты. Каждый адрес
	// настраивается отдельно: сервис, поднятый только ради чтения, вправе
	// уметь вход и не уметь регистрацию. Пустой адрес прячет кнопку в
	// приложении — кнопка, которая всегда отвечает ошибкой, хуже её
	// отсутствия.
	CitavukLoginURL          string
	CitavukRegisterURL       string
	CitavukResendURL         string
	CitavukGoogleURL         string
	CitavukYandexStartURL    string
	CitavukYandexCompleteURL string
	// Старый Читавук всегда возвращает web-flow на citavuk.ru. Флаг можно
	// включить только после установки companion-патча trusted returnUrl.
	CitavukYandexWebReturn bool

	// GoogleWebClientID — публичный client_id Google Identity Services. Секрет
	// браузерному потоку не нужен: ID token проверяет общий сервер Читавука.
	GoogleWebClientID string
	// Code+PKCE-поток ниже остаётся для установленных клиентов.
	GoogleClientID     string
	GoogleClientSecret string
	GoogleCallbackURL  string
	OAuthStateSecret   string
	// WebOrigin — единственный origin SPA, которому разрешены credentialed
	// CORS и OAuth-возврат. Пустое значение оставляет только same-origin.
	WebOrigin string

	// StandardEbooks* — официальный Atom-каталог. Открытая лента новых
	// релизов работает без учётных данных; полный /all требует разрешения
	// Standard Ebooks и Basic Auth.
	StandardEbooksFeedURL string
	StandardEbooksUser    string
	StandardEbooksPass    string

	// DictionaryPath — распакованный TSV. Рядом обязан лежать файл .tsv.gz,
	// который сервер отдаёт устройствам для офлайн-установки.
	DictionaryPath string

	// ReleasesPath — каталог опубликованных MSI и APK. Сервер строит манифест
	// непосредственно по его содержимому, поэтому выпуск новой версии не
	// требует править конфиг или перезапускать процесс.
	ReleasesPath string

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

		CitavukLoginURL:          envOr("WOLFY_CITAVUK_LOGIN_URL", "https://api.citavuk.ru/v1/auth/login"),
		CitavukRegisterURL:       envOr("WOLFY_CITAVUK_REGISTER_URL", "https://api.citavuk.ru/v1/auth/register"),
		CitavukResendURL:         envOr("WOLFY_CITAVUK_RESEND_URL", "https://api.citavuk.ru/v1/auth/resend-verification"),
		CitavukGoogleURL:         envOr("WOLFY_CITAVUK_GOOGLE_URL", "https://api.citavuk.ru/v1/auth/google"),
		CitavukYandexStartURL:    envOr("WOLFY_CITAVUK_YANDEX_START_URL", "https://api.citavuk.ru/v1/auth/yandex/start"),
		CitavukYandexCompleteURL: envOr("WOLFY_CITAVUK_YANDEX_COMPLETE_URL", "https://api.citavuk.ru/v1/auth/yandex/complete"),
		CitavukYandexWebReturn:   envBool("WOLFY_CITAVUK_YANDEX_WEB_RETURN", false),
		GoogleWebClientID:        strings.TrimSpace(os.Getenv("WOLFY_GOOGLE_WEB_CLIENT_ID")),
		GoogleClientID:           strings.TrimSpace(os.Getenv("WOLFY_GOOGLE_CLIENT_ID")),
		GoogleClientSecret:       strings.TrimSpace(os.Getenv("WOLFY_GOOGLE_CLIENT_SECRET")),
		GoogleCallbackURL:        strings.TrimSpace(os.Getenv("WOLFY_GOOGLE_CALLBACK_URL")),
		OAuthStateSecret:         strings.TrimSpace(os.Getenv("WOLFY_OAUTH_STATE_SECRET")),
		WebOrigin:                strings.TrimRight(strings.TrimSpace(os.Getenv("WOLFY_WEB_ORIGIN")), "/"),
		StandardEbooksFeedURL: envOr(
			"WOLFY_STANDARD_EBOOKS_FEED_URL",
			"https://standardebooks.org/feeds/atom/new-releases",
		),
		StandardEbooksUser: strings.TrimSpace(os.Getenv("WOLFY_STANDARD_EBOOKS_USER")),
		StandardEbooksPass: strings.TrimSpace(os.Getenv("WOLFY_STANDARD_EBOOKS_PASSWORD")),
		DictionaryPath: envOr(
			"WOLFY_DICTIONARY_PATH",
			defaultDictionaryPath(),
		),
		ReleasesPath: envOr("WOLFY_RELEASES_PATH", defaultReleasesPath()),

		RequestTimeout: 20 * time.Second,
	}

	// База — единственное, без чего сервис бесполезен: в ней и аккаунты, и
	// синхронизация, и кэш переводов.
	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("не задан WOLFY_DB_URL: без базы сервис работать не может")
	}
	return cfg, nil
}

func defaultReleasesPath() string {
	candidates := []string{"dist", filepath.Join("..", "dist")}
	if executable, err := os.Executable(); err == nil {
		folder := filepath.Dir(executable)
		candidates = append(candidates, filepath.Join(folder, "dist"), folder)
	}
	for _, candidate := range candidates {
		if info, err := os.Stat(candidate); err == nil && info.IsDir() {
			return filepath.Clean(candidate)
		}
	}
	return filepath.Clean(candidates[0])
}

// defaultDictionaryPath не зависит от того, запустили `go run` из корня
// проекта или из server. Раньше один и тот же сервер в первом случае молча
// отключал словарь, потому что относительный путь указывал на соседний каталог.
func defaultDictionaryPath() string {
	const name = "wolfy_dictionary.tsv"
	candidates := []string{
		filepath.Join("dist", name),
		filepath.Join("..", "dist", name),
	}
	if executable, err := os.Executable(); err == nil {
		folder := filepath.Dir(executable)
		candidates = append(candidates,
			filepath.Join(folder, name),
			filepath.Join(folder, "dist", name),
		)
	}
	for _, candidate := range candidates {
		if info, err := os.Stat(candidate); err == nil && info.Mode().IsRegular() {
			return filepath.Clean(candidate)
		}
	}
	return filepath.Clean(candidates[0])
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

func envBool(key string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}
