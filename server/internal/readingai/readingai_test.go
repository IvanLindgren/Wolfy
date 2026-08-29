package readingai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
