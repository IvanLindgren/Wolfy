package readingai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
	answer, err := service.Ask(context.Background(), "json", 0.2)
	if err != nil {
		t.Fatalf("резерв не сработал: %v", err)
	}
	if answer != `{"ok":true}` {
		t.Fatalf("неожиданный ответ: %q", answer)
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
	if _, err := service.Ask(context.Background(), "json", 0.2); err != nil {
		t.Fatal(err)
	}
	if body := <-requests; body["response_format"] != nil {
		t.Fatal("имя модели само включило JSON mode")
	}

	service.WithJSONMode(true)
	if _, err := service.Ask(context.Background(), "json", 0.2); err != nil {
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
