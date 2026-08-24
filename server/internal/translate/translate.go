// Package translate — контекстный перевод через DeepL с кэшем в базе.
//
// Это единственное, ради чего карточка слова вообще ходит в сеть. Всё
// остальное — начальная форма, часть речи, разбор окончания, частотность —
// считается на устройстве и появляется мгновенно; перевод приезжает в уже
// открытую карточку.
//
// Кэш здесь не оптимизация, а необходимость. Читатели одной книги нажимают на
// одни и те же слова в одних и тех же предложениях, и без кэша счёт от DeepL
// рос бы линейно по числу читателей при неизменном числе разных фраз.
package translate

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrTooLarge — запрос превышает лимит размера до провайдера.
var ErrTooLarge = errors.New("слишком длинный текст для перевода")

// ErrUnavailable — перевод сейчас недоступен: нет ключа или провайдер молчит.
var ErrUnavailable = errors.New("перевод недоступен")

// Hard limits до внешнего API. DeepL тарифицируется по символам, поэтому
// десятки килобайт текста нельзя пропускать только потому, что JSON влез в
// общий body limit. Раздельные константы для текста и контекста позволяют
// держать слово коротким, а контекст — предложением.
const (
	MaxTextRunes    = 2000
	MaxContextRunes = 2000
	// Byte limits duplicate rune limits for provider contracts that count bytes.
	MaxTextBytes    = 8000
	MaxContextBytes = 8000
)

// Request — что переводим.
type Request struct {
	Text    string
	Context string // окружающая фраза для короткого неоднозначного текста
	Source  string // "EN"; пустая строка — пусть определяет провайдер
	Target  string // "RU"
}

// Result — перевод и откуда он взялся.
type Result struct {
	Text string `json:"text"`
	// Cached помогает клиенту понять, стоит ли ждать быстрого ответа в
	// следующий раз, и видно в логах, насколько кэш окупается.
	Cached bool `json:"cached"`
}

// Service переводит текст, спрашивая сначала кэш, потом DeepL.
type Service struct {
	pool    *pgxpool.Pool
	client  *http.Client
	key     string
	url     string
	timeout time.Duration
}

func New(pool *pgxpool.Pool, key, endpoint string, timeout time.Duration) *Service {
	return &Service{
		pool: pool,
		// Свой клиент, а не http.DefaultClient: у общего клиента нет таймаута,
		// и зависший запрос к провайдеру держал бы соединение бесконечно.
		client:  &http.Client{Timeout: timeout},
		key:     strings.TrimSpace(key),
		url:     endpoint,
		timeout: timeout,
	}
}

// Configured — настроен ли перевод. Без ключа сервис живёт дальше: чтение и
// разбор слов работают локально, а карточка честно скажет, что перевода нет.
func (s *Service) Configured() bool {
	return s.key != ""
}

// Translate переводит текст.
func (s *Service) Translate(ctx context.Context, req Request) (Result, error) {
	text := strings.TrimSpace(req.Text)
	if text == "" {
		return Result{}, fmt.Errorf("пустой текст")
	}
	// Hard limits before external API: не даём клиенту прогнать через
	// платный сервис книгу целиком, даже если общий JSON-body limit её
	// пропустил. Проверяем и runes (Unicode code points), и bytes по
	// контракту провайдера.
	if len([]rune(text)) > MaxTextRunes {
		return Result{}, fmt.Errorf("%w: текст %d символов, предел %d", ErrTooLarge, len([]rune(text)), MaxTextRunes)
	}
	if len(text) > MaxTextBytes {
		return Result{}, fmt.Errorf("%w: текст %d байт, предел %d", ErrTooLarge, len(text), MaxTextBytes)
	}
	target := strings.ToUpper(strings.TrimSpace(req.Target))
	if target == "" {
		target = "RU"
	}
	source := strings.ToUpper(strings.TrimSpace(req.Source))
	contextText := strings.TrimSpace(req.Context)
	if len([]rune(contextText)) > MaxContextRunes {
		return Result{}, fmt.Errorf("%w: контекст %d символов, предел %d", ErrTooLarge, len([]rune(contextText)), MaxContextRunes)
	}
	if len(contextText) > MaxContextBytes {
		return Result{}, fmt.Errorf("%w: контекст %d байт, предел %d", ErrTooLarge, len(contextText), MaxContextBytes)
	}

	// Одно и то же слово в разных предложениях может переводиться по-разному:
	// `book` — «книга» или «бронировать». Контекст обязан входить в ключ кэша,
	// иначе первый встреченный смысл навсегда подменил бы остальные.
	key := cacheKey(text, contextText, source, target)

	if cached, ok := s.fromCache(ctx, key); ok {
		return Result{Text: cached, Cached: true}, nil
	}
	if !s.Configured() {
		return Result{}, ErrUnavailable
	}

	translated, err := s.fromDeepL(ctx, text, contextText, source, target)
	if err != nil {
		return Result{}, err
	}

	s.store(ctx, key, source, target, text, translated)
	return Result{Text: translated}, nil
}

// cacheKey — хеш от текста и направления перевода.
//
// Хеш, а не сам текст ключом: предложение бывает длиной в несколько сотен
// символов, а индекс по bytea фиксированной длины и меньше, и быстрее.
func cacheKey(text, contextText, source, target string) []byte {
	sum := sha256.Sum256([]byte(source + "\x00" + target + "\x00" + contextText + "\x00" + text))
	return sum[:]
}

func (s *Service) fromCache(ctx context.Context, key []byte) (string, bool) {
	if s.pool == nil {
		return "", false
	}
	var translated string
	err := s.pool.QueryRow(ctx,
		`UPDATE wolfy.translations
            SET hits = hits + 1, used_at = now()
          WHERE hash = $1
      RETURNING translated`, key).Scan(&translated)

	if err != nil {
		// Промах кэша — обычное дело, а сбой базы уже проявится дальше при
		// записи. Ронять перевод из-за кэша неправильно.
		if !errors.Is(err, pgx.ErrNoRows) {
			return "", false
		}
		return "", false
	}
	return translated, true
}

func (s *Service) store(ctx context.Context, key []byte, source, target, text, translated string) {
	if s.pool == nil {
		return
	}
	_, _ = s.pool.Exec(ctx, `
        INSERT INTO wolfy.translations
               (hash, source_lang, target_lang, source_text, translated)
        VALUES ($1, $2, $3, $4, $5)
        ON CONFLICT (hash) DO NOTHING`,
		key, source, target, text, translated)
}

// ответ DeepL.
type deeplResponse struct {
	Translations []struct {
		Text                   string `json:"text"`
		DetectedSourceLanguage string `json:"detected_source_language"`
	} `json:"translations"`
}

func (s *Service) fromDeepL(ctx context.Context, text, contextText, source, target string) (string, error) {
	form := url.Values{}
	form.Set("text", text)
	form.Set("target_lang", target)
	if source != "" {
		form.Set("source_lang", source)
	}
	// DeepL не переводит context и не тарифицирует его, а использует только
	// для выбора смысла короткого текста. Это ровно наш случай карточки слова.
	if contextText != "" {
		form.Set("context", contextText)
	}
	// Книга — это проза, и предложение в ней надо переводить целиком, не
	// разрывая по знакам препинания.
	form.Set("split_sentences", "0")

	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.url,
		strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("запрос к DeepL: %w", err)
	}
	request.Header.Set("Authorization", "DeepL-Auth-Key "+s.key)
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	response, err := s.client.Do(request)
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer func() { _ = response.Body.Close() }()

	// Ограничиваем чтение: ответ провайдера не должен уметь занять всю память
	// сервиса, что бы там ни пришло.
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return "", fmt.Errorf("%w: ответ не прочитан: %v", ErrUnavailable, err)
	}

	switch {
	case response.StatusCode == http.StatusTooManyRequests:
		return "", fmt.Errorf("%w: превышен лимит запросов к переводчику", ErrUnavailable)
	case response.StatusCode == http.StatusForbidden:
		return "", fmt.Errorf("%w: ключ DeepL отклонён", ErrUnavailable)
	case response.StatusCode == 456:
		// Отдельный код DeepL: закончилась квота символов.
		return "", fmt.Errorf("%w: исчерпан лимит символов DeepL", ErrUnavailable)
	case response.StatusCode != http.StatusOK:
		return "", fmt.Errorf("%w: DeepL ответил %d", ErrUnavailable, response.StatusCode)
	}

	var parsed deeplResponse
	if err := json.NewDecoder(bytes.NewReader(body)).Decode(&parsed); err != nil {
		return "", fmt.Errorf("%w: ответ DeepL не разобран: %v", ErrUnavailable, err)
	}
	if len(parsed.Translations) == 0 {
		return "", fmt.Errorf("%w: DeepL вернул пустой перевод", ErrUnavailable)
	}
	return parsed.Translations[0].Text, nil
}
