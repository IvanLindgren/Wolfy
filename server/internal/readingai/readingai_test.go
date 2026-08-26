package readingai

import "testing"

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
