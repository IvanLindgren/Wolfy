package readingai

import (
	"context"
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
