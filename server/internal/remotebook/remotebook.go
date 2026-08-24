// Package remotebook безопасно загружает пользовательскую книгу по HTTPS.
//
// Браузер не может сам скачать большинство книг из-за CORS. Сервер выступает
// узким прокси только для EPUB, PDF и TXT: проверяет адрес при каждом
// перенаправлении и ещё раз непосредственно при соединении, чтобы DNS
// rebinding не превратил маршрут в доступ к локальной сети VDS.
package remotebook

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"path"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

var (
	ErrUnsafeURL   = errors.New("разрешены только публичные HTTPS-адреса")
	ErrTooLarge    = errors.New("файл книги больше 64 МБ")
	ErrUnsupported = errors.New("по ссылке нет поддерживаемой книги EPUB, PDF или TXT")
	ErrUpstream    = errors.New("источник книги недоступен")
	ErrBusy        = errors.New("сервер уже загружает другую книгу; попробуйте ещё раз")
)

const MaxBytes = 64 << 20

type Download struct {
	Bytes       []byte
	FileName    string
	ContentType string
}

type Service struct {
	http  *http.Client
	slots chan struct{}
}

// New создаёт клиент без системного HTTP-прокси: прокси мог бы разрешить
// внутренний адрес уже после наших проверок и тем самым обойти SSRF-защиту.
func New(timeout time.Duration) *Service {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = publicDialContext(net.DefaultResolver)
	transport.MaxResponseHeaderBytes = 64 << 10
	transport.ResponseHeaderTimeout = timeout

	return &Service{http: &http.Client{
		Transport: transport,
		Timeout:   timeout,
		CheckRedirect: func(request *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return fmt.Errorf("%w: слишком много перенаправлений", ErrUnsafeURL)
			}
			return validateURL(request.URL)
		},
	}, slots: make(chan struct{}, 1)}
}

// Fetch получает файл, ограничивает его ещё во время чтения и определяет
// формат по содержимому. Расширению и Content-Type источника доверять нельзя:
// CDN нередко отдаёт application/octet-stream, а страница ошибки может иметь
// имя book.pdf.
func (s *Service) Fetch(ctx context.Context, rawURL string) (Download, error) {
	address, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || validateURL(address) != nil {
		return Download{}, ErrUnsafeURL
	}
	// systemd ограничивает Wolfy 192 МБ памяти. Один ответ может занимать до
	// 64 МБ, поэтому rate limit сам по себе недостаточен: burst из нескольких
	// параллельных загрузок выбил бы процесс по OOM. Тестовые Service без slots
	// остаются без ограничения, production New всегда создаёт один слот.
	if s.slots != nil {
		select {
		case s.slots <- struct{}{}:
			defer func() { <-s.slots }()
		default:
			return Download{}, ErrBusy
		}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, address.String(), nil)
	if err != nil {
		return Download{}, ErrUnsafeURL
	}
	req.Header.Set("Accept", "application/epub+zip, application/pdf, text/plain;q=0.9, application/octet-stream;q=0.5")
	req.Header.Set("User-Agent", "Wolfy/1.0 (book importer)")

	response, err := s.http.Do(req)
	if err != nil {
		return Download{}, fmt.Errorf("%w: %v", ErrUpstream, err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return Download{}, fmt.Errorf("%w: источник ответил %d", ErrUpstream, response.StatusCode)
	}
	if response.ContentLength > MaxBytes {
		return Download{}, ErrTooLarge
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, MaxBytes+1))
	if err != nil {
		return Download{}, fmt.Errorf("%w: чтение ответа: %v", ErrUpstream, err)
	}
	if len(data) > MaxBytes {
		return Download{}, ErrTooLarge
	}

	// После redirect имя и расширение принадлежат конечному ответу, а не
	// короткой ссылке вида `/download?id=…`.
	finalAddress := address
	if response.Request != nil && response.Request.URL != nil {
		finalAddress = response.Request.URL
	}
	format, contentType := detectFormat(data, response.Header.Get("Content-Type"), finalAddress.Path)
	if format == "" {
		return Download{}, ErrUnsupported
	}
	name := responseName(response, finalAddress)
	name = withExtension(safeName(name), format)
	return Download{Bytes: data, FileName: name, ContentType: contentType}, nil
}

func validateURL(address *url.URL) error {
	if address == nil || !strings.EqualFold(address.Scheme, "https") || address.Hostname() == "" || address.User != nil {
		return ErrUnsafeURL
	}
	if port := address.Port(); port != "" && port != "443" {
		return ErrUnsafeURL
	}
	if ip, err := netip.ParseAddr(address.Hostname()); err == nil && !publicIP(ip) {
		return ErrUnsafeURL
	}
	return nil
}

type ipResolver interface {
	LookupNetIP(context.Context, string, string) ([]netip.Addr, error)
}

func publicDialContext(resolver ipResolver) func(context.Context, string, string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil || port != "443" {
			return nil, ErrUnsafeURL
		}
		ips, err := resolver.LookupNetIP(ctx, "ip", host)
		if err != nil {
			return nil, fmt.Errorf("разрешение адреса: %w", err)
		}
		var last error
		for _, ip := range ips {
			if !publicIP(ip) {
				continue
			}
			connection, dialErr := dialer.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
			if dialErr == nil {
				return connection, nil
			}
			last = dialErr
		}
		if last != nil {
			return nil, last
		}
		return nil, ErrUnsafeURL
	}
}

// Дополнительно к стандартным private/link-local закрываем диапазоны,
// которые IsPrivate не считает локальными: carrier-grade NAT, документацию и
// служебные сети. Они не должны быть целью серверного загрузчика.
var blockedNetworks = mustPrefixes(
	"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24",
	"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4",
	"240.0.0.0/4", "2001:db8::/32", "2001:10::/28", "fc00::/7", "fe80::/10",
	"ff00::/8",
)

func mustPrefixes(values ...string) []netip.Prefix {
	result := make([]netip.Prefix, 0, len(values))
	for _, value := range values {
		result = append(result, netip.MustParsePrefix(value))
	}
	return result
}

func publicIP(ip netip.Addr) bool {
	if !ip.IsValid() {
		return false
	}
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	for _, prefix := range blockedNetworks {
		if prefix.Contains(ip) {
			return false
		}
	}
	return true
}

func detectFormat(data []byte, contentType, sourcePath string) (format, mimeType string) {
	if len(data) >= 5 && bytes.Equal(data[:5], []byte("%PDF-")) {
		return "pdf", "application/pdf"
	}
	if validEPUB(data) {
		return "epub", "application/epub+zip"
	}
	mediaType, _, _ := mime.ParseMediaType(contentType)
	if strings.EqualFold(mediaType, "text/plain") || strings.EqualFold(path.Ext(sourcePath), ".txt") {
		if charset := textCharset(data); charset != "" {
			return "txt", "text/plain; charset=" + charset
		}
	}
	return "", ""
}

func validEPUB(data []byte) bool {
	// EPUB OCF требует `mimetype` первым ZIP-entry, без сжатия, extra field и
	// пробелов. Этого достаточно для точной O(1) сигнатуры и, в отличие от
	// zip.NewReader, не создаёт по объекту на каждую запись недоверенного
	// central directory (crafted ZIP мог превысить MemoryMax процесса).
	const (
		localHeader = 30
		mimeName    = "mimetype"
		mimeValue   = "application/epub+zip"
	)
	if len(data) < localHeader+len(mimeName)+len(mimeValue) ||
		!bytes.Equal(data[:4], []byte{'P', 'K', 3, 4}) {
		return false
	}
	flags := binary.LittleEndian.Uint16(data[6:8])
	method := binary.LittleEndian.Uint16(data[8:10])
	nameLength := int(binary.LittleEndian.Uint16(data[26:28]))
	extraLength := int(binary.LittleEndian.Uint16(data[28:30]))
	if flags&1 != 0 || method != 0 || nameLength != len(mimeName) || extraLength != 0 {
		return false
	}
	nameStart := localHeader
	valueStart := nameStart + nameLength
	valueEnd := valueStart + len(mimeValue)
	return valueEnd <= len(data) &&
		string(data[nameStart:valueStart]) == mimeName &&
		string(data[valueStart:valueEnd]) == mimeValue
}

func textCharset(data []byte) string {
	data = bytes.TrimPrefix(data, []byte{0xef, 0xbb, 0xbf})
	if bytes.ContainsRune(data, '\x00') {
		return ""
	}
	if utf8.Valid(data) {
		return "utf-8"
	}

	// Локальный импорт умеет старые TXT в Windows-1251. Для загрузки по
	// ссылке принимаем ту же кодировку, но отсекаем бинарные данные по
	// управляющим байтам: в обычном тексте допустимы только TAB/LF/FF/CR.
	for _, value := range data {
		if value < 0x20 && value != '\t' && value != '\n' && value != '\f' && value != '\r' {
			return ""
		}
	}
	return "windows-1251"
}

func responseName(response *http.Response, address *url.URL) string {
	if disposition := response.Header.Get("Content-Disposition"); disposition != "" {
		if _, params, err := mime.ParseMediaType(disposition); err == nil && params["filename"] != "" {
			return params["filename"]
		}
	}
	name, _ := url.PathUnescape(path.Base(strings.TrimSuffix(address.Path, "/")))
	return name
}

func safeName(value string) string {
	value = path.Base(strings.ReplaceAll(strings.TrimSpace(value), "\\", "/"))
	runes := []rune(strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || strings.ContainsRune(" ._()-", r) {
			return r
		}
		return '_'
	}, value))
	if len(runes) > 120 {
		runes = runes[:120]
	}
	clean := strings.Trim(strings.TrimSpace(string(runes)), ".")
	if clean == "" {
		return "book"
	}
	return clean
}

func withExtension(name, format string) string {
	extension := "." + format
	current := path.Ext(name)
	if strings.EqualFold(current, extension) {
		return name
	}
	if current != "" {
		name = strings.TrimSuffix(name, current)
	}
	return strings.TrimSpace(name) + extension
}
