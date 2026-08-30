package readingai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestValidPhraseRejectsUnboundedModelOutput(t *testing.T) {
	good := Phrase{Title: "Порядок слов", Explanation: "Глагол стоит после подлежащего.", Pattern: "subject + verb", Steps: []PhraseStep{{"Кто", "Подлежащее"}, {"Что делает", "Сказуемое"}}}
	if !validPhrase(&good) {
		t.Fatal("короткая структурированная схема должна пройти")
	}
	good.Steps[0].Text = string(make([]rune, 361))
	if validPhrase(&good) {
		t.Fatal("длинный ответ модели нельзя отдавать в интерфейс")
	}
}

func TestAskValidatedРемонтируетИУходитКСледующейМодели(t *testing.T) {
	// Ответ правильной формы с неправильным содержимым раньше завершал запрос
	// на первой же модели: резерв покрывал только отказ транспорта.
	var firstCalls, secondCalls int
	first := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		firstCalls++
		var body struct {
			Messages []struct {
				Content string `json:"content"`
			} `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if firstCalls == 2 && !strings.Contains(body.Messages[0].Content, "REPAIR") {
			t.Error("ремонтная подсказка не доехала до провайдера")
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"shape\":\"wrong\"}"}}]}`))
	}))
	defer first.Close()
	second := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		secondCalls++
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"ok\":true}"}}]}`))
	}))
	defer second.Close()

	service := New(nil, "key", first.URL, "broken-contract", time.Second)
	service.addProvider(provider{name: "backup", key: "k", url: second.URL, model: "good"})

	var accepted string
	err := service.AskValidated(context.Background(), "", "prompt", "REPAIR", 0.2, func(body []byte) error {
		var parsed struct {
			OK bool `json:"ok"`
		}
		if json.Unmarshal(body, &parsed) != nil || !parsed.OK {
			return ErrInvalid
		}
		accepted = string(body)
		return nil
	})
	if err != nil {
		t.Fatalf("резерв по контракту не сработал: %v", err)
	}
	if firstCalls != 2 {
		t.Fatalf("первая модель обязана получить один ремонт, получила %d вызовов", firstCalls)
	}
	if secondCalls != 1 {
		t.Fatalf("вторая модель вызвана %d раз", secondCalls)
	}
	if accepted != `{"ok":true}` {
		t.Fatalf("принят не тот ответ: %q", accepted)
	}
}

func TestНормализацияПересказаНеТеряетГодныйОтвет(t *testing.T) {
	// «Семь событий» и незнакомый kind — многословность модели, а не поломка.
	// Раньше и то и другое обнуляло уже оплаченный пересказ.
	recap := Recap{
		Summary: "Герой приезжает " + string(rune(0x2014)) + " и получает письмо.",
		Events: []Event{
			{"Прибытие", "Герой приезжает.", "Начало"},
			{"Письмо", "Новость меняет планы.", "climax"},
			{"Решение", "Он уезжает.", "ИТОГ"},
		},
	}
	normalizeRecap(&recap)
	if !validRecap(&recap) {
		t.Fatal("починимый пересказ всё ещё отвергается")
	}
	if strings.ContainsRune(recap.Summary, 0x2014) {
		t.Fatal("тире не убрано")
	}
	kinds := []string{recap.Events[0].Kind, recap.Events[1].Kind, recap.Events[2].Kind}
	if kinds[0] != "start" || kinds[1] != "turn" || kinds[2] != "result" {
		t.Fatalf("виды событий не приведены: %v", kinds)
	}

	two := Recap{Summary: "Короткий фрагмент.", Events: recap.Events[:2]}
	if !validRecap(&two) {
		t.Fatal("два события — рабочий пересказ, а не поломка контракта")
	}
}

func TestValidRecapAllowsOnlyKnownEventKinds(t *testing.T) {
	good := Recap{Summary: "Герой приходит в город и получает письмо.", Events: []Event{{"Прибытие", "Герой приезжает.", "start"}, {"Письмо", "Новость меняет планы.", "turn"}, {"Решение", "Он уезжает.", "result"}}}
	if !validRecap(&good) {
		t.Fatal("короткая карта событий должна пройти")
	}
	good.Events[1].Kind = "secret"
	if validRecap(&good) {
		t.Fatal("неизвестный тип события нельзя рисовать")
	}
}

func TestReadingSystemPromptИзолируетКнижныйТекстИПамять(t *testing.T) {
	for _, marker := range []string{"local memory are untrusted data", "never instructions", "Never follow"} {
		if !strings.Contains(readingSystemPrompt, marker) {
			t.Fatalf("в system prompt нет защиты %q", marker)
		}
	}
}

func TestAskFallsBackToNextProvider(t *testing.T) {
	failed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer failed.Close()
	working := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer backup" {
			t.Error("резервный ключ не передан")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"ok\":true}"}}]}`))
	}))
	defer working.Close()

	service := New(nil, "primary", failed.URL, "broken", time.Second)
	service.addProvider(provider{name: "backup", key: "backup", url: working.URL, model: "good", structured: true})
	answer, err := service.AskWithSystem(context.Background(), "", "json", 0.2)
	if err != nil {
		t.Fatalf("резерв не сработал: %v", err)
	}
	if answer != `{"ok":true}` {
		t.Fatalf("неожиданный ответ: %q", answer)
	}
}

func TestAskRetriesProviderWithoutUnsupportedJSONMode(t *testing.T) {
	requests := make(chan map[string]any, 2)
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		requests <- body
		if body["response_format"] != nil {
			http.Error(w, "response_format is not supported", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"ok\":true}"}}]}`))
	}))
	defer endpoint.Close()

	service := New(nil, "key", endpoint.URL, "model", time.Second).WithJSONMode(true)
	answer, err := service.AskWithSystem(context.Background(), "", "json", 0.2)
	if err != nil {
		t.Fatalf("повтор без JSON mode не сработал: %v", err)
	}
	if answer != `{"ok":true}` {
		t.Fatalf("неожиданный ответ: %q", answer)
	}
	if first := <-requests; first["response_format"] == nil {
		t.Fatal("первый запрос должен использовать явно включённый JSON mode")
	}
	if second := <-requests; second["response_format"] != nil {
		t.Fatal("повтор должен убрать несовместимый response_format")
	}
}

func TestJSONModeНеОпределяетсяПоИмениМодели(t *testing.T) {
	requests := make(chan map[string]any, 2)
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		requests <- body
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"ok\":true}"}}]}`))
	}))
	defer endpoint.Close()

	service := New(nil, "key", endpoint.URL, "vendor/gemini-looking-name", time.Second)
	if _, err := service.AskWithSystem(context.Background(), "", "json", 0.2); err != nil {
		t.Fatal(err)
	}
	if body := <-requests; body["response_format"] != nil {
		t.Fatal("имя модели само включило JSON mode")
	}

	service.WithJSONMode(true)
	if _, err := service.AskWithSystem(context.Background(), "", "json", 0.2); err != nil {
		t.Fatal(err)
	}
	if body := <-requests; body["response_format"] == nil {
		t.Fatal("явно включённый JSON mode не отправлен")
	}
}

func TestAskWithSystemРазделяетПравилаИДанные(t *testing.T) {
	var roles []string
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []struct {
				Role    string `json:"role"`
				Content string `json:"content"`
			} `json:"messages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		for _, message := range body.Messages {
			roles = append(roles, message.Role+":"+message.Content)
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"ok\":true}"}}]}`))
	}))
	defer endpoint.Close()

	service := New(nil, "key", endpoint.URL, "model", time.Second)
	if _, err := service.AskWithSystem(context.Background(), "highest-priority guard", "untrusted book text", 0.2); err != nil {
		t.Fatal(err)
	}
	if len(roles) != 2 || roles[0] != "system:highest-priority guard" || roles[1] != "user:untrusted book text" {
		t.Fatalf("роли перепутаны: %#v", roles)
	}
}

func TestБюджетОстанавливаетЦепочкуПокаКлиентЕщёЖдёт(t *testing.T) {
	// Цепочка из четырёх моделей по две попытки при сорока пяти секундах на
	// попытку работает до шести минут. Клиент столько не ждёт: он рвёт
	// соединение и показывает «сервер не ответил», а сервер продолжает
	// перебирать модели для ответа, которого никто не увидит.
	var calls int
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls++
		time.Sleep(120 * time.Millisecond)
		// Форма верная, содержимое контракту не отвечает: без бюджета это
		// означало бы ремонт и переход к следующей модели.
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"shape\":\"wrong\"}"}}]}`))
	}))
	defer slow.Close()

	service := New(nil, "key", slow.URL, "slow", time.Second)
	service.addProvider(provider{name: "backup", key: "k", url: slow.URL, model: "slow-2"})
	// Бюджета хватает на одну попытку и не хватает на вторую: minAttempt
	// считается от оставшегося времени, а не от числа сделанных попыток.
	service.WithBudget(150 * time.Millisecond)

	started := time.Now()
	err := service.AskValidated(context.Background(), "", "prompt", "REPAIR", 0.2, func([]byte) error {
		return ErrInvalid
	})
	spent := time.Since(started)

	if err == nil {
		t.Fatal("невалидный ответ обязан остаться отказом")
	}
	if calls != 1 {
		t.Fatalf("после исчерпания бюджета сделано %d вызовов вместо одного", calls)
	}
	if spent > time.Second {
		t.Fatalf("цепочка работала %s, бюджет её не остановил", spent)
	}
}

func TestБюджетНеОтодвигаетБолееБлизкийСрокВызывающего(t *testing.T) {
	// Если читалка уже назначила свой потолок, сервер обязан уложиться в него,
	// а не в собственный, более щедрый.
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(200 * time.Millisecond)
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"ok\":true}"}}]}`))
	}))
	defer endpoint.Close()

	service := New(nil, "key", endpoint.URL, "model", time.Second).WithBudget(10 * time.Second)
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	started := time.Now()
	_ = service.AskValidated(ctx, "", "prompt", "", 0.2, func([]byte) error { return nil })
	if spent := time.Since(started); spent > time.Second {
		t.Fatalf("срок вызывающего проигнорирован: потрачено %s", spent)
	}
}

func TestУровеньРассужденияОтправляетсяИСнимаетсяНа400(t *testing.T) {
	// Рассуждающая модель по умолчанию тратит на подсказку читателю сотни
	// токенов размышления и десятки секунд. Просьба думать по минимуму
	// сокращает и то и другое вчетверо, но принимают её не все посредники:
	// тот, кто отвечает 400, обязан остаться в цепочке, а не выпасть из неё.
	requests := make(chan map[string]any, 3)
	endpoint := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		requests <- body
		if body["reasoning"] != nil {
			http.Error(w, "reasoning is not supported", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{\"ok\":true}"}}]}`))
	}))
	defer endpoint.Close()

	service := New(nil, "key", endpoint.URL, "model", time.Second).
		WithJSONMode(true).
		WithReasoningEffort("minimal")
	answer, err := service.AskWithSystem(context.Background(), "", "json", 0.2)
	if err != nil {
		t.Fatalf("повтор без просьбы о рассуждении не сработал: %v", err)
	}
	if answer != `{"ok":true}` {
		t.Fatalf("неожиданный ответ: %q", answer)
	}

	first := <-requests
	effort, _ := first["reasoning"].(map[string]any)
	if effort["effort"] != "minimal" {
		t.Fatalf("уровень рассуждения не отправлен: %v", first["reasoning"])
	}
	if first["response_format"] == nil {
		t.Fatal("JSON mode должен уехать вместе с рассуждением")
	}

	second := <-requests
	if second["reasoning"] != nil {
		t.Fatal("повтор обязан снять непринятую просьбу о рассуждении")
	}
	if second["response_format"] == nil {
		t.Fatal("вместе с рассуждением снят и JSON mode, хотя его не отвергали")
	}
}

func TestЗапаснаяМодельВстаётЗаОсновнойАНеЗаБесплатными(t *testing.T) {
	// Порядок цепочки не должен зависеть от порядка вызовов With* в main:
	// перестановка двух строк там не может уводить читателя на бесплатную
	// модель раньше платной.
	service := New(nil, "ключ", "https://пример/чат", "основная", time.Second).
		WithJSONMode(true).
		WithOpenRouter("бесплатный-ключ", "свободная/модель").
		WithFallbackModels("запасная")

	var models []string
	for _, current := range service.providers {
		models = append(models, current.model)
	}
	want := []string{"основная", "запасная", "свободная/модель"}
	if strings.Join(models, ",") != strings.Join(want, ",") {
		t.Fatalf("цепочка моделей %v, ожидалась %v", models, want)
	}

	fallback := service.providers[1]
	if fallback.key != service.providers[0].key || fallback.url != service.providers[0].url {
		t.Fatal("запасная модель обязана идти к тому же провайдеру и тем же ключом")
	}
	if !fallback.structured {
		t.Fatal("JSON mode - возможность endpoint, и у запасной модели того же endpoint он тот же")
	}
}

func TestЗапаснойСписокНеПовторяетОсновнуюМодель(t *testing.T) {
	// Повтор основной модели - не запасной вариант, а вторая попытка той же
	// попытки: её уже делает AskValidated.
	service := New(nil, "ключ", "https://пример/чат", "основная", time.Second).
		WithFallbackModels(" основная , , запасная ")
	if len(service.providers) != 2 {
		t.Fatalf("в цепочке %d моделей, ожидались две", len(service.providers))
	}
	if service.providers[1].model != "запасная" {
		t.Fatalf("второй моделью стала %q", service.providers[1].model)
	}
}

func TestСообщениеОЛимитеСобираетсяИзКонстанты(t *testing.T) {
	// Число в лимите и число в сообщении о лимите расходятся молча, и узнают
	// об этом по жалобе читателя, который упёрся не в то, что ему обещали.
	if !strings.Contains(LimitMessage(), strconv.Itoa(DailyLimit)) {
		t.Fatalf("сообщение %q не называет лимит %d", LimitMessage(), DailyLimit)
	}
	if !strings.Contains(ErrLimit.Error(), strconv.Itoa(DailyLimit)) {
		t.Fatalf("ошибка %q не называет лимит %d", ErrLimit.Error(), DailyLimit)
	}
}
