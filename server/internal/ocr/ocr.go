// Package ocr — распознавание страницы книги по фотографии.
//
// Задача узкая и не совпадает с обычным OCR. Читателю нужен не «текст на
// картинке», а страница бумажной книги, пригодная для чтения в приложении:
// без номеров страниц, без колонтитулов, со склеенными переносами и с
// сохранёнными абзацами. Обычный распознаватель отдал бы всё подряд построчно,
// и получившееся пришлось бы чинить руками.
//
// Поэтому вместо распознавателя здесь модель, которая видит картинку и умеет
// читать инструкцию. Она дороже посимвольного OCR, но разница в цене на одну
// фотографию несопоставима с разницей в результате.
package ocr

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/wolfy/server/internal/gate"
)

// ErrUnavailable — распознавание сейчас недоступно: нет ключа или провайдер
// молчит. Приложение прячет съёмку, а не показывает нерабочую кнопку.
var ErrUnavailable = errors.New("распознавание недоступно")

// ErrTooLarge — снимок больше, чем имеет смысл отправлять.
var ErrTooLarge = errors.New("слишком большой снимок")

// ErrTooManyRequests — распознавание перегружено.
var ErrTooManyRequests = errors.New("слишком много запросов на распознавание")

// ErrInvalidType — неподдерживаемый тип изображения.
var ErrInvalidType = errors.New("неподдерживаемый тип изображения")

// MaxImageBytes — предел на снимок.
//
// Восемь мегабайт — это фотография страницы с запасом на любой телефон.
// Больше означает, что клиент забыл сжать снимок: страница книги не требует
// разрешения, на котором видно волокна бумаги.
const MaxImageBytes = 8 << 20

// MaxDecodedBytes — предел на декодированное изображение, чтобы учесть
// разницу между сжатым JPEG и сырыми пикселями. 12 мегапикселей в RGBA
// — это ~48 MiB.
const MaxDecodedPixels = 16 << 20 // 16 MP

// MaxConcurrentOCR — глобальный лимит одновременных vision-вызовов.
// Держится в gate.OCR, но константа видна для тестов и логов.
const MaxConcurrentOCR = 4

// Указание модели.
//
// Написано по-английски намеренно: модель работает с английским текстом, и
// инструкция на его же языке даёт заметно более устойчивый результат. Каждое
// требование здесь появилось из того, как выглядит настоящая страница.
const instruction = `You are reading a photograph of a page from a printed English book.
Return the text of the page and nothing else.

Rules:
- Return plain text only. No markdown, no comments, no explanations.
- Join words broken by a hyphen at a line end into a single word.
- Keep paragraph breaks as blank lines. Do not keep the line breaks of the printed page.
- Drop page numbers, running heads and footers.
- Keep the original spelling and punctuation, including quotation marks.
- If the photograph shows no readable text, return an empty response.`

// Result — распознанный текст.
type Result struct {
	Text string `json:"text"`
	// Model видно в логах и в ответе: если распознавание вдруг стало хуже,
	// первый вопрос — не сменилась ли модель.
	Model string `json:"model"`
}

// Service распознаёт страницы.
type Service struct {
	client *http.Client
	key    string
	url    string
	model  string
}

func New(key, endpoint, model string, timeout time.Duration) *Service {
	return &Service{
		// Свой клиент, а не http.DefaultClient: у общего нет таймаута, а
		// распознавание картинки — самый долгий запрос сервиса.
		client: &http.Client{Timeout: timeout},
		key:    strings.TrimSpace(key),
		url:    endpoint,
		model:  model,
	}
}

// Configured — настроено ли распознавание.
func (s *Service) Configured() bool {
	return s.key != ""
}

// Recognize отдаёт текст со снимка.
//
// На вход идут байты изображения и его тип — тот, что прислал клиент. Тип
// нужен модели: она принимает картинку как data-URL, а он начинается именно
// с типа. Hard limits, timeout и concurrency gate защищают от того, чтобы один
// аккаунт держал десятки дорогих vision-запросов.
func (s *Service) Recognize(ctx context.Context, img []byte, mime string) (Result, error) {
	if !s.Configured() {
		return Result{}, ErrUnavailable
	}
	if len(img) == 0 {
		return Result{}, fmt.Errorf("пустой снимок")
	}
	if len(img) > MaxImageBytes {
		return Result{}, fmt.Errorf("%w: %d байт, предел %d", ErrTooLarge, len(img), MaxImageBytes)
	}
	if err := validateImage(img, mime); err != nil {
		return Result{}, err
	}
	if err := validateDecodedSize(img); err != nil {
		return Result{}, err
	}
	// Глобальный конкурентный лимит: vision-модель дорогая, и десятки
	// одновременных запросов от одного или разных пользователей не должны
	// выбивать процесс по памяти/CPU. Семафор уважает отмену контекста.
	if gate.OCR != nil {
		if err := gate.OCR.Acquire(ctx); err != nil {
			return Result{}, fmt.Errorf("%w: %v", ErrTooManyRequests, err)
		}
		defer gate.OCR.Release()
	}

	dataURL := "data:" + imageMime(mime) + ";base64," + base64.StdEncoding.EncodeToString(img)

	payload := chatRequest{
		Model: s.model,
		// Ноль: распознавание — не сочинение. Модель, которой позволено
		// фантазировать, дописывает за автора там, где страница смазана.
		Temperature: 0,
		Messages: []message{{
			Role: "user",
			Content: []part{
				{Type: "text", Text: instruction},
				{Type: "image_url", ImageURL: &imageURL{URL: dataURL}},
			},
		}},
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return Result{}, fmt.Errorf("сборка запроса: %w", err)
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.url, bytes.NewReader(body))
	if err != nil {
		return Result{}, fmt.Errorf("запрос распознавания: %w", err)
	}
	request.Header.Set("Authorization", "Bearer "+s.key)
	request.Header.Set("Content-Type", "application/json")

	response, err := s.client.Do(request)
	if err != nil {
		return Result{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer func() { _ = response.Body.Close() }()

	// Предел на ответ: распознанная страница это килобайты, а не мегабайты.
	raw, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return Result{}, fmt.Errorf("%w: ответ не прочитан: %v", ErrUnavailable, err)
	}
	if response.StatusCode != http.StatusOK {
		return Result{}, fmt.Errorf("%w: провайдер ответил %d: %s",
			ErrUnavailable, response.StatusCode, strings.TrimSpace(string(raw)))
	}

	var decoded chatResponse
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return Result{}, fmt.Errorf("%w: ответ не разобран: %v", ErrUnavailable, err)
	}
	if len(decoded.Choices) == 0 {
		return Result{}, fmt.Errorf("%w: провайдер вернул пустой ответ", ErrUnavailable)
	}

	return Result{
		Text:  Clean(decoded.Choices[0].Message.Content),
		Model: s.model,
	}, nil
}

// Clean приводит ответ модели к тому, что можно показать читателю.
//
// Модель просили вернуть только текст, и обычно она так и делает. Но «обычно»
// здесь недостаточно: обёртка в тройные кавычки и вводная фраза встречаются
// достаточно часто, чтобы читатель увидел их на странице книги.
func Clean(text string) string {
	cleaned := strings.TrimSpace(text)

	// Ограждение из тройных кавычек: ```\n...\n```
	if strings.HasPrefix(cleaned, "```") {
		if end := strings.LastIndex(cleaned, "```"); end > 3 {
			cleaned = cleaned[3:end]
			// Первая строка ограждения бывает с языком: ```text
			if newline := strings.IndexByte(cleaned, '\n'); newline >= 0 &&
				!strings.Contains(cleaned[:newline], " ") {
				cleaned = cleaned[newline+1:]
			}
		}
	}

	// Переводы строк приводим к одному виду: клиент считает смещения в
	// символах, и «\r\n» сдвинул бы подсветку на каждой строке.
	cleaned = strings.ReplaceAll(cleaned, "\r\n", "\n")
	cleaned = strings.ReplaceAll(cleaned, "\r", "\n")

	// Больше двух переводов строки подряд не значат ничего: абзац отделяется
	// одним пустым рядом.
	for strings.Contains(cleaned, "\n\n\n") {
		cleaned = strings.ReplaceAll(cleaned, "\n\n\n", "\n\n")
	}

	return strings.TrimSpace(cleaned)
}

// imageMime подставляет тип по умолчанию: клиент мог его не прислать, а
// data-URL без типа модель не примет. Нормализует image/jpg -> image/jpeg.
func imageMime(mime string) string {
	mime = strings.TrimSpace(strings.ToLower(mime))
	switch mime {
	case "image/jpeg", "image/jpg":
		return "image/jpeg"
	case "image/png", "image/webp", "image/heic":
		return mime
	default:
		return "image/jpeg"
	}
}

func validateImage(data []byte, mime string) error {
	raw := strings.TrimSpace(strings.ToLower(mime))
	// Если клиент прислал явный тип, он должен быть из белого списка.
	if raw != "" && raw != "image/jpeg" && raw != "image/jpg" && raw != "image/png" && raw != "image/webp" && raw != "image/heic" {
		return fmt.Errorf("%w: %s", ErrInvalidType, mime)
	}
	// Проверка магических байтов — не доверяем только заголовку.
	if len(data) >= 2 && data[0] == 0xFF && data[1] == 0xD8 {
		return nil // JPEG
	}
	if len(data) >= 8 && bytes.Equal(data[:8], []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}) {
		return nil // PNG
	}
	if len(data) >= 12 && bytes.Equal(data[:4], []byte{'R', 'I', 'F', 'F'}) && bytes.Equal(data[8:12], []byte{'W', 'E', 'B', 'P'}) {
		return nil // WEBP
	}
	if len(data) >= 12 && bytes.Equal(data[4:8], []byte{'f', 't', 'y', 'p'}) {
		// HEIC/HEIF — проверяем brand.
		brand := string(data[8:12])
		if brand == "heic" || brand == "heix" || brand == "hevc" || brand == "hevx" || brand == "mif1" || brand == "msf1" {
			return nil
		}
	}
	// Для неизвестных/пустых mime разрешаем JPEG по умолчанию только если
	// магические байты совпали выше; иначе требуем явный поддерживаемый тип.
	if raw == "" {
		return fmt.Errorf("%w: неизвестный формат", ErrInvalidType)
	}
	// Если mime валидный, но магические байты не совпали, всё равно ошибка:
	// клиент мог отправить текст под видом image/jpeg.
	return fmt.Errorf("%w: сигнатура не совпала с %s", ErrInvalidType, mime)
}

func validateDecodedSize(data []byte) error {
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		// HEIC и некоторые WEBP не декодируются stdlib — проверяем только
		// тех, кого можем. Для остальных остаётся энкодед лимит.
		return nil
	}
	if cfg.Width <= 0 || cfg.Height <= 0 {
		return fmt.Errorf("%w: неверные размеры", ErrInvalidType)
	}
	pixels := int64(cfg.Width) * int64(cfg.Height)
	if pixels > int64(MaxDecodedPixels) {
		return fmt.Errorf("%w: %dx%d, предел %d пикселей", ErrTooLarge, cfg.Width, cfg.Height, MaxDecodedPixels)
	}
	// Оценка памяти RGBA = pixels *4
	if pixels*4 > int64(MaxImageBytes*6) {
		return fmt.Errorf("%w: декодированный размер слишком велик", ErrTooLarge)
	}
	return nil
}

// Формат запроса — совместимый с OpenAI: его понимают все провайдеры, через
// которых сюда может прийти модель, и менять код при смене провайдера не
// приходится.
type chatRequest struct {
	Model       string    `json:"model"`
	Temperature float64   `json:"temperature"`
	Messages    []message `json:"messages"`
}

type message struct {
	Role    string `json:"role"`
	Content []part `json:"content"`
}

type part struct {
	Type     string    `json:"type"`
	Text     string    `json:"text,omitempty"`
	ImageURL *imageURL `json:"image_url,omitempty"`
}

type imageURL struct {
	URL string `json:"url"`
}

type chatResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
}
