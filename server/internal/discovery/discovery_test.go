package discovery

import (
	"encoding/xml"
	"strings"
	"testing"

	"github.com/wolfy/server/internal/store"
)

func TestAtomEntryBecomesExtensibleContentItem(t *testing.T) {
	xmlText := `<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
<entry><id>https://standardebooks.org/ebooks/a/book</id><title>A Book</title>
<author><name>A. Writer</name></author><summary type="text">A short mystery.</summary>
<category scheme="https://standardebooks.org/vocab/subjects" term="Mystery"/>
<media:thumbnail url="https://standardebooks.org/cover.jpg"/>
<link rel="enclosure" type="application/epub+zip" title="Recommended compatible epub" href="https://standardebooks.org/book.epub"/>
</entry></feed>`
	var feed atomFeed
	if err := xml.NewDecoder(strings.NewReader(xmlText)).Decode(&feed); err != nil {
		t.Fatal(err)
	}
	item := itemOf(feed.Entries[0])
	if item.ContentType != "book" || item.Genres[0] != "Mystery" || item.DownloadURL == "" {
		t.Fatalf("неверная карточка: %+v", item)
	}
}

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
