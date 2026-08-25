// Package discovery формирует персональную вертикальную ленту материалов.
// Источник отделён от ранжирования: сегодня это книги Project Gutenberg,
// позже тем же контрактом добавятся статьи и журналы.
package discovery

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/fnv"
	"html"
	"io"
	"math"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/wolfy/server/internal/gate"
	"github.com/wolfy/server/internal/store"
)

var (
	ErrOnboarding  = errors.New("сначала выберите уровень английского и жанры")
	ErrNotFound    = errors.New("материал не найден")
	ErrTooLarge    = errors.New("файл книги слишком большой")
	ErrInvalidBook = errors.New("источник вернул не EPUB")
	ErrBusy        = errors.New("сервер уже загружает другую книгу; попробуйте ещё раз")
)

const (
	embeddingSize = 96
	maxBookBytes  = 64 << 20
)

type Item struct {
	ID          string   `json:"id"`
	ContentType string   `json:"contentType"`
	Title       string   `json:"title"`
	Author      string   `json:"author"`
	Summary     string   `json:"summary"`
	Genres      []string `json:"genres"`
	Level       string   `json:"level"`
	CoverURL    string   `json:"coverUrl"`
	PageURL     string   `json:"pageUrl"`
	DownloadURL string   `json:"-"`
	Liked       bool     `json:"liked"`
	Added       bool     `json:"added"`
	Score       float64  `json:"-"`
	embedding   []float64
}

type Page struct {
	Items      []Item `json:"items"`
	NextCursor int    `json:"nextCursor"`
	HasMore    bool   `json:"hasMore"`
}

type Download struct {
	Bytes    []byte
	FileName string
	Item     Item
}

type Repository interface {
	DiscoveryProfile(context.Context, string) (store.DiscoveryProfile, error)
	SaveDiscoveryProfile(context.Context, string, store.DiscoveryProfile) error
	DiscoveryReactions(context.Context, string) ([]store.DiscoveryReaction, error)
	SaveDiscoveryReaction(context.Context, string, store.DiscoveryReaction) error
}

type Source interface {
	Items(context.Context) ([]Item, error)
}

type Service struct {
	repository Repository
	source     Source
	http       *http.Client
	gate       *gate.Gate
}

func New(repository Repository, source Source, timeout time.Duration) *Service {
	return &Service{
		repository: repository,
		source:     source,
		http:       trustedHTTPClient(timeout),
		gate:       gate.Download,
	}
}

// NewWithGate — для тестов с кастомным gate.
func NewWithGate(repository Repository, source Source, timeout time.Duration, g *gate.Gate) *Service {
	s := New(repository, source, timeout)
	s.gate = g
	return s
}

func (s *Service) Profile(ctx context.Context, userID string) (store.DiscoveryProfile, error) {
	return s.repository.DiscoveryProfile(ctx, userID)
}

func (s *Service) SaveProfile(ctx context.Context, userID string, profile store.DiscoveryProfile) error {
	profile.EnglishLevel = strings.ToUpper(strings.TrimSpace(profile.EnglishLevel))
	if !validLevel(profile.EnglishLevel) {
		return errors.New("уровень должен быть от A1 до C2")
	}
	profile.Genres = cleanGenres(profile.Genres)
	if len(profile.Genres) == 0 {
		return errors.New("выберите хотя бы один жанр")
	}
	profile.OnboardingComplete = true
	return s.repository.SaveDiscoveryProfile(ctx, userID, profile)
}

func (s *Service) Feed(ctx context.Context, userID string, cursor, limit int) (Page, error) {
	profile, err := s.repository.DiscoveryProfile(ctx, userID)
	if err != nil {
		return Page{}, err
	}
	if !profile.OnboardingComplete {
		return Page{}, ErrOnboarding
	}
	items, err := s.source.Items(ctx)
	if err != nil {
		return Page{}, err
	}
	reactions, err := s.repository.DiscoveryReactions(ctx, userID)
	if err != nil {
		return Page{}, err
	}

	ranked := rank(items, profile, reactions, userID)
	if cursor < 0 {
		cursor = 0
	}
	if limit < 1 || limit > 50 {
		limit = 15
	}
	if cursor > len(ranked) {
		cursor = len(ranked)
	}
	end := min(cursor+limit, len(ranked))
	page := Page{Items: ranked[cursor:end], NextCursor: end, HasMore: end < len(ranked)}
	return page, nil
}

func (s *Service) Like(ctx context.Context, userID, itemID string) error {
	item, err := s.find(ctx, itemID)
	if err != nil {
		return err
	}
	return s.saveReaction(ctx, userID, item, true, false)
}

// DownloadAndAdd сначала получает файл, и лишь затем записывает добавление.
// Неудачная загрузка не должна превращаться в успешный лайк. Использует
// общий gate.Download, чтобы не держать в памяти несколько 64 MiB книг
// одновременно вместе с remotebook.
func (s *Service) DownloadAndAdd(ctx context.Context, userID, itemID string) (Download, error) {
	item, err := s.find(ctx, itemID)
	if err != nil {
		return Download{}, err
	}
	if err := trustedDownload(item.DownloadURL); err != nil {
		return Download{}, err
	}
	select {
	case <-ctx.Done():
		return Download{}, ctx.Err()
	default:
	}
	if s.gate != nil {
		if !s.gate.TryAcquire() {
			return Download{}, ErrBusy
		}
		defer s.gate.Release()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, item.DownloadURL, nil)
	if err != nil {
		return Download{}, fmt.Errorf("подготовка загрузки: %w", err)
	}
	req.Header.Set("User-Agent", catalogueUserAgent)
	response, err := s.http.Do(req)
	if err != nil {
		return Download{}, fmt.Errorf("загрузка книги: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return Download{}, fmt.Errorf("Гутенберг ответил %d", response.StatusCode)
	}
	if response.ContentLength > maxBookBytes {
		return Download{}, ErrTooLarge
	}
	bytes, err := io.ReadAll(io.LimitReader(response.Body, maxBookBytes+1))
	if err != nil {
		return Download{}, fmt.Errorf("чтение книги: %w", err)
	}
	if len(bytes) > maxBookBytes {
		return Download{}, ErrTooLarge
	}
	// EPUB — ZIP-контейнер. Проверка сигнатуры не доказывает, что вся книга
	// исправна, но не даёт сохранить HTML ошибки или страницу защиты как
	// книгу с расширением .epub.
	if len(bytes) < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4 {
		return Download{}, ErrInvalidBook
	}
	if err := s.saveReaction(ctx, userID, item, true, true); err != nil {
		return Download{}, err
	}
	name := path.Base(strings.TrimSuffix(mustURL(item.DownloadURL).Path, "/"))
	if !strings.HasSuffix(strings.ToLower(name), ".epub") {
		name = safeName(item.Title) + ".epub"
	}
	return Download{Bytes: bytes, FileName: name, Item: item}, nil
}

func (s *Service) find(ctx context.Context, itemID string) (Item, error) {
	items, err := s.source.Items(ctx)
	if err != nil {
		return Item{}, err
	}
	for _, item := range items {
		if item.ID == itemID {
			return item, nil
		}
	}
	return Item{}, ErrNotFound
}

func (s *Service) saveReaction(ctx context.Context, userID string, item Item, liked, added bool) error {
	return s.repository.SaveDiscoveryReaction(ctx, userID, store.DiscoveryReaction{
		ItemID: item.ID, ContentType: item.ContentType, Liked: liked, Added: added,
		Embedding: vectorOf(item), Genres: item.Genres,
	})
}

func rank(items []Item, profile store.DiscoveryProfile, reactions []store.DiscoveryReaction, userID string) []Item {
	liked := make(map[string]store.DiscoveryReaction, len(reactions))
	centroid := make([]float64, embeddingSize)
	var weight float64
	for _, reaction := range reactions {
		liked[reaction.ItemID] = reaction
		if !reaction.Liked || len(reaction.Embedding) != embeddingSize {
			continue
		}
		w := 1.0
		if reaction.Added {
			w = 1.6
		}
		for index, value := range reaction.Embedding {
			centroid[index] += value * w
		}
		weight += w
	}
	if weight > 0 {
		for index := range centroid {
			centroid[index] /= weight
		}
		normalize(centroid)
	}

	wanted := make(map[string]bool, len(profile.Genres))
	for _, genre := range profile.Genres {
		wanted[strings.ToLower(genre)] = true
	}
	result := make([]Item, len(items))
	copy(result, items)
	for index := range result {
		item := &result[index]
		item.embedding = vectorOf(*item)
		for _, genre := range item.Genres {
			if wanted[strings.ToLower(genre)] {
				item.Score += 0.75
			}
		}
		if weight > 0 {
			item.Score += cosine(item.embedding, centroid) * 1.8
		}
		item.Score += levelScore(profile.EnglishLevel, item.Level)
		item.Score += stableJitter(userID, item.ID)
		if reaction, ok := liked[item.ID]; ok {
			item.Liked = reaction.Liked
			item.Added = reaction.Added
			// Уже увиденное не исчезает совсем, но уступает новому.
			item.Score -= 0.45
		}
	}
	sort.SliceStable(result, func(i, j int) bool { return result[i].Score > result[j].Score })
	return result
}

func vectorOf(item Item) []float64 {
	vector := make([]float64, embeddingSize)
	text := strings.Join(append([]string{item.Title, item.Author, item.Summary}, item.Genres...), " ")
	for _, token := range strings.FieldsFunc(strings.ToLower(text), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	}) {
		if len([]rune(token)) < 2 {
			continue
		}
		hash := fnv.New64a()
		_, _ = hash.Write([]byte(token))
		value := hash.Sum64()
		index := int(value % embeddingSize)
		sign := 1.0
		if value&(1<<63) != 0 {
			sign = -1
		}
		vector[index] += sign
	}
	normalize(vector)
	return vector
}

func normalize(vector []float64) {
	var length float64
	for _, value := range vector {
		length += value * value
	}
	if length == 0 {
		return
	}
	length = math.Sqrt(length)
	for index := range vector {
		vector[index] /= length
	}
}

func cosine(left, right []float64) float64 {
	if len(left) != len(right) {
		return 0
	}
	var result float64
	for index := range left {
		result += left[index] * right[index]
	}
	return result
}

func stableJitter(userID, itemID string) float64 {
	sum := sha256.Sum256([]byte(userID + "\x00" + itemID))
	return float64(binary.BigEndian.Uint16(sum[:2])) / 65535.0 * 0.08
}

func levelScore(wanted, actual string) float64 {
	levels := map[string]int{"A1": 1, "A2": 2, "B1": 3, "B2": 4, "C1": 5, "C2": 6}
	distance := levels[actual] - levels[wanted]
	if distance < 0 {
		distance = -distance
	}
	return 0.3 - float64(distance)*0.12
}

func validLevel(level string) bool {
	switch level {
	case "A1", "A2", "B1", "B2", "C1", "C2":
		return true
	default:
		return false
	}
}

func cleanGenres(genres []string) []string {
	seen := map[string]bool{}
	result := make([]string, 0, min(len(genres), 12))
	for _, genre := range genres {
		genre = strings.TrimSpace(genre)
		key := strings.ToLower(genre)
		if genre == "" || len([]rune(genre)) > 60 || seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, genre)
		if len(result) == 12 {
			break
		}
	}
	return result
}

// catalogueUserAgent — кто мы для чужого сервиса.
//
// Гутенберг и Gutendex просят представляться: анонимный поток запросов они
// вправе считать выкачиванием и закрыть. Адрес в подписи — чтобы было куда
// написать, если мы им мешаем.
const catalogueUserAgent = "Wolfy/1.0 (+https://wolfy.citavuk.ru)"

// trustedDownload — куда позволено ходить за файлом книги.
//
// Список хостов, а не проверка «это https»: адрес приходит из чужого каталога,
// и без него ответ Gutendex превращал бы наш сервер в загрузчик чего угодно по
// чужому указанию. Файлы Гутенберга живут на www.gutenberg.org, а сам домен
// используется для перенаправления с коротких адресов вида /ebooks/84.epub3.
func trustedDownload(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" {
		return errors.New("небезопасный адрес загрузки")
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if host != "gutenberg.org" && host != "www.gutenberg.org" {
		return errors.New("небезопасный адрес загрузки")
	}
	return nil
}

// trustedCatalogue — куда позволено ходить за списком книг.
//
// Отдельно от загрузки: метаданные отдаёт Gutendex, а файлы — Гутенберг, и
// смешивать эти списки значит разрешить каждому из них то, что нужно только
// другому.
func trustedCatalogue(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" {
		return errors.New("небезопасный адрес каталога")
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if host != "gutendex.com" && host != "www.gutendex.com" {
		return errors.New("небезопасный адрес каталога")
	}
	return nil
}

func trustedHTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("слишком много перенаправлений при загрузке книги")
			}
			return trustedDownload(req.URL.String())
		},
	}
}

// catalogueHTTPClient ходит за метаданными и не умеет качать книги.
func catalogueHTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("слишком много перенаправлений каталога")
			}
			return trustedCatalogue(req.URL.String())
		},
	}
}

func mustURL(raw string) *url.URL {
	u, _ := url.Parse(raw)
	return u
}

func safeName(title string) string {
	name := strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-' || r == '_' || r == ' ' {
			return r
		}
		return '_'
	}, title)
	return strings.TrimSpace(name)
}

var tags = regexp.MustCompile(`<[^>]+>`)

func stableID(sourceID string) string {
	if sourceID == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(sourceID))
	return fmt.Sprintf("bk-%x", sum[:12])
}

func cleanHTML(value string) string {
	value = html.UnescapeString(tags.ReplaceAllString(value, " "))
	return strings.Join(strings.Fields(value), " ")
}

func estimatedLevel(summary string) string {
	words := strings.Fields(summary)
	if len(words) == 0 {
		return "B2"
	}
	var long int
	for _, word := range words {
		if len([]rune(strings.Trim(word, ".,;:!?—–\"'“”"))) >= 9 {
			long++
		}
	}
	ratio := float64(long) / float64(len(words))
	switch {
	case ratio < 0.08:
		return "B1"
	case ratio < 0.17:
		return "B2"
	case ratio < 0.25:
		return "C1"
	default:
		return "C2"
	}
}
