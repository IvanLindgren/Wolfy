// Package updates публикует установочные пакеты Wolfy и описывает последнюю
// версию. Каталог перечитывается на каждый запрос: достаточно атомарно положить
// новый MSI или APK на сервер, и клиенты увидят его без перезапуска сервиса.
package updates

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	windowsPackage = regexp.MustCompile(`^Wolfy-(\d+\.\d+\.\d+)\.msi$`)
	// Внешнее обновление Android обязано быть подписанным release APK. Старый
	// суффикс -debug оставлен только для уже опубликованных тестовых сборок.
	androidPackage = regexp.MustCompile(`^Wolfy-(\d+\.\d+\.\d+)(?:-debug)?\.apk$`)
	// DEB собирался в CI и оставался артефактом сборки: до сервера он не
	// доезжал, и клиент на Linux не мог узнать о новой версии ничем, кроме
	// захода на сайт. Имя приводится к общему виду при выкладке — packageDeb
	// называет файл по-своему (wolfy_0.1.5-1_amd64.deb).
	linuxPackage = regexp.MustCompile(`^Wolfy-(\d+\.\d+\.\d+)\.deb$`)
)

type Service struct {
	directory string
	mu        sync.Mutex
	checksums map[string]cachedChecksum
}

type cachedChecksum struct {
	size     int64
	modified time.Time
	value    string
}

type Manifest struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	SHA256  string `json:"sha256"`
	Size    int64  `json:"size"`
}

type packageFile struct {
	name    string
	version version
	info    os.FileInfo
}

type version [3]int

func New(directory string) *Service {
	return &Service{directory: filepath.Clean(directory), checksums: make(map[string]cachedChecksum)}
}

func (s *Service) Latest(w http.ResponseWriter, r *http.Request) {
	pattern, ok := patternFor(r.URL.Query().Get("platform"))
	if !ok {
		http.Error(w, "неизвестная платформа", http.StatusBadRequest)
		return
	}
	current, err := parseVersion(r.URL.Query().Get("current"))
	if err != nil {
		http.Error(w, "неверная текущая версия", http.StatusBadRequest)
		return
	}
	latest, err := s.latest(pattern)
	if err != nil {
		http.Error(w, "каталог обновлений недоступен", http.StatusServiceUnavailable)
		return
	}
	if latest == nil || !newer(latest.version, current) {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	sum, err := s.checksum(latest.name, latest.info)
	if err != nil {
		http.Error(w, "пакет обновления недоступен", http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(Manifest{
		Version: latest.version.String(),
		URL:     "/v1/update/files/" + url.PathEscape(latest.name),
		SHA256:  sum,
		Size:    latest.info.Size(),
	})
}

func (s *Service) File(w http.ResponseWriter, r *http.Request) {
	name, err := url.PathUnescape(r.PathValue("name"))
	if err != nil || filepath.Base(name) != name || !allowed(name) {
		http.NotFound(w, r)
		return
	}
	path := filepath.Join(s.directory, name)
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename=%q`, name))
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	http.ServeFile(w, r, path)
}

func patternFor(platform string) (*regexp.Regexp, bool) {
	switch platform {
	case "windows":
		return windowsPackage, true
	case "android":
		return androidPackage, true
	case "linux":
		return linuxPackage, true
	default:
		return nil, false
	}
}

func allowed(name string) bool {
	return windowsPackage.MatchString(name) ||
		androidPackage.MatchString(name) ||
		linuxPackage.MatchString(name)
}

func (s *Service) latest(pattern *regexp.Regexp) (*packageFile, error) {
	entries, err := os.ReadDir(s.directory)
	if err != nil {
		return nil, err
	}
	var result *packageFile
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		match := pattern.FindStringSubmatch(entry.Name())
		if match == nil {
			continue
		}
		v, err := parseVersion(match[1])
		if err != nil {
			continue
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		if result == nil || newer(v, result.version) {
			result = &packageFile{name: entry.Name(), version: v, info: info}
		}
	}
	return result, nil
}

func (s *Service) checksum(name string, info os.FileInfo) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if cached, ok := s.checksums[name]; ok && cached.size == info.Size() && cached.modified.Equal(info.ModTime()) {
		return cached.value, nil
	}
	file, err := os.Open(filepath.Join(s.directory, name))
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	value := hex.EncodeToString(hash.Sum(nil))
	s.checksums[name] = cachedChecksum{size: info.Size(), modified: info.ModTime(), value: value}
	return value, nil
}

func parseVersion(raw string) (version, error) {
	parts := strings.Split(strings.TrimSpace(raw), ".")
	if len(parts) != 3 {
		return version{}, fmt.Errorf("версия должна состоять из трёх частей")
	}
	var result version
	for index, part := range parts {
		value, err := strconv.Atoi(part)
		if err != nil || value < 0 {
			return version{}, fmt.Errorf("неверная версия")
		}
		result[index] = value
	}
	return result, nil
}

func newer(left, right version) bool {
	for index := range left {
		if left[index] != right[index] {
			return left[index] > right[index]
		}
	}
	return false
}

func (v version) String() string {
	return fmt.Sprintf("%d.%d.%d", v[0], v[1], v[2])
}
