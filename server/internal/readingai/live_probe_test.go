package readingai_test

import (
	"context"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/wolfy/server/internal/companionai"
	"github.com/wolfy/server/internal/readingai"
	"github.com/wolfy/server/internal/store"
)

// Живая проверка модели: держит ли она контракт на настоящем провайдере.
//
// Это не тест в обычном смысле, а инструмент на случай смены модели. Он ходит
// в сеть и тратит настоящие деньги, поэтому по умолчанию не запускается ни
// здесь, ни в CI: нужен и ключ, и явное WOLFY_LIVE_MODEL_PROBE=1.
//
// Смысл в том, что подсказки ломаются не отказом, а плохим ответом. Модель,
// которая отвечает быстро и дёшево, но кладёт в JSON markdown, ставит тире или
// путает число событий, выглядит рабочей в логах и нерабочей у читателя.
// Дешевле выяснить это до выката, чем по жалобам.
//
// Запуск:
//
//	WOLFY_LIVE_MODEL_PROBE=1 WOLFY_TEST_DB_URL=... WOLFY_OCR_KEY=... \
//	  go test ./internal/readingai/ -run Живая -v
func TestЖиваяМодельДержитКонтракт(t *testing.T) {
	if os.Getenv("WOLFY_LIVE_MODEL_PROBE") != "1" {
		t.Skip("живая проверка модели выключена: WOLFY_LIVE_MODEL_PROBE=1 включает")
	}
	key := firstNonEmpty(os.Getenv("WOLFY_AI_KEY"), os.Getenv("WOLFY_OCR_KEY"))
	if key == "" {
		t.Skip("нет ключа провайдера")
	}
	url := firstNonEmpty(os.Getenv("WOLFY_AI_URL"), "https://api.polza.ai/api/v1/chat/completions")
	model := firstNonEmpty(os.Getenv("WOLFY_AI_MODEL"), "z-ai/glm-5.3-flash")
	dsn := firstNonEmpty(os.Getenv("WOLFY_TEST_DB_URL"), os.Getenv("WOLFY_DB_URL"))
	if dsn == "" {
		t.Skip("нет базы: квота живёт в ней")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	db, err := store.Open(ctx, dsn)
	if err != nil {
		t.Fatalf("база не открылась: %v", err)
	}
	defer db.Close()

	user := "11111111-2222-3333-4444-555555555555"
	if _, err := db.Pool.Exec(ctx,
		`DELETE FROM wolfy.ai_daily_usage WHERE user_id=$1::uuid`, user); err != nil {
		t.Fatalf("сброс квоты: %v", err)
	}

	service := readingai.New(db, key, url, model, 60*time.Second).WithJSONMode(true)
	if !service.Configured() {
		t.Fatal("провайдер не собрался")
	}
	t.Logf("модель: %s", model)

	t.Run("пересказ", func(t *testing.T) {
		started := time.Now()
		recap, err := service.Recap(ctx, user, "Alice's Adventures in Wonderland", excerpt, "")
		if err != nil {
			t.Fatalf("пересказ не вышел: %v", err)
		}
		t.Logf("за %s, событий %d", time.Since(started).Round(time.Millisecond), len(recap.Events))
		t.Logf("summary: %s", recap.Summary)
		for _, event := range recap.Events {
			t.Logf("  [%s] %s — %s", event.Kind, event.Title, event.Text)
		}
		checkRussian(t, "summary", recap.Summary)
		if len(recap.Events) < 2 {
			t.Errorf("событий слишком мало: %d", len(recap.Events))
		}
		for _, event := range recap.Events {
			checkRussian(t, "event", event.Title+" "+event.Text)
			if event.Kind != "start" && event.Kind != "turn" && event.Kind != "result" {
				t.Errorf("неизвестный вид события: %q", event.Kind)
			}
		}
	})

	t.Run("разбор фразы", func(t *testing.T) {
		started := time.Now()
		phrase, err := service.Phrase(ctx, user,
			"she had been running for a while",
			"Alice had been running for a while when she noticed the rabbit hole.")
		if err != nil {
			t.Fatalf("разбор не вышел: %v", err)
		}
		t.Logf("за %s: %s | %s", time.Since(started).Round(time.Millisecond), phrase.Title, phrase.Pattern)
		t.Logf("объяснение: %s", phrase.Explanation)
		for _, step := range phrase.Steps {
			t.Logf("  %s: %s", step.Label, step.Text)
		}
		checkRussian(t, "explanation", phrase.Explanation)
		if len(phrase.Steps) < 2 || len(phrase.Steps) > 4 {
			t.Errorf("шагов вне контракта: %d", len(phrase.Steps))
		}
	})

	t.Run("мнение компаньона", func(t *testing.T) {
		companion := companionai.New(db, service)
		var request companionai.OpinionRequest
		request.BookID = "11111111-1111-1111-1111-111111111111"
		request.Title = "Alice's Adventures in Wonderland"
		request.PageText = excerpt[:1200]
		request.Companion.Name = "Вульфи"
		request.Companion.Locale = "ru"
		request.Companion.Description = "любопытный волчонок, говорит коротко и тепло"

		started := time.Now()
		opinion, err := companion.Opinion(ctx, user, request)
		if err != nil {
			t.Fatalf("мнение не вышло: %v", err)
		}
		t.Logf("за %s: %s", time.Since(started).Round(time.Millisecond), opinion.Title)
		t.Logf("реплика: %s", opinion.Opinion)
		checkRussian(t, "opinion", opinion.Opinion)
	})

	t.Run("сырой ответ соблюдает json mode", func(t *testing.T) {
		var raw string
		err := service.AskValidated(ctx, "",
			`Return JSON only: {"ok":true,"note":"одно короткое русское предложение"}`,
			"", 0.2, func(body []byte) error {
				raw = string(body)
				var probe map[string]any
				return json.Unmarshal(body, &probe)
			})
		if err != nil {
			t.Fatalf("модель не вернула разбираемый JSON: %v", err)
		}
		t.Logf("сырой ответ: %s", raw)
		if strings.Contains(raw, "```") {
			t.Errorf("в ответе markdown-обёртка, json mode не сработал")
		}
	})
}

// checkRussian ловит то, из-за чего ответы отбраковывались чаще всего.
func checkRussian(t *testing.T, what, text string) {
	t.Helper()
	if strings.TrimSpace(text) == "" {
		t.Errorf("%s пустой", what)
		return
	}
	for _, dash := range []string{"—", "–", "―"} {
		if strings.Contains(text, dash) {
			t.Logf("ВНИМАНИЕ: %s содержит длинное тире (санитайзер его чинит, но модель правило не поняла)", what)
			break
		}
	}
	if strings.Contains(text, "```") || strings.Contains(text, "**") {
		t.Errorf("%s содержит markdown: %q", what, text)
	}
	var cyrillic int
	for _, r := range text {
		if r >= 'а' && r <= 'я' || r >= 'А' && r <= 'Я' || r == 'ё' || r == 'Ё' {
			cyrillic++
		}
	}
	if cyrillic*3 < len([]rune(text)) {
		t.Errorf("%s похоже не по-русски: %q", what, text)
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

// Отрывок настоящей книги: модель обязана работать на живом тексте, а не на
// придуманном для теста абзаце.
const excerpt = `Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do: once or twice she had peeped into the book her sister was reading, but it had no pictures or conversations in it, and what is the use of a book, thought Alice, without pictures or conversations?

So she was considering in her own mind, as well as she could, for the hot day made her feel very sleepy and stupid, whether the pleasure of making a daisy-chain would be worth the trouble of getting up and picking the daisies, when suddenly a White Rabbit with pink eyes ran close by her.

There was nothing so very remarkable in that; nor did Alice think it so very much out of the way to hear the Rabbit say to itself, Oh dear! Oh dear! I shall be late! But when the Rabbit actually took a watch out of its waistcoat-pocket, and looked at it, and then hurried on, Alice started to her feet, for it flashed across her mind that she had never before seen a rabbit with either a waistcoat-pocket, or a watch to take out of it, and burning with curiosity, she ran across the field after it, and fortunately was just in time to see it pop down a large rabbit-hole under the hedge.

In another moment down went Alice after it, never once considering how in the world she was to get out again. The rabbit-hole went straight on like a tunnel for some way, and then dipped suddenly down, so suddenly that Alice had not a moment to think about stopping herself before she found herself falling down a very deep well.`
