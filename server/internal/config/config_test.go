package config

import (
	"os"
	"strings"
	"testing"
)

// Кавычки вокруг значения — самая частая ошибка записи `wolfy.env`, и цена у
// неё непропорциональная: сервис поднимается, healthcheck зелёный, а вход
// через Google отвечает `invalid_client` на client_id, отличающийся от
// настоящего ровно двумя знаками.
func TestLoadStripsSurroundingQuotes(t *testing.T) {
	t.Setenv("WOLFY_DB_URL", `"postgres://wolfy:wolfy@localhost:5433/wolfy?sslmode=disable"`)
	t.Setenv("WOLFY_GOOGLE_WEB_CLIENT_ID", `"123.apps.googleusercontent.com"`)
	t.Setenv("WOLFY_GOOGLE_CLIENT_SECRET", `'секрет'`)
	t.Setenv("WOLFY_WEB_ORIGIN", `"https://wolfy.citavuk.ru/"`)
	t.Setenv("WOLFY_DEEPL_URL", `"https://api.deepl.com/v2/translate"`)
	t.Setenv("WOLFY_CITAVUK_YANDEX_WEB_RETURN", `"true"`)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("настройки не прочитались: %v", err)
	}
	if cfg.DatabaseURL != "postgres://wolfy:wolfy@localhost:5433/wolfy?sslmode=disable" {
		t.Errorf("адрес базы с кавычками: %q", cfg.DatabaseURL)
	}
	if cfg.GoogleWebClientID != "123.apps.googleusercontent.com" {
		t.Errorf("client_id с кавычками: %q", cfg.GoogleWebClientID)
	}
	if cfg.GoogleClientSecret != "секрет" {
		t.Errorf("секрет с кавычками: %q", cfg.GoogleClientSecret)
	}
	// Косая черта снимается после кавычек, а не до них.
	if cfg.WebOrigin != "https://wolfy.citavuk.ru" {
		t.Errorf("origin разобран неверно: %q", cfg.WebOrigin)
	}
	if cfg.DeepLURL != "https://api.deepl.com/v2/translate" {
		t.Errorf("адрес DeepL с кавычками: %q", cfg.DeepLURL)
	}
	if !cfg.CitavukYandexWebReturn {
		t.Error("флаг в кавычках не разобрался")
	}
}

// Кавычка внутри значения — часть значения: пароль `pa"ss` менять нельзя.
func TestUnquoteKeepsInnerQuotes(t *testing.T) {
	cases := map[string]string{
		`"внешние"`:   `внешние`,
		`'внешние'`:   `внешние`,
		`pa"ss`:       `pa"ss`,
		`"незакрытая`: `"незакрытая`,
		`"`:           `"`,
		`""`:          ``,
		``:            ``,
	}
	for input, want := range cases {
		if got := unquote(input); got != want {
			t.Errorf("unquote(%q) = %q, ожидали %q", input, got, want)
		}
	}
}

// Пустая переменная остаётся пустой и не мешает значению по умолчанию.
func TestEnvOrFallsBackOnQuotedEmpty(t *testing.T) {
	t.Setenv("WOLFY_TEST_EMPTY", `""`)
	if got := envOr("WOLFY_TEST_EMPTY", "по умолчанию"); got != "по умолчанию" {
		t.Errorf("пустое значение в кавычках не уступило умолчанию: %q", got)
	}
	_ = os.Unsetenv("WOLFY_TEST_EMPTY")
}

func TestLoadAcceptsExistingOpenRouterVariable(t *testing.T) {
	t.Setenv("WOLFY_DB_URL", "postgres://test")
	t.Setenv("WOLFY_OPENROUTER_KEY", "")
	t.Setenv("WOLFY_OPENROUTER", "legacy-key")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.OpenRouterKey != "legacy-key" {
		t.Fatalf("старое имя ключа не прочитано: %q", cfg.OpenRouterKey)
	}
	if !strings.Contains(cfg.OpenRouterModels, "openrouter/free") {
		t.Fatalf("в резерве нет стабильного free router: %q", cfg.OpenRouterModels)
	}
}
