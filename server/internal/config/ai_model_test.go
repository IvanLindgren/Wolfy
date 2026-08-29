package config

import (
	"os"
	"testing"
)

// Модель подсказок не наследуется от модели распознавания фото.
//
// Наследовалась: `WOLFY_AI_MODEL` по умолчанию брал `WOLFY_OCR_MODEL`. В
// production задан только второй, поэтому весь текст разборов, пересказов и
// реплик компаньона годами уходил в модель, выбранную для фотографий. Заметить
// это по конфигу было нельзя - переменной, которая всё решала, там просто не
// было, - а платилось за неё на порядок больше.
//
// Тест держит эти две настройки раздельными. Если наследование однажды вернут
// ради удобства, оно упрётся сюда, а не в счёт.
func TestМодельПодсказокНеНаследуетМодельOCR(t *testing.T) {
	t.Setenv("WOLFY_DB_URL", "postgres://localhost/wolfy")
	t.Setenv("WOLFY_OCR_MODEL", "google/gemini-3.7-flash")
	os.Unsetenv("WOLFY_AI_MODEL")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("настройки не собрались: %v", err)
	}
	if cfg.AIModel == cfg.OCRModel {
		t.Fatalf("модель подсказок утянулась у OCR: %q", cfg.AIModel)
	}
	if cfg.AIModel != DefaultAIModel {
		t.Fatalf("без WOLFY_AI_MODEL ожидалась %q, а собралась %q", DefaultAIModel, cfg.AIModel)
	}
}

// Явная переменная сильнее умолчания: сменить модель обратно можно без сборки.
func TestЯвнаяМодельПодсказокПобеждаетУмолчание(t *testing.T) {
	t.Setenv("WOLFY_DB_URL", "postgres://localhost/wolfy")
	t.Setenv("WOLFY_AI_MODEL", "openai/gpt-5-mini")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("настройки не собрались: %v", err)
	}
	if cfg.AIModel != "openai/gpt-5-mini" {
		t.Fatalf("явная модель не применилась: %q", cfg.AIModel)
	}
}

// Ключ и адрес наследовать у OCR по-прежнему можно: это один аккаунт и один
// протокол, и разводить их значило бы заводить вторую копию тех же секретов.
func TestКлючИАдресПодсказокНаследуютсяОтOCR(t *testing.T) {
	t.Setenv("WOLFY_DB_URL", "postgres://localhost/wolfy")
	t.Setenv("WOLFY_OCR_KEY", "secret")
	t.Setenv("WOLFY_OCR_URL", "https://api.polza.ai/api/v1/chat/completions")
	os.Unsetenv("WOLFY_AI_KEY")
	os.Unsetenv("WOLFY_AI_URL")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("настройки не собрались: %v", err)
	}
	if cfg.AIKey != "secret" || cfg.AIURL != cfg.OCRURL {
		t.Fatalf("ключ или адрес перестали наследоваться: key=%q url=%q", cfg.AIKey, cfg.AIURL)
	}
}
