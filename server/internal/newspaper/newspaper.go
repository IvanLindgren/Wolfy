// Package newspaper собирает свежий номер газеты из открытых лент новостей.
//
// Зачем это в приложении для чтения. Книга — длинная и не всегда по силам;
// новость короткая, злободневная и написана живым современным языком, которого
// в классике из Открытой библиотеки нет вовсе. Читатель, который сегодня не
// готов к главе романа, прочитает две заметки — и это те же слова, тот же
// разбор и та же колода.
//
// Ленты берутся публичные (RSS и Atom), и берёт их сервер, а не приложение.
// Причина та же, что у каталога книг: один канал наружу, один ограничитель
// частоты, один кэш и одно место, где чужой формат превращается в поля,
// понятные клиенту. Плюс браузер в чужую ленту всё равно не сходит — CORS.
//
// Полный текст заметки достаётся отдельным вызовом и только по ссылке из
// нашей же ленты: хост обязан быть в списке источников. Это не украшение —
// без такой проверки endpoint превратился бы в открытый прокси, которым
// можно ходить куда угодно от имени сервера.
package newspaper

import (
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"hash/fnv"
	"html"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
)

var (
	ErrUnavailable = errors.New("Новости сейчас недоступны")
	ErrUnknownHost = errors.New("Эта заметка не из нашей газеты")
	ErrEmpty       = errors.New("В заметке не нашлось текста")
)

const (
	// Лента новостей — это килобайты, а не мегабайты. Предел стоит не ради
	// диска, а ради памяти: сервису отведено 192 МБ на всё.
	maxFeedBytes = 2 << 20
	// Страница заметки с разметкой и рекламой редко бывает больше мегабайта.
	maxPageBytes = 4 << 20
	// Как долго номер считается свежим. Газета выходит не ежесекундно, и
	// перечитывать десяток чужих лент на каждое открытие раздела незачем.
	freshFor = 15 * time.Minute
)

// Topic — раздел газеты.
type Topic struct {
	Code  string `json:"code"`
	Title string `json:"title"`
	// Ленты раздела. Несколько на раздел нарочно: одна лента — это одна
	// редакция, а газета из одной редакции показывает одну картину мира.
	feeds []string
}

// Topics — разделы в том порядке, в каком они стоят в номере.
//
// Порядок газетный, а не алфавитный: первая полоса — мир, дальше то, что
// человек листает по дороге. Разделов семь, и это предел: восьмой уже не
// помещается в голову листающего.
var Topics = []Topic{
	{
		Code:  "world",
		Title: "Мир",
		feeds: []string{
			"https://feeds.bbci.co.uk/news/world/rss.xml",
			"https://www.theguardian.com/world/rss",
			"https://feeds.npr.org/1004/rss.xml",
		},
	},
	{
		Code:  "business",
		Title: "Экономика",
		feeds: []string{
			"https://feeds.bbci.co.uk/news/business/rss.xml",
			"https://www.theguardian.com/uk/business/rss",
		},
	},
	{
		Code:  "technology",
		Title: "Технологии",
		feeds: []string{
			"https://feeds.bbci.co.uk/news/technology/rss.xml",
			"https://feeds.arstechnica.com/arstechnica/technology-lab",
			"https://www.theguardian.com/uk/technology/rss",
		},
	},
	{
		Code:  "science",
		Title: "Наука",
		feeds: []string{
			"https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
			"https://www.sciencedaily.com/rss/top/science.xml",
			"https://feeds.npr.org/1007/rss.xml",
		},
	},
	{
		Code:  "health",
		Title: "Здоровье",
		feeds: []string{
			"https://feeds.bbci.co.uk/news/health/rss.xml",
			"https://feeds.npr.org/1128/rss.xml",
		},
	},
	{
		Code:  "culture",
		Title: "Культура",
		feeds: []string{
			"https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml",
			"https://www.theguardian.com/uk/culture/rss",
			"https://feeds.npr.org/1008/rss.xml",
		},
	},
	{
		Code:  "sport",
		Title: "Спорт",
		feeds: []string{
			"https://feeds.bbci.co.uk/sport/rss.xml",
			"https://www.theguardian.com/uk/sport/rss",
		},
	},
}

// Article — заметка в номере.
type Article struct {
	// Устойчивый номер: клиент по нему помнит прочитанное, а мы находим
	// заметку в кэше, не доверяя присланному адресу.
	ID string `json:"id"`
	// Код раздела, в котором заметка вышла.
	Topic string `json:"topic"`
	Title string `json:"title"`
	// Подзаголовок из ленты: одно-два предложения, которыми редакция сама
	// пересказала заметку.
	Summary string `json:"summary"`
	// Издание — по имени ленты, а не по домену: «BBC News», а не «bbci.co.uk».
	Source string `json:"source"`
	Author string `json:"author"`
	Link   string `json:"link"`
	// Когда вышла, в миллисекундах эпохи. Ноль — лента не сказала.
	Published int64  `json:"published"`
	ImageURL  string `json:"imageUrl"`
	// Сколько слов в подзаголовке: по ним полоса решает, какой заметке дать
	// колонку пошире.
	Words int `json:"words"`
}

// Section — полоса номера.
type Section struct {
	Topic    string    `json:"topic"`
	Title    string    `json:"title"`
	Articles []Article `json:"articles"`
}

// Issue — номер целиком.
type Issue struct {
	// Дата выпуска в ISO — её печатают под названием газеты.
	Date     string    `json:"date"`
	Sections []Section `json:"sections"`
}

// Reading — заметка, распознанная для читалки.
type Reading struct {
	Title      string   `json:"title"`
	Author     string   `json:"author"`
	Source     string   `json:"source"`
	Link       string   `json:"link"`
	Paragraphs []string `json:"paragraphs"`
	Words      int      `json:"words"`
}

type cached struct {
	articles []Article
	until    time.Time
}

type Service struct {
	http *http.Client
	mu   sync.Mutex
	// Кэш по адресу ленты, а не по разделу: одна и та же лента бывает в двух
	// разделах, и читать её дважды незачем.
	feeds map[string]cached
	// Хосты, с которых мы готовы забирать полный текст. Заполняется из
	// Topics один раз при создании службы.
	hosts map[string]bool
}

// New создаёт службу с клиентом, который не ходит во внутреннюю сеть.
//
// Транспорт настроен так же, как у загрузчика книг: без системного прокси и
// с проверкой адреса на каждом перенаправлении. Лента — это чужой URL, и
// перенаправить он может куда угодно.
func New(timeout time.Duration) *Service {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = publicDialContext(net.DefaultResolver)
	transport.MaxResponseHeaderBytes = 64 << 10
	transport.ResponseHeaderTimeout = timeout

	service := &Service{
		http: &http.Client{
			Transport: transport,
			Timeout:   timeout,
			CheckRedirect: func(request *http.Request, via []*http.Request) error {
				if len(via) >= 5 {
					return fmt.Errorf("%w: слишком много перенаправлений", ErrUnavailable)
				}
				return validateURL(request.URL)
			},
		},
		feeds: map[string]cached{},
		hosts: map[string]bool{},
	}

	for _, topic := range Topics {
		for _, feed := range topic.feeds {
			if parsed, err := url.Parse(feed); err == nil {
				service.allow(parsed.Hostname())
			}
		}
	}
	return service
}

// allow разрешает хост и его поддомены: лента живёт на feeds.bbci.co.uk, а
// сами заметки — на www.bbc.com и bbc.co.uk.
func (s *Service) allow(host string) {
	host = strings.ToLower(strings.TrimPrefix(host, "www."))
	if host == "" {
		return
	}
	s.hosts[host] = true
	// Ленты и статьи одного издания нередко живут на разных поддоменах.
	// Разрешаем регистрируемый домен целиком: «feeds.bbci.co.uk» →
	// «bbci.co.uk», «bbc.co.uk».
	if parts := strings.Split(host, "."); len(parts) > 2 {
		s.hosts[strings.Join(parts[len(parts)-2:], ".")] = true
		if len(parts) > 3 {
			s.hosts[strings.Join(parts[len(parts)-3:], ".")] = true
		}
	}
	switch {
	case strings.Contains(host, "bbci.co.uk"), strings.Contains(host, "bbc.co.uk"):
		s.hosts["bbc.com"] = true
		s.hosts["bbc.co.uk"] = true
	case strings.Contains(host, "npr.org"):
		s.hosts["npr.org"] = true
	case strings.Contains(host, "arstechnica.com"):
		s.hosts["arstechnica.com"] = true
	}
}

// Known сообщает, наша ли это заметка. Публично — потому что обработчик
// обязан отказать до похода в сеть, а не после.
func (s *Service) Known(rawURL string) bool {
	address, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || validateURL(address) != nil {
		return false
	}
	host := strings.ToLower(strings.TrimPrefix(address.Hostname(), "www."))
	if s.hosts[host] {
		return true
	}
	parts := strings.Split(host, ".")
	for at := 0; at+1 < len(parts); at++ {
		if s.hosts[strings.Join(parts[at:], ".")] {
			return true
		}
	}
	return false
}

// Issue собирает номер из выбранных разделов.
//
// Пустой список разделов — это весь номер: читатель, который ничего не
// выбирал, должен увидеть газету, а не пустую полосу с просьбой настроить.
func (s *Service) Issue(ctx context.Context, codes []string, perSection int) (Issue, error) {
	if perSection <= 0 {
		perSection = 6
	}
	if perSection > 30 {
		perSection = 30
	}

	wanted := chosen(codes)
	issue := Issue{Date: time.Now().UTC().Format("2006-01-02")}

	var failures int
	for _, topic := range Topics {
		if len(wanted) > 0 && !wanted[topic.Code] {
			continue
		}
		articles, err := s.topicArticles(ctx, topic)
		if err != nil {
			failures++
			continue
		}
		if len(articles) == 0 {
			continue
		}
		if len(articles) > perSection {
			articles = articles[:perSection]
		}
		issue.Sections = append(issue.Sections, Section{
			Topic:    topic.Code,
			Title:    topic.Title,
			Articles: articles,
		})
	}

	if len(issue.Sections) == 0 {
		if failures > 0 {
			return Issue{}, ErrUnavailable
		}
		return issue, nil
	}
	return issue, nil
}

func chosen(codes []string) map[string]bool {
	wanted := map[string]bool{}
	for _, code := range codes {
		code = strings.ToLower(strings.TrimSpace(code))
		if code != "" {
			wanted[code] = true
		}
	}
	return wanted
}

// topicArticles собирает раздел из всех его лент.
//
// Ленты читаются одновременно: разделов семь, лент на раздел до трёх, и
// последовательный обход превратил бы открытие газеты в двадцать походов в
// сеть подряд.
func (s *Service) topicArticles(ctx context.Context, topic Topic) ([]Article, error) {
	type result struct {
		articles []Article
		err      error
	}

	results := make([]result, len(topic.feeds))
	var wait sync.WaitGroup
	for at, feed := range topic.feeds {
		wait.Add(1)
		go func(at int, feed string) {
			defer wait.Done()
			articles, err := s.feed(ctx, feed, topic.Code)
			results[at] = result{articles: articles, err: err}
		}(at, feed)
	}
	wait.Wait()

	seen := map[string]bool{}
	merged := make([]Article, 0, 32)
	var lastErr error
	for _, item := range results {
		if item.err != nil {
			lastErr = item.err
			continue
		}
		for _, article := range item.articles {
			// Одна и та же новость приходит из двух лент под разными
			// адресами; ключ по заголовку убирает дубль надёжнее, чем ключ
			// по ссылке.
			key := strings.ToLower(strings.Join(strings.Fields(article.Title), " "))
			if key == "" || seen[key] {
				continue
			}
			seen[key] = true
			merged = append(merged, article)
		}
	}

	if len(merged) == 0 && lastErr != nil {
		return nil, lastErr
	}

	// Свежие сверху. Заметки без даты уходят вниз: сказать о них нечего, и
	// ставить их на первую полосу — врать о свежести.
	sort.SliceStable(merged, func(i, j int) bool {
		return merged[i].Published > merged[j].Published
	})
	return merged, nil
}

// feed читает одну ленту, помня прошлый ответ.
func (s *Service) feed(ctx context.Context, address, topic string) ([]Article, error) {
	s.mu.Lock()
	if item, ok := s.feeds[address]; ok && time.Now().Before(item.until) {
		articles := retopic(item.articles, topic)
		s.mu.Unlock()
		return articles, nil
	}
	s.mu.Unlock()

	articles, err := s.fetchFeed(ctx, address)
	if err != nil {
		// Вчерашняя лента лучше пустой полосы: источник мог моргнуть.
		s.mu.Lock()
		item, ok := s.feeds[address]
		s.mu.Unlock()
		if ok && len(item.articles) > 0 {
			return retopic(item.articles, topic), nil
		}
		return nil, err
	}

	s.mu.Lock()
	s.feeds[address] = cached{articles: articles, until: time.Now().Add(freshFor)}
	s.mu.Unlock()
	return retopic(articles, topic), nil
}

// retopic проставляет раздел копии: одна лента бывает в двух разделах, и
// портить кэш чужим кодом нельзя.
func retopic(articles []Article, topic string) []Article {
	out := make([]Article, len(articles))
	copy(out, articles)
	for at := range out {
		out[at].Topic = topic
	}
	return out
}

func (s *Service) fetchFeed(ctx context.Context, address string) ([]Article, error) {
	parsed, err := url.Parse(address)
	if err != nil || validateURL(parsed) != nil {
		return nil, ErrUnavailable
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return nil, ErrUnavailable
	}
	request.Header.Set("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.5")
	request.Header.Set("User-Agent", userAgent)

	response, err := s.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%w: источник ответил %d", ErrUnavailable, response.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(response.Body, maxFeedBytes))
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	return parseFeed(body)
}

// --- Разбор ленты -----------------------------------------------------------

// feedDocument читает и RSS, и Atom одной структурой.
//
// Отдельные разборщики на два формата — это две вещи, которые расходятся:
// поле, добавленное в один, забывают в другом. Форматы различаются именами
// элементов, а не смыслом, и `xml` спокойно читает оба набора сразу — лишние
// поля просто остаются пустыми.
type feedDocument struct {
	// RSS: <rss><channel><item>
	ChannelTitle string     `xml:"channel>title"`
	Items        []feedItem `xml:"channel>item"`
	// Atom: <feed><entry>
	FeedTitle string     `xml:"title"`
	Entries   []feedItem `xml:"entry"`
}

type feedItem struct {
	Title string `xml:"title"`
	// RSS держит адрес текстом элемента, Atom — атрибутом `href`, а
	// `encoding/xml` не даёт отобразить одно имя дважды. Поэтому элемент
	// один и несёт оба варианта: какой заполнится, тот формат и пришёл.
	Links       []feedLink `xml:"link"`
	Description string     `xml:"description"`
	Summary     string     `xml:"summary"`
	// `content:encoded` из RSS.
	Encoded string `xml:"encoded"`
	// `content` — и текст заметки в Atom, и картинка в `media:content`.
	// Пространства имён `encoding/xml` по умолчанию не различает, поэтому
	// оба варианта приходят сюда, и разбирает их `articleOf`.
	Contents  []feedContent `xml:"content"`
	PubDate   string        `xml:"pubDate"`
	Updated   string        `xml:"updated"`
	Published string        `xml:"published"`
	Creator   string        `xml:"creator"`
	Author    struct {
		Name string `xml:"name"`
	} `xml:"author"`
	GUID       string      `xml:"guid"`
	ID         string      `xml:"id"`
	Thumbnails []feedThumb `xml:"thumbnail"`
	Enclosures []feedThumb `xml:"enclosure"`
}

type feedLink struct {
	Href string `xml:"href,attr"`
	Rel  string `xml:"rel,attr"`
	Type string `xml:"type,attr"`
	Text string `xml:",chardata"`
}

type feedContent struct {
	URL  string `xml:"url,attr"`
	Type string `xml:"type,attr"`
	Text string `xml:",chardata"`
}

type feedThumb struct {
	URL  string `xml:"url,attr"`
	Type string `xml:"type,attr"`
}

func parseFeed(body []byte) ([]Article, error) {
	var document feedDocument
	decoder := xml.NewDecoder(strings.NewReader(string(body)))
	// Ленты нередко объявляют windows-1251 или просто врут о кодировке.
	// Читаем как есть: заголовки в них всё равно UTF-8, а падать из-за
	// объявления кодировки — терять весь раздел.
	decoder.CharsetReader = func(_ string, input io.Reader) (io.Reader, error) {
		return input, nil
	}
	decoder.Strict = false
	if err := decoder.Decode(&document); err != nil {
		return nil, fmt.Errorf("%w: лента не разобралась", ErrUnavailable)
	}

	source := strings.TrimSpace(document.ChannelTitle)
	if source == "" {
		source = strings.TrimSpace(document.FeedTitle)
	}

	items := document.Items
	if len(items) == 0 {
		items = document.Entries
	}

	articles := make([]Article, 0, len(items))
	for _, item := range items {
		article := articleOf(item, source)
		if article.Title == "" || article.Link == "" {
			continue
		}
		articles = append(articles, article)
	}
	return articles, nil
}

func articleOf(item feedItem, source string) Article {
	link := ""
	for _, candidate := range item.Links {
		if candidate.Rel != "" && candidate.Rel != "alternate" {
			continue
		}
		if value := strings.TrimSpace(candidate.Href); value != "" {
			link = value
			break
		}
		if value := strings.TrimSpace(candidate.Text); value != "" {
			link = value
			break
		}
	}
	if link == "" {
		link = strings.TrimSpace(item.GUID)
	}
	if !strings.HasPrefix(strings.ToLower(link), "https://") {
		return Article{}
	}

	summary := firstNonEmpty(item.Description, item.Summary, item.Encoded, atomContent(item))
	summary = plainText(summary)
	summary = trimWords(summary, 60)

	author := strings.TrimSpace(item.Creator)
	if author == "" {
		author = strings.TrimSpace(item.Author.Name)
	}

	return Article{
		ID:        identifier(link),
		Title:     strings.TrimSpace(plainText(item.Title)),
		Summary:   summary,
		Source:    source,
		Author:    author,
		Link:      link,
		Published: published(item),
		ImageURL:  image(item),
		Words:     len(strings.Fields(summary)),
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

func published(item feedItem) int64 {
	for _, value := range []string{item.PubDate, item.Published, item.Updated} {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		for _, layout := range []string{
			time.RFC1123Z,
			time.RFC1123,
			time.RFC3339,
			"Mon, 2 Jan 2006 15:04:05 -0700",
			"Mon, 2 Jan 2006 15:04:05 MST",
			"2006-01-02T15:04:05Z07:00",
			"2006-01-02 15:04:05",
		} {
			if parsed, err := time.Parse(layout, value); err == nil {
				return parsed.UnixMilli()
			}
		}
	}
	return 0
}

// atomContent — текст заметки из Atom. У `media:content` текста не бывает,
// поэтому пустой элемент с одним адресом сюда не попадает.
func atomContent(item feedItem) string {
	for _, content := range item.Contents {
		if text := strings.TrimSpace(content.Text); text != "" {
			return text
		}
	}
	return ""
}

func image(item feedItem) string {
	for _, thumb := range item.Thumbnails {
		if address := imageURL(thumb.URL, thumb.Type); address != "" {
			return address
		}
	}
	for _, content := range item.Contents {
		if address := imageURL(content.URL, content.Type); address != "" {
			return address
		}
	}
	for _, thumb := range item.Enclosures {
		if address := imageURL(thumb.URL, thumb.Type); address != "" {
			return address
		}
	}
	return ""
}

func imageURL(address, mime string) string {
	address = strings.TrimSpace(address)
	if !strings.HasPrefix(strings.ToLower(address), "https://") {
		return ""
	}
	if mime != "" && !strings.HasPrefix(mime, "image/") {
		return ""
	}
	return address
}

// identifier — устойчивый короткий номер заметки по её адресу.
func identifier(link string) string {
	sum := fnv.New64a()
	_, _ = sum.Write([]byte(link))
	return fmt.Sprintf("%016x", sum.Sum64())
}

// trimWords обрезает пересказ по словам, а не по буквам: обрыв посреди слова
// читается как ошибка вёрстки.
func trimWords(text string, limit int) string {
	words := strings.Fields(text)
	if len(words) <= limit {
		return strings.Join(words, " ")
	}
	return strings.Join(words[:limit], " ") + "…"
}

// --- Полный текст заметки ---------------------------------------------------

const userAgent = "Wolfy/1.0 (reader; +https://wolfy.citavuk.ru)"

// Read достаёт текст заметки для читалки.
//
// Только с наших источников: `Known` проверяет хост до похода в сеть. Без
// этого endpoint стал бы открытым прокси, а список источников — единственное,
// что отличает газету от «скачай мне что угодно».
func (s *Service) Read(ctx context.Context, rawURL string) (Reading, error) {
	if !s.Known(rawURL) {
		return Reading{}, ErrUnknownHost
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimSpace(rawURL), nil)
	if err != nil {
		return Reading{}, ErrUnavailable
	}
	request.Header.Set("Accept", "text/html,application/xhtml+xml")
	request.Header.Set("User-Agent", userAgent)

	response, err := s.http.Do(request)
	if err != nil {
		return Reading{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return Reading{}, fmt.Errorf("%w: источник ответил %d", ErrUnavailable, response.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(response.Body, maxPageBytes))
	if err != nil {
		return Reading{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}

	page := string(body)
	paragraphs := paragraphsOf(page)
	if len(paragraphs) == 0 {
		return Reading{}, ErrEmpty
	}

	words := 0
	for _, paragraph := range paragraphs {
		words += len(strings.Fields(paragraph))
	}

	return Reading{
		Title:      pageTitle(page),
		Source:     sourceName(rawURL),
		Link:       strings.TrimSpace(rawURL),
		Paragraphs: paragraphs,
		Words:      words,
	}, nil
}

func sourceName(rawURL string) string {
	address, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	return strings.TrimPrefix(strings.ToLower(address.Hostname()), "www.")
}

// pageTitle достаёт заголовок страницы и снимает хвост издания:
// «Заголовок - BBC News» — это заголовок, а не заголовок с издательством.
func pageTitle(page string) string {
	start := strings.Index(strings.ToLower(page), "<title")
	if start < 0 {
		return ""
	}
	open := strings.Index(page[start:], ">")
	if open < 0 {
		return ""
	}
	rest := page[start+open+1:]
	end := strings.Index(strings.ToLower(rest), "</title>")
	if end < 0 {
		return ""
	}
	title := strings.TrimSpace(html.UnescapeString(rest[:end]))
	for _, separator := range []string{" - ", " | ", " — ", " :: "} {
		if at := strings.LastIndex(title, separator); at > 20 {
			title = strings.TrimSpace(title[:at])
			break
		}
	}
	return title
}

// paragraphsOf вытаскивает читаемый текст заметки.
//
// Разбор нарочно грубый: абзацем считается содержимое `<p>`, и остаётся оно
// только если похоже на прозу — достаточно длинное и с пробелами. На новостных
// сайтах этого хватает, а полноценный разбор DOM потребовал бы зависимости и
// всё равно не был бы точнее на чужой вёрстке.
//
// Всё, что стоит внутри `script`, `style` и подобного, выбрасывается до
// разбора: там встречается и `<p>` внутри JSON-строки.
func paragraphsOf(page string) []string {
	page = dropElements(page, "script", "style", "noscript", "svg", "template", "form")

	paragraphs := make([]string, 0, 32)
	seen := map[string]bool{}
	lower := strings.ToLower(page)

	at := 0
	for {
		open := strings.Index(lower[at:], "<p")
		if open < 0 {
			break
		}
		open += at
		// «<pre», «<picture» — не абзацы.
		if open+2 < len(page) && page[open+2] != '>' && page[open+2] != ' ' {
			at = open + 2
			continue
		}
		content := strings.Index(lower[open:], ">")
		if content < 0 {
			break
		}
		content += open + 1
		close := strings.Index(lower[content:], "</p>")
		if close < 0 {
			break
		}
		close += content

		text := plainText(page[content:close])
		at = close + 4

		if !prose(text) || seen[text] {
			continue
		}
		seen[text] = true
		paragraphs = append(paragraphs, text)
	}
	return paragraphs
}

// prose отсекает подписи, кнопки и служебные строки.
func prose(text string) bool {
	if len(text) < 60 {
		return false
	}
	if strings.Count(text, " ") < 6 {
		return false
	}
	lower := strings.ToLower(text)
	for _, junk := range []string{
		"cookie", "subscribe to", "sign up for", "follow us", "all rights reserved",
		"advertisement", "share this", "read more about",
	} {
		if strings.Contains(lower, junk) {
			return false
		}
	}
	return true
}

// dropElements выбрасывает элементы вместе с содержимым.
func dropElements(page string, names ...string) string {
	for _, name := range names {
		open := "<" + name
		close := "</" + name + ">"
		for {
			lower := strings.ToLower(page)
			start := strings.Index(lower, open)
			if start < 0 {
				break
			}
			end := strings.Index(lower[start:], close)
			if end < 0 {
				page = page[:start]
				break
			}
			page = page[:start] + " " + page[start+end+len(close):]
		}
	}
	return page
}

// plainText снимает разметку и превращает сущности в буквы.
func plainText(markup string) string {
	var out strings.Builder
	out.Grow(len(markup))

	inside := false
	for _, symbol := range markup {
		switch {
		case symbol == '<':
			inside = true
			out.WriteByte(' ')
		case symbol == '>':
			inside = false
		case !inside:
			out.WriteRune(symbol)
		}
	}

	return strings.Join(strings.Fields(html.UnescapeString(out.String())), " ")
}

// --- Безопасность адреса ----------------------------------------------------

// validateURL повторяет правило загрузчика книг: только публичный HTTPS.
func validateURL(address *url.URL) error {
	if address == nil || address.Scheme != "https" || address.Hostname() == "" {
		return ErrUnavailable
	}
	return nil
}

// publicDialContext не даёт соединиться с внутренним адресом, даже если имя
// в него разрешилось после наших проверок.
func publicDialContext(resolver *net.Resolver) func(context.Context, string, string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}

	return func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		addresses, err := resolver.LookupNetIP(ctx, "ip", host)
		if err != nil {
			return nil, err
		}
		for _, candidate := range addresses {
			if !publicIP(candidate.Unmap()) {
				return nil, fmt.Errorf("%w: адрес во внутренней сети", ErrUnavailable)
			}
		}
		return dialer.DialContext(ctx, network, net.JoinHostPort(host, port))
	}
}

func publicIP(ip netip.Addr) bool {
	if !ip.IsValid() {
		return false
	}
	if ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	// Разделяемое адресное пространство операторов и «этот хост».
	for _, prefix := range []string{"100.64.0.0/10", "0.0.0.0/8", "192.0.0.0/24", "fc00::/7"} {
		if netip.MustParsePrefix(prefix).Contains(ip) {
			return false
		}
	}
	return true
}
