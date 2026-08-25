package newspaper

import (
	"strings"
	"testing"
	"time"
)

const rssFeed = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"
     xmlns:dc="http://purl.org/dc/elements/1.1/">
  <channel>
    <title>BBC News</title>
    <item>
      <title>Ocean currents are slowing, study finds</title>
      <link>https://www.bbc.com/news/science-1</link>
      <description>&lt;p&gt;Researchers say the change is measurable.&lt;/p&gt;</description>
      <pubDate>Mon, 24 Aug 2026 09:15:00 GMT</pubDate>
      <dc:creator>Jane Doe</dc:creator>
      <media:thumbnail url="https://ichef.bbci.co.uk/one.jpg"/>
    </item>
    <item>
      <title>Second story</title>
      <link>https://www.bbc.com/news/science-2</link>
      <description>Short summary.</description>
      <pubDate>Tue, 25 Aug 2026 07:00:00 GMT</pubDate>
    </item>
    <item>
      <title>Story without a link</title>
      <description>No link at all.</description>
    </item>
  </channel>
</rss>`

const atomFeed = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Ars Technica</title>
  <entry>
    <title>A new chip lands</title>
    <link rel="alternate" href="https://arstechnica.com/one"/>
    <summary>The chip is fast and hot.</summary>
    <published>2026-08-25T10:00:00Z</published>
    <author><name>John Roe</name></author>
  </entry>
  <entry>
    <title>Content instead of summary</title>
    <link href="https://arstechnica.com/two"/>
    <content>Full text of the entry lives here.</content>
    <updated>2026-08-24T10:00:00Z</updated>
  </entry>
</feed>`

func TestParseRSS(t *testing.T) {
	articles, err := parseFeed([]byte(rssFeed))
	if err != nil {
		t.Fatalf("лента не разобралась: %v", err)
	}
	// Заметка без ссылки выброшена: открыть её нечем.
	if len(articles) != 2 {
		t.Fatalf("ожидали две заметки, получили %d", len(articles))
	}

	first := articles[0]
	if first.Title != "Ocean currents are slowing, study finds" {
		t.Errorf("заголовок: %q", first.Title)
	}
	// Разметка из описания снята, сущности раскрыты.
	if first.Summary != "Researchers say the change is measurable." {
		t.Errorf("подзаголовок с разметкой: %q", first.Summary)
	}
	if first.Source != "BBC News" {
		t.Errorf("издание: %q", first.Source)
	}
	if first.Author != "Jane Doe" {
		t.Errorf("автор: %q", first.Author)
	}
	if first.ImageURL != "https://ichef.bbci.co.uk/one.jpg" {
		t.Errorf("картинка: %q", first.ImageURL)
	}
	if first.Published == 0 {
		t.Error("дата выпуска не разобралась")
	}
	if first.Words != 6 {
		t.Errorf("слов в подзаголовке: %d", first.Words)
	}
	if first.ID == "" || first.ID == articles[1].ID {
		t.Errorf("номера заметок не различаются: %q и %q", first.ID, articles[1].ID)
	}
}

// Atom держит адрес в атрибуте, а не в тексте элемента: пока `link` и `href`
// разбирались двумя полями с одним именем, `encoding/xml` молча терял ссылку.
func TestParseAtom(t *testing.T) {
	articles, err := parseFeed([]byte(atomFeed))
	if err != nil {
		t.Fatalf("лента не разобралась: %v", err)
	}
	if len(articles) != 2 {
		t.Fatalf("ожидали две заметки, получили %d", len(articles))
	}
	if articles[0].Link != "https://arstechnica.com/one" {
		t.Errorf("ссылка из атрибута: %q", articles[0].Link)
	}
	if articles[0].Source != "Ars Technica" {
		t.Errorf("издание: %q", articles[0].Source)
	}
	if articles[1].Summary != "Full text of the entry lives here." {
		t.Errorf("текст из <content>: %q", articles[1].Summary)
	}
}

func TestParseRefusesNonHTTPS(t *testing.T) {
	feed := strings.ReplaceAll(rssFeed, "https://www.bbc.com", "http://www.bbc.com")
	articles, err := parseFeed([]byte(feed))
	if err != nil {
		t.Fatalf("лента не разобралась: %v", err)
	}
	if len(articles) != 0 {
		t.Errorf("заметки по http приняты: %d", len(articles))
	}
}

func TestTrimWordsCutsOnWordBoundary(t *testing.T) {
	if got := trimWords("one two three four", 2); got != "one two…" {
		t.Errorf("обрезка: %q", got)
	}
	if got := trimWords("one two", 5); got != "one two" {
		t.Errorf("короткий текст обрезан: %q", got)
	}
}

// Endpoint полного текста ходит в сеть от имени сервера. Единственное, что
// отличает его от открытого прокси, — список хостов, и он обязан работать.
func TestKnownAcceptsOnlyOurSources(t *testing.T) {
	service := New(time.Second)

	for _, address := range []string{
		"https://www.bbc.com/news/world-1",
		"https://bbc.co.uk/news/world-1",
		"https://feeds.bbci.co.uk/news/world/rss.xml",
		"https://www.theguardian.com/world/2026/aug/25/story",
		"https://arstechnica.com/one",
		"https://www.npr.org/2026/08/25/story",
	} {
		if !service.Known(address) {
			t.Errorf("свой источник отвергнут: %s", address)
		}
	}

	for _, address := range []string{
		"https://evil.example/steal",
		"http://www.bbc.com/news/world-1",    // не HTTPS
		"https://127.0.0.1/admin",            // петля
		"https://metadata.google.internal/x", // метаданные облака
		"https://bbc.com.evil.example/x",     // чужой домен с нашим префиксом
		"",
	} {
		if service.Known(address) {
			t.Errorf("чужой адрес принят: %s", address)
		}
	}
}

const articlePage = `<!doctype html>
<html><head><title>Ocean currents are slowing, study finds - BBC News</title>
<style>p { color: red; }</style>
<script>var junk = "<p>this is a long fake paragraph inside a script tag</p>";</script>
</head>
<body>
<nav><p>Home News Sport Weather iPlayer Sounds</p></nav>
<article>
<p>The Atlantic overturning circulation has weakened measurably over the past
   two decades, according to a study published on Monday in a peer reviewed
   journal that tracked temperature and salinity.</p>
<p>Researchers used a combination of satellite measurements and moored sensors
   placed across the basin, building a record that stretches back further than
   any previous attempt at the same question.</p>
<p>Short.</p>
<p>Subscribe to our newsletter for more stories like this one delivered to your inbox every morning.</p>
<pre>not a paragraph</pre>
</article>
</body></html>`

func TestParagraphsKeepProseAndDropChrome(t *testing.T) {
	paragraphs := paragraphsOf(articlePage)
	if len(paragraphs) != 2 {
		t.Fatalf("ожидали два абзаца прозы, получили %d: %q", len(paragraphs), paragraphs)
	}
	if !strings.HasPrefix(paragraphs[0], "The Atlantic overturning circulation") {
		t.Errorf("первый абзац: %q", paragraphs[0])
	}
	// Перенос строки внутри абзаца схлопнут в пробел.
	if strings.Contains(paragraphs[0], "\n") {
		t.Errorf("абзац сохранил вёрстку исходника: %q", paragraphs[0])
	}
	for _, paragraph := range paragraphs {
		if strings.Contains(paragraph, "fake paragraph") {
			t.Error("содержимое <script> попало в текст")
		}
		if strings.Contains(strings.ToLower(paragraph), "subscribe to") {
			t.Error("служебная строка попала в текст")
		}
		if strings.Contains(paragraph, "Home News Sport") {
			t.Error("меню попало в текст")
		}
	}
}

func TestPageTitleDropsPublisherTail(t *testing.T) {
	if got := pageTitle(articlePage); got != "Ocean currents are slowing, study finds" {
		t.Errorf("заголовок страницы: %q", got)
	}
}

func TestPlainTextUnescapesAndCollapses(t *testing.T) {
	got := plainText("  <b>Cost</b>&nbsp;&amp;   value \n of&#39;it  ")
	if got != "Cost & value of'it" {
		t.Errorf("текст: %q", got)
	}
}

func TestChosenIgnoresBlanks(t *testing.T) {
	wanted := chosen([]string{" world ", "", "SPORT"})
	if len(wanted) != 2 || !wanted["world"] || !wanted["sport"] {
		t.Errorf("выбор разделов: %v", wanted)
	}
}

// Полоса не должна показывать одну новость дважды: одна и та же заметка
// приходит из BBC и Guardian под разными адресами.
func TestRetopicDoesNotShareBacking(t *testing.T) {
	articles := []Article{{Title: "One", Topic: "world"}}
	copied := retopic(articles, "sport")
	if articles[0].Topic != "world" {
		t.Error("кэш испорчен чужим разделом")
	}
	if copied[0].Topic != "sport" {
		t.Errorf("раздел копии: %q", copied[0].Topic)
	}
}
