package updates

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLatestReturnsOnlyNewerMatchingPlatform(t *testing.T) {
	directory := t.TempDir()
	for name, body := range map[string]string{
		"Wolfy-1.3.0.msi":       "windows-new",
		"Wolfy-1.2.9.msi":       "windows-old",
		"Wolfy-8.9.0-debug.apk": "android-debug",
		"Wolfy-9.0.0.apk":       "android-release",
		"Wolfy-1.3.0.deb":       "linux-new",
		"Wolfy-1.2.9.deb":       "linux-old",
		"Wolfy-1.3.0-arm64.msi": "windows-arm",
		"not-a-release.exe":     "ignored",
	} {
		if err := os.WriteFile(filepath.Join(directory, name), []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	service := New(directory)

	request := httptest.NewRequest(http.MethodGet, "/v1/update/latest?platform=windows&current=1.2.9", nil)
	response := httptest.NewRecorder()
	service.Latest(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"version":"1.3.0"`) {
		t.Fatalf("unexpected response: %d %s", response.Code, response.Body.String())
	}

	request = httptest.NewRequest(http.MethodGet, "/v1/update/latest?platform=windows&current=1.3.0", nil)
	response = httptest.NewRecorder()
	service.Latest(response, request)
	if response.Code != http.StatusNoContent {
		t.Fatalf("want 204, got %d", response.Code)
	}

	request = httptest.NewRequest(http.MethodGet, "/v1/update/latest?platform=android&current=8.9.0", nil)
	response = httptest.NewRecorder()
	service.Latest(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"version":"9.0.0"`) {
		t.Fatalf("unexpected Android response: %d %s", response.Code, response.Body.String())
	}

	// DEB собирался в CI и оставался артефактом сборки: до сервера он не
	// доезжал, платформы "linux" не существовало, и клиент на Linux не мог
	// узнать о новой версии ничем, кроме захода на сайт.
	request = httptest.NewRequest(http.MethodGet, "/v1/update/latest?platform=linux&current=1.2.9", nil)
	response = httptest.NewRecorder()
	service.Latest(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"version":"1.3.0"`) {
		t.Fatalf("unexpected Linux response: %d %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), ".deb") {
		t.Fatalf("Linux отдали не DEB: %s", response.Body.String())
	}

	// Windows на ARM — своя платформа. Общий с x64 ключ означал бы обновление,
	// которое на чужой архитектуре не запустится.
	request = httptest.NewRequest(http.MethodGet, "/v1/update/latest?platform=windows-arm64&current=1.2.9", nil)
	response = httptest.NewRecorder()
	service.Latest(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "-arm64.msi") {
		t.Fatalf("unexpected ARM response: %d %s", response.Code, response.Body.String())
	}
}

// Пакет для ARM не должен попадать в выдачу для x64 и наоборот: имена
// отличаются одним суффиксом, и незаякоренный шаблон сделал бы их одним
// пакетом. Проверяется отдельно от общего сценария — эту ошибку не видно ни в
// каком ответе сервера, она видна только на чужой машине.
func TestWindowsPackagesDoNotCrossArchitectures(t *testing.T) {
	directory := t.TempDir()
	for name, body := range map[string]string{
		"Wolfy-1.0.0.msi":       "x64",
		"Wolfy-2.0.0-arm64.msi": "arm",
	} {
		if err := os.WriteFile(filepath.Join(directory, name), []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	service := New(directory)

	request := httptest.NewRequest(http.MethodGet, "/v1/update/latest?platform=windows&current=0.0.1", nil)
	response := httptest.NewRecorder()
	service.Latest(response, request)
	if !strings.Contains(response.Body.String(), `"version":"1.0.0"`) {
		t.Fatalf("x64 получил чужой пакет: %s", response.Body.String())
	}

	request = httptest.NewRequest(http.MethodGet, "/v1/update/latest?platform=windows-arm64&current=0.0.1", nil)
	response = httptest.NewRecorder()
	service.Latest(response, request)
	if !strings.Contains(response.Body.String(), `"version":"2.0.0"`) {
		t.Fatalf("ARM получил чужой пакет: %s", response.Body.String())
	}
}

func TestFileRejectsTraversalAndUnknownNames(t *testing.T) {
	service := New(t.TempDir())
	for _, name := range []string{"..%2Fsecret", "notes.txt", "Wolfy-bad.msi", "Wolfy-1.0.deb", "payload.deb", "Wolfy-1.0.0-arm.msi"} {
		request := httptest.NewRequest(http.MethodGet, "/v1/update/files/"+name, nil)
		request.SetPathValue("name", name)
		response := httptest.NewRecorder()
		service.File(response, request)
		if response.Code != http.StatusNotFound {
			t.Fatalf("%q: want 404, got %d", name, response.Code)
		}
	}
}
