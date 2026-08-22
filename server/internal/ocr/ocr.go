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
	"io"
	"net/http"
	"strings"
	"time"
)

// ErrUnavailable — распознавание сейчас недоступно: нет ключа или провайдер
// молчит. Приложение прячет съёмку, а не показывает нерабочую кнопку.
var ErrUnavailable = errors.New("распознавание недоступно")

// ErrTooLarge — снимок больше, чем имеет смысл отправлять.
var ErrTooLarge = errors.New("слишком большой снимок")

// MaxImageBytes — предел на снимок.
//
// Восемь мегабайт — это фотография страницы с запасом на любой телефон.
// Больше означает, что клиент забыл сжать снимок: страница книги не требует
// разрешения, на котором видно волокна бумаги.
const MaxImageBytes = 8 << 20

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
// с типа.
func (s *Service) Recognize(ctx context.Context, image []byte, mime string) (Result, error) {
	if !s.Configured() {
		return Result{}, ErrUnavailable
	}
	if len(image) == 0 {
		return Result{}, fmt.Errorf("пустой снимок")
	}
	if len(image) > MaxImageBytes {
		return Result{}, fmt.Errorf("%w: %d байт, предел %d", ErrTooLarge, len(image), MaxImageBytes)
	}

	dataURL := "data:" + imageMime(mime) + ";base64," + base64.StdEncoding.EncodeToString(image)

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
// data-URL без типа модель не примет.
func imageMime(mime string) string {
	mime = strings.TrimSpace(strings.ToLower(mime))
	switch mime {
	case "image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic":
		return mime
	default:
		return "image/jpeg"
	}
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
