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

	// AI* — OpenAI-совместимый endpoint для Beta-подсказок читателю.
	// Ключ и адрес по умолчанию берутся у уже настроенного OCR-провайдера:
	// это один и тот же аккаунт и один и тот же протокол.
	//
	// А вот модель — не берётся, и это важно. Раньше бралась: незаданный
	// WOLFY_AI_MODEL молча наследовал WOLFY_OCR_MODEL, и production, где задан
	// только OCR, гонял через модель для распознавания фотографий весь текст
	// разборов, пересказов и реплик компаньона. Задачи разные - одной нужно
	// зрение, другой нет, - а цена отличается на порядок, и связывать их
	// молчанием было дорогой ошибкой.
	AIKey   string
	AIModel string
	AIURL   string
	// AIJSONMode включает response_format только для endpoint, у которого
	// эта возможность подтверждена конфигурацией.
	AIJSONMode bool
	// AITimeout — сколько ждать одну модель. Отдельно от RequestTimeout
	// намеренно: перевод и каталог отвечают за секунды, а пересказ на
	// восемнадцать тысяч знаков у бесплатной модели в двадцать секунд не
	// укладывается. Общий таймаут ронял его в резерв, где следующая модель
	// не успевала тем более, и читатель получал «нет связи» вместо ответа.
	AITimeout time.Duration
	// AIBudget — потолок времени на всю цепочку моделей одного запроса.
	// Отдельно от AITimeout: тот ограничивает одну попытку, а этот — их
	// сумму. Без него четыре модели по две попытки складывались в шесть
	// минут работы в соединение, которое читалка закрыла на первой минуте.
	AIBudget time.Duration
	// AIPackBudget — тот же потолок для набора из ста реплик. Он длиннее:
	// сто фраз одним ответом модель пишет заметно дольше подсказки, а ждёт
	// его не читатель у страницы, а редактор компаньона.
	AIPackBudget time.Duration
	// AIFallbackModels — запасные модели того же провайдера, через запятую.
	// Пробуются после основной и раньше бесплатного резерва OpenRouter.
	AIFallbackModels string
	// AIReasoningEffort — сколько модели разрешено думать перед ответом.
	// Пустая строка снимает просьбу и возвращает поведение провайдера по
	// умолчанию.
	AIReasoningEffort string
	// OpenRouter* — резервный OpenAI-совместимый провайдер. Поддерживается
	// старое имя WOLFY_OPENROUTER, уже использованное в production env.
	OpenRouterKey    string
	OpenRouterModels string

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

	// GutendexURL — каталог Project Gutenberg. Ключей и регистрации не
	// требует, поэтому значение по умолчанию рабочее, а переменная нужна
	// только чтобы подставить зеркало, если основной адрес недоступен.
	GutendexURL string

	// DictionaryPath — распакованный TSV. Рядом обязан лежать файл .tsv.gz,
	// который сервер отдаёт устройствам для офлайн-установки.
	DictionaryPath string

	// ReleasesPath — каталог опубликованных MSI и APK. Сервер строит манифест
	// непосредственно по его содержимому, поэтому выпуск новой версии не
	// требует править конфиг или перезапускать процесс.
	ReleasesPath string
	// BookFilesPath — закрытое файловое хранилище книг пользователей. Оно
	// намеренно не раздаётся как статический каталог: каждый запрос проверяет
	// сессию и владельца книги.
	BookFilesPath string

	// RequestTimeout — сколько сервис готов ждать ответа от внешнего API.
	// Клиент не должен ждать дольше, чем ему обещано.
	RequestTimeout time.Duration
}

// DefaultAIModel — модель Beta-подсказок, когда WOLFY_AI_MODEL не задан.
//
// Цены Polza за миллион токенов, рублями, на день замены:
//
//	google/gemini-3.7-flash   вход 89.17   выход 445.83
//	z-ai/glm-5.3-flash        вход  8.92   выход  29.72
//
// Десятикратная разница на входе и пятнадцатикратная на выходе, а вход здесь
// большой: в пересказ уезжает до восемнадцати тысяч знаков прочитанного.
//
// Модель OpenAI-совместима и принимает response_format: json_object, то есть
// JSON mode остаётся включённым. Схему она не навязывает, но контракт тут и не
// держится на провайдере - ответ проверяет и при нужде чинит AskValidated.
const DefaultAIModel = "z-ai/glm-5.3-flash"

// DefaultAIFallbackModels — запасные модели у того же провайдера.
//
// Цены Polza за миллион токенов, рублями:
//
//	z-ai/glm-5.3-flash                     вход  8.90   выход  29.67
//	deepseek/deepseek-v4-flash-vision-exp  вход 26.11   выход  78.34
//
// Втрое дороже основной и всё ещё вчетверо дешевле того, что стояло здесь до
// glm. Платить втрое имеет смысл только тогда, когда альтернатива — не ответить
// вовсе, и именно в этом месте цепочки она такая.
//
// Endpoint у модели тот же, response_format и reasoning она принимает — то
// есть контракт JSON и просьба думать поменьше доезжают до неё без оговорок.
const DefaultAIFallbackModels = "deepseek/deepseek-v4-flash-vision-exp"

// DefaultAIReasoningEffort — сколько модели разрешено думать перед ответом.
//
// Модель по умолчанию рассуждающая, и на промпте мнения о странице она тратила
// на размышление шестьсот токенов и тридцать секунд. На «minimal» тот же промпт
// с тем же проверяемым контрактом отвечает за семь секунд и вчетверо дешевле:
//
//	default  30.0 c, 616 reasoning-токенов, 0.0285 руб.
//	minimal   7.3 c,   0 reasoning-токенов, 0.0075 руб.
//
// Тридцать секунд подсказка себе позволить не может: клиент ждёт ответа
// ограниченное время, и первая же неудачная попытка выносила запрос за этот
// предел. Значение снимается пустой строкой, если провайдер сменится на
// нерассуждающую модель, где просьба лишняя.
const DefaultAIReasoningEffort = "minimal"

// DefaultAIBudget и DefaultAIPackBudget — потолки времени цепочки моделей.
// Оба заметно меньше терпения клиента: см. readingai.DefaultBudget.
const (
	DefaultAIBudget     = 45 * time.Second
	DefaultAIPackBudget = 150 * time.Second
)

// Load читает настройки и проверяет обязательные.
func Load() (Config, error) {
	cfg := Config{
		Addr:        envOr("WOLFY_ADDR", ":8080"),
		Env:         envOr("WOLFY_ENV", "dev"),
		DatabaseURL: env("WOLFY_DB_URL"),

		DeepLKey: env("DEEPL_API_KEY"),
		DeepLURL: envOr("WOLFY_DEEPL_URL", "https://api-free.deepl.com/v2/translate"),

		OCRKey:                   env("WOLFY_OCR_KEY"),
		OCRModel:                 envOr("WOLFY_OCR_MODEL", "google/gemini-3.7-flash"),
		OCRURL:                   envOr("WOLFY_OCR_URL", "https://api.polza.ai/api/v1/chat/completions"),
		AIKey:                    envOr("WOLFY_AI_KEY", env("WOLFY_OCR_KEY")),
		AIModel:                  envOr("WOLFY_AI_MODEL", DefaultAIModel),
		AIURL:                    envOr("WOLFY_AI_URL", envOr("WOLFY_OCR_URL", "https://api.polza.ai/api/v1/chat/completions")),
		AIJSONMode:               envBool("WOLFY_AI_JSON_MODE", true),
		AITimeout:                envSeconds("WOLFY_AI_TIMEOUT_SECONDS", 45*time.Second),
		AIBudget:                 envSeconds("WOLFY_AI_BUDGET_SECONDS", DefaultAIBudget),
		AIPackBudget:             envSeconds("WOLFY_AI_PACK_BUDGET_SECONDS", DefaultAIPackBudget),
		AIFallbackModels:         envOr("WOLFY_AI_FALLBACK_MODELS", DefaultAIFallbackModels),
		AIReasoningEffort:        envOr("WOLFY_AI_REASONING_EFFORT", DefaultAIReasoningEffort),
		OpenRouterKey:            envOr("WOLFY_OPENROUTER_KEY", env("WOLFY_OPENROUTER")),
		OpenRouterModels:         envOr("WOLFY_OPENROUTER_MODELS", "nvidia/nemotron-3-super-120b-a12b:free,z-ai/glm-5.2:free,openrouter/free"),
		CitavukLoginURL:          envOr("WOLFY_CITAVUK_LOGIN_URL", "https://api.citavuk.ru/v1/auth/login"),
		CitavukRegisterURL:       envOr("WOLFY_CITAVUK_REGISTER_URL", "https://api.citavuk.ru/v1/auth/register"),
		CitavukResendURL:         envOr("WOLFY_CITAVUK_RESEND_URL", "https://api.citavuk.ru/v1/auth/resend-verification"),
		CitavukGoogleURL:         envOr("WOLFY_CITAVUK_GOOGLE_URL", "https://api.citavuk.ru/v1/auth/google"),
		CitavukYandexStartURL:    envOr("WOLFY_CITAVUK_YANDEX_START_URL", "https://api.citavuk.ru/v1/auth/yandex/start"),
		CitavukYandexCompleteURL: envOr("WOLFY_CITAVUK_YANDEX_COMPLETE_URL", "https://api.citavuk.ru/v1/auth/yandex/complete"),
		CitavukYandexWebReturn:   envBool("WOLFY_CITAVUK_YANDEX_WEB_RETURN", false),
		GoogleWebClientID:        env("WOLFY_GOOGLE_WEB_CLIENT_ID"),
		GoogleClientID:           env("WOLFY_GOOGLE_CLIENT_ID"),
		GoogleClientSecret:       env("WOLFY_GOOGLE_CLIENT_SECRET"),
		GoogleCallbackURL:        env("WOLFY_GOOGLE_CALLBACK_URL"),
		OAuthStateSecret:         env("WOLFY_OAUTH_STATE_SECRET"),
		WebOrigin:                strings.TrimRight(env("WOLFY_WEB_ORIGIN"), "/"),
		GutendexURL:              envOr("WOLFY_GUTENDEX_URL", "https://gutendex.com/books/"),
		DictionaryPath: envOr(
			"WOLFY_DICTIONARY_PATH",
			defaultDictionaryPath(),
		),
		ReleasesPath:  envOr("WOLFY_RELEASES_PATH", defaultReleasesPath()),
		BookFilesPath: envOr("WOLFY_BOOK_FILES_PATH", defaultBookFilesPath()),

		RequestTimeout: 20 * time.Second,
	}

	// База — единственное, без чего сервис бесполезен: в ней и аккаунты, и
	// синхронизация, и кэш переводов.
	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("не задан WOLFY_DB_URL: без базы сервис работать не может")
	}
	return cfg, nil
}

func defaultBookFilesPath() string {
	// Релизы в /opt/wolfy/releases намеренно заменяются целиком. Файлы книг
	// должны пережить такую замену, поэтому на VDS живут рядом с общим env.
	// Каталог создаёт сам bookfiles.Service при первой загрузке.
	if info, err := os.Stat("/opt/wolfy/shared"); err == nil && info.IsDir() {
		return "/opt/wolfy/shared/book-files"
	}
	return filepath.Join(defaultReleasesPath(), "book-files")
}

func defaultReleasesPath() string {
	// Пакеты обновления — общие данные, а не часть переключаемого релиза.
	// Без этого production после каждого deploy смотрел бы в новый каталог
	// бинарника и переставал предлагать уже опубликованный APK/MSI.
	if info, err := os.Stat("/opt/wolfy/shared/releases"); err == nil && info.IsDir() {
		return "/opt/wolfy/shared/releases"
	}
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

/*
 * env читает переменную и снимает с неё кавычки.
 *
 * `WOLFY_GOOGLE_WEB_CLIENT_ID="123.apps.googleusercontent.com"` — то, как
 * переменные окружения пишут по привычке из shell-скриптов, и systemd такую
 * запись действительно раскавычивает сам. Но раскавычивает он её только когда
 * кавычки закрыты и стоят по краям всего значения: строка со случайным
 * пробелом после закрывающей кавычки, с `#` внутри или собранная не systemd, а
 * `docker run --env-file`, доезжает до сервиса вместе с кавычками.
 *
 * Дальше кавычка молча становится частью ключа: Google отвечает
 * `invalid_client` на client_id, отличающийся от настоящего двумя знаками,
 * Postgres — «не найден хост», и ни один из этих ответов не называет причину.
 * Дешевле снять кавычки здесь, чем каждый раз узнавать их по симптомам.
 *
 * Снимается только пара одинаковых кавычек по краям: значение, в котором
 * кавычка стоит внутри, — это значение с кавычкой, а не ошибка записи.
 */
func env(key string) string {
	return unquote(strings.TrimSpace(os.Getenv(key)))
}

func unquote(value string) string {
	for _, quote := range []string{`"`, `'`} {
		if len(value) >= 2 && strings.HasPrefix(value, quote) && strings.HasSuffix(value, quote) {
			return strings.TrimSpace(value[1 : len(value)-1])
		}
	}
	return value
}

func envOr(key, fallback string) string {
	if value := env(key); value != "" {
		return value
	}
	return fallback
}

// envSeconds читает таймаут в секундах. Час сверху — не настройка, а защита
// от опечатки: запрос, висящий дольше, уже никому не нужен.
func envSeconds(key string, fallback time.Duration) time.Duration {
	value := env(key)
	if value == "" {
		return fallback
	}
	seconds, err := strconv.Atoi(value)
	if err != nil || seconds <= 0 || seconds > 3600 {
		return fallback
	}
	return time.Duration(seconds) * time.Second
}

func envBool(key string, fallback bool) bool {
	value := env(key)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}
