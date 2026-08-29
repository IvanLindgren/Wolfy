package store

import (
	"crypto/sha256"
	"encoding/hex"
	"sort"
	"testing"
)

// Отпечатки применённых миграций.
//
// Журнал в базе помнит имя файла, а не его содержимое: миграция, чьё имя уже
// записано, второй раз не выполняется никогда. Поэтому правка применённого
// файла не ошибка компиляции и не падение тестов - она просто не доезжает до
// баз, где миграция уже прошла. Свежая база берёт новую редакцию, тесты на ней
// зелёные, а production молча живёт со старой схемой, пока какой-нибудь запрос
// не упрётся в недостающую колонку.
//
// Так и случилось с 0003 и 0004: их поправили на месте, и «column "generation"
// does not exist» вылезло много позже, при перепривязке книги. Чинит это
// 0012, а таблица ниже держит дверь закрытой.
//
// Изменили существующий файл - тест падает. Правильный ответ почти всегда:
// вернуть файл как был и написать новую миграцию. Обновлять отпечаток здесь
// можно, только если файл ещё нигде не применялся или правка не трогает схему
// (например, поправлен комментарий), и это решение осознанное.
var migrationChecksums = map[string]string{
	"0001_init.sql":                        "896b5fc0ad79a93fba156aa8504823a2c404cb9c6034975dfb008e6a4e4995b8",
	"0002_discovery.sql":                   "b4af7789ef0d862559433c7b47c3daf083c31cdb43b054747c1f6e49e5a9f7c7",
	"0003_annotations.sql":                 "6ea42529de5f8f0d9d5fea0303ecebefb5c04458e18446bae74d0f6514928bf1",
	"0004_annotations_devices.sql":         "ee9cef647e069eef728557e283bc01944223144571c897dd6163af10884698d3",
	"0005_practice.sql":                    "2443a29452169e357d7771b09633a0694f3db4645498ddbb1e8f3fbcfc2bbc79",
	"0006_book_files.sql":                  "14cf0559328fd93de8e6f13ad9ab8cf0505027dffb6ce2f7858a4cd2d6faf770",
	"0007_ai_daily_usage.sql":              "dfc3460566b369f897e53e4c06a84c7cb2e49b56758afa0b6c7879aad25a0497",
	"0009_remove_research.sql":             "af57174979c3624e8cc8ce7fc273506bbb9758f5698188a5502b8dfb493267c7",
	"0010_companions.sql":                  "5328ebea79cbbab7552241e52737756ae56d876cf39942da8659fb3352743fe7",
	"0011_companion_phrase_pack_cache.sql": "e09f4d105434eec9d60d8bfdea82a518b06895c3abd23318faf36a63a17adb6b",
	"0012_annotation_generations.sql":      "85f023c963859ee23643db7345214201ce4064a4f1890c1ee54931d4aa39ac85",
	"0013_book_files_storage_key.sql":      "dad988182cd95f994110497eb5633103ed44d50bfb82182b57ec8f29676a1915",
}

func TestПрименённыеМиграцииНеМеняются(t *testing.T) {
	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		t.Fatalf("чтение миграций: %v", err)
	}

	seen := make(map[string]bool, len(entries))
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := entry.Name()
		seen[name] = true

		body, err := migrations.ReadFile("migrations/" + name)
		if err != nil {
			t.Fatalf("чтение %s: %v", name, err)
		}
		sum := sha256.Sum256(body)
		actual := hex.EncodeToString(sum[:])

		expected, known := migrationChecksums[name]
		if !known {
			t.Errorf("новая миграция %s: добавьте отпечаток в migrationChecksums\n\t%q: %q,", name, name, actual)
			continue
		}
		if expected != actual {
			t.Errorf(
				"миграция %s изменена после применения.\n"+
					"Журнал в базе помнит имя файла, поэтому изменённый файл не выполнится там, где он уже прошёл: "+
					"свежая база получит новую схему, а production останется со старой.\n"+
					"Верните файл как был и напишите новую миграцию. Если правка точно не трогает схему, "+
					"обновите отпечаток осознанно:\n\t%q: %q,",
				name, name, actual,
			)
		}
	}

	missing := make([]string, 0)
	for name := range migrationChecksums {
		if !seen[name] {
			missing = append(missing, name)
		}
	}
	sort.Strings(missing)
	for _, name := range missing {
		// Удалить применённую миграцию так же опасно, как изменить: имя
		// останется в журнале, а на свежей базе схемы просто не будет.
		// Правильный способ отменить - новая миграция, как сделала 0009.
		t.Errorf("миграция %s исчезла: отменяйте её новой миграцией, а не удалением файла", name)
	}
}
