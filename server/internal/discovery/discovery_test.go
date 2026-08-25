package discovery

import (
	"testing"

	"github.com/wolfy/server/internal/store"
)

func TestLikedGenreRisesInRecommendations(t *testing.T) {
	mystery := Item{ID: "m", Title: "Hidden Clue", Summary: "A detective solves a mystery", Genres: []string{"Mystery"}}
	travel := Item{ID: "t", Title: "Long Road", Summary: "A journey across a country", Genres: []string{"Travel"}}
	reaction := store.DiscoveryReaction{ItemID: "old", Liked: true, Embedding: vectorOf(mystery)}
	ranked := rank([]Item{travel, mystery}, store.DiscoveryProfile{
		EnglishLevel: "B2", Genres: []string{"Mystery"}, OnboardingComplete: true,
	}, []store.DiscoveryReaction{reaction}, "reader")
	if ranked[0].ID != "m" {
		t.Fatalf("похожая книга не поднялась: %+v", ranked)
	}
}
