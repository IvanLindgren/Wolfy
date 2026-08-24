package remotebook

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net/http"
	"net/netip"
	"strings"
	"testing"
)

type roundTrip func(*http.Request) (*http.Response, error)

func (fn roundTrip) RoundTrip(request *http.Request) (*http.Response, error) {
	return fn(request)
}

func response(status int, contentType, disposition string, body []byte) *http.Response {
	header := make(http.Header)
	header.Set("Content-Type", contentType)
	if disposition != "" {
		header.Set("Content-Disposition", disposition)
	}
	return &http.Response{
		StatusCode:    status,
		Header:        header,
		Body:          io.NopCloser(bytes.NewReader(body)),
		ContentLength: int64(len(body)),
	}
}

func serviceReturning(result *http.Response) *Service {
	return &Service{http: &http.Client{Transport: roundTrip(func(*http.Request) (*http.Response, error) {
		return result, nil
	})}}
}

func TestНебезопасныеАдресаНеЗагружаются(t *testing.T) {
	addresses := []string{
		"http://example.com/book.pdf",
		"https://user:secret@example.com/book.pdf",
		"https://127.0.0.1/book.pdf",
		"https://[::1]/book.pdf",
		"https://10.2.3.4/book.epub",
		"https://169.254.169.254/latest/meta-data/",
		"https://example.com:8443/book.txt",
	}
	service := serviceReturning(response(http.StatusOK, "application/pdf", "", []byte("%PDF-1.7")))
	for _, address := range addresses {
		t.Run(address, func(t *testing.T) {
			if _, err := service.Fetch(context.Background(), address); !errors.Is(err, ErrUnsafeURL) {
				t.Fatalf("адрес не отклонён: %v", err)
			}
		})
	}
}

func TestСлужебныеИПНеСчитаютсяПубличными(t *testing.T) {
	private := []string{
		"100.64.0.1", "192.0.2.1", "198.51.100.2", "203.0.113.3",
		"2001:db8::1", "::ffff:127.0.0.1",
	}
	for _, raw := range private {
		if publicIP(netip.MustParseAddr(raw)) {
			t.Fatalf("служебный адрес %s разрешён", raw)
		}
	}
	if !publicIP(netip.MustParseAddr("1.1.1.1")) {
		t.Fatal("публичный адрес отклонён")
	}
}

func TestPDFОпределяетсяПоСигнатуреИПолучаетВерноеИмя(t *testing.T) {
	service := serviceReturning(response(
		http.StatusOK,
		"application/octet-stream",
		`attachment; filename*=UTF-8''Alice%20in%20Wonderland.epub`,
		[]byte("%PDF-1.7\ncontent"),
	))
	download, err := service.Fetch(context.Background(), "https://books.example/download?id=1")
	if err != nil {
		t.Fatal(err)
	}
	if download.FileName != "Alice in Wonderland.pdf" || download.ContentType != "application/pdf" {
		t.Fatalf("неверный результат: %+v", download)
	}
}

func TestEPUBПроверяетсяКакZIPКонтейнер(t *testing.T) {
	var body bytes.Buffer
	archive := zip.NewWriter(&body)
	file, err := archive.CreateHeader(&zip.FileHeader{Name: "mimetype", Method: zip.Store})
	if err != nil {
		t.Fatal(err)
	}
	_, _ = file.Write([]byte("application/epub+zip"))
	container, err := archive.Create("META-INF/container.xml")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = container.Write([]byte("<container/>"))
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}

	service := serviceReturning(response(http.StatusOK, "application/zip", "", body.Bytes()))
	download, err := service.Fetch(context.Background(), "https://books.example/pride-and-prejudice")
	if err != nil {
		t.Fatal(err)
	}
	if download.FileName != "pride-and-prejudice.epub" {
		t.Fatalf("имя %q", download.FileName)
	}
}

func TestHTMLПодВидомКнигиОтклоняется(t *testing.T) {
	service := serviceReturning(response(
		http.StatusOK, "text/html", "", []byte("<!doctype html><title>Access denied</title>"),
	))
	_, err := service.Fetch(context.Background(), "https://books.example/book.pdf")
	if !errors.Is(err, ErrUnsupported) {
		t.Fatalf("HTML не отклонён: %v", err)
	}
}

func TestTXTПринимаетUTF8ИWindows1251БезБинарныхБайтов(t *testing.T) {
	good := serviceReturning(response(http.StatusOK, "text/plain; charset=utf-8", "", []byte("Hello, reader!")))
	if got, err := good.Fetch(context.Background(), "https://books.example/story"); err != nil || got.FileName != "story.txt" {
		t.Fatalf("TXT не принят: %+v, %v", got, err)
	}

	bad := serviceReturning(response(http.StatusOK, "text/plain", "", []byte{'a', 0, 'b'}))
	if _, err := bad.Fetch(context.Background(), "https://books.example/story.txt"); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("бинарный файл принят как TXT: %v", err)
	}

	cp1251 := serviceReturning(response(
		http.StatusOK,
		"application/octet-stream",
		"",
		[]byte{0xC3, 0xEB, 0xE0, 0xE2, 0xE0, '\n'}, // «Глава» в Windows-1251.
	))
	got, err := cp1251.Fetch(context.Background(), "https://books.example/chapter.txt")
	if err != nil {
		t.Fatalf("TXT в Windows-1251 не принят: %v", err)
	}
	if got.ContentType != "text/plain; charset=windows-1251" {
		t.Fatalf("неверная кодировка ответа: %q", got.ContentType)
	}

	control := serviceReturning(response(http.StatusOK, "text/plain", "", []byte{'a', 1, 0xff}))
	if _, err := control.Fetch(context.Background(), "https://books.example/story.txt"); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("бинарный управляющий байт принят как TXT: %v", err)
	}
}

func TestПослеRedirectБерутсяКонечныеИмяИРасширение(t *testing.T) {
	result := response(http.StatusOK, "application/octet-stream", "", []byte("Redirected plain text"))
	final, err := http.NewRequest(http.MethodGet, "https://cdn.example/final-story.txt", nil)
	if err != nil {
		t.Fatal(err)
	}
	result.Request = final
	service := serviceReturning(result)

	download, err := service.Fetch(context.Background(), "https://books.example/download?id=42")
	if err != nil {
		t.Fatal(err)
	}
	if download.FileName != "final-story.txt" || download.ContentType != "text/plain; charset=utf-8" {
		t.Fatalf("redirect разобран по исходному URL: %+v", download)
	}
}

func TestEPUBСигнатураНеРазбираетНедоверенныйCentralDirectory(t *testing.T) {
	// ZIP с обычным первым entry не является EPUB. Проверка обязана завершиться
	// по локальному заголовку и не обходить произвольное число central entries.
	data := make([]byte, 58)
	copy(data, []byte{'P', 'K', 3, 4})
	binary.LittleEndian.PutUint16(data[26:28], uint16(len("not-epub")))
	copy(data[30:], "not-epub")
	if validEPUB(data) {
		t.Fatal("обычный ZIP принят как EPUB")
	}
}

func TestРазмерОграничиваетсяДоЧтения(t *testing.T) {
	result := response(http.StatusOK, "application/pdf", "", []byte("%PDF-1.7"))
	result.ContentLength = MaxBytes + 1
	service := serviceReturning(result)
	if _, err := service.Fetch(context.Background(), "https://books.example/book.pdf"); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("слишком большой ответ не отклонён: %v", err)
	}
}

func TestПеренаправлениеНаЛокальныйАдресОтклоняется(t *testing.T) {
	service := New(0)
	request, _ := http.NewRequest(http.MethodGet, "https://127.0.0.1/secret", nil)
	err := service.http.CheckRedirect(request, []*http.Request{{}})
	if !errors.Is(err, ErrUnsafeURL) {
		t.Fatalf("redirect разрешён: %v", err)
	}
}

func TestОшибкаИсточникаНеВыдаётсяЗаФормат(t *testing.T) {
	service := serviceReturning(response(http.StatusForbidden, "text/plain", "", []byte("forbidden")))
	_, err := service.Fetch(context.Background(), "https://books.example/book.txt")
	if !errors.Is(err, ErrUpstream) || !strings.Contains(err.Error(), "403") {
		t.Fatalf("неверная ошибка: %v", err)
	}
}

func TestПараллельнаяЗагрузкаНеПревышаетЛимитПамяти(t *testing.T) {
	service := serviceReturning(response(http.StatusOK, "application/pdf", "", []byte("%PDF-1.7")))
	service.slots = make(chan struct{}, 1)
	service.slots <- struct{}{}

	_, err := service.Fetch(context.Background(), "https://books.example/book.pdf")
	if !errors.Is(err, ErrBusy) {
		t.Fatalf("вторая одновременная загрузка не остановлена: %v", err)
	}
}
