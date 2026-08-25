package library

import (
	"strings"
	"testing"

	"github.com/wolfy/server/internal/store"
)

const testUUID = "3f1c2b4a-0000-4000-8000-000000000001"

func TestValidateAcceptsEveryClientCardKind(t *testing.T) {
	for _, kind := range []string{"", "word", "phrase", "rule"} {
		t.Run("kind="+kind, func(t *testing.T) {
			err := validate(store.Changes{Cards: []store.Card{{ID: testUUID, Kind: kind}}})
			if err != nil {
				t.Fatalf("вид карточки %q отвергнут: %v", kind, err)
			}
		})
	}
}

func TestValidateRejectsUnknownCardKind(t *testing.T) {
	err := validate(store.Changes{Cards: []store.Card{{ID: testUUID, Kind: "video"}}})
	if err == nil || !strings.Contains(err.Error(), "неизвестный вид карточки") {
		t.Fatalf("неизвестный вид принят или дал неясную ошибку: %v", err)
	}
}
