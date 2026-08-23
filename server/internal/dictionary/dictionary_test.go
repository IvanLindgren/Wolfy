package dictionary

import (
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
)

func TestOpenAndDefine(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "wolfy_dictionary.tsv")
	body := "# wolfy english dictionary v2\n" +
		"library\tˈlaɪˌbɹɛɹi\tt|библиотека\tn|a room where books are kept\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	archive, err := os.Create(path + ".gz")
	if err != nil {
		t.Fatal(err)
	}
	zipper := gzip.NewWriter(archive)
	if _, err := zipper.Write([]byte(body)); err != nil {
		t.Fatal(err)
	}
	if err := zipper.Close(); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}

	service, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	entry, ok := service.Define("  Library ")
	if !ok {
		t.Fatal("статья не нашлась")
	}
	if entry.Pronunciation != "ˈlaɪˌbɹɛɹi" || entry.Translations[0] != "библиотека" || entry.Senses[0].POS != "NOUN" {
		t.Fatalf("неверная статья: %+v", entry)
	}
}
