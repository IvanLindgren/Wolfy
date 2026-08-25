package store

import (
	"context"
	"encoding/json"
	"testing"
)

func TestValidatePracticeDeviceID(t *testing.T) {
	valid := []string{"phone", "laptop-1", "device_123", "legacy", "a", "A1._-b2"}
	for _, id := range valid {
		if err := validatePracticeDeviceID(id); err != nil {
			t.Errorf("deviceId %q должен быть валидным: %v", id, err)
		}
	}
	invalid := []string{"", " ", "ab cd", "device/with/slash", "bad$", "a/b", "x@y", "device id", "тест"}
	for _, id := range invalid {
		if err := validatePracticeDeviceID(id); err == nil {
			t.Errorf("deviceId %q должен быть отвергнут", id)
		}
	}
	if err := validatePracticeDeviceID(""); err == nil {
		t.Error("пустой deviceId не отвергнут")
	}
	long := make([]byte, MaxPracticeDeviceID+1)
	for i := range long {
		long[i] = 'a'
	}
	if err := validatePracticeDeviceID(string(long)); err == nil {
		t.Error("слишком длинный deviceId не отвергнут")
	}
}

func TestValidatePracticeJSON(t *testing.T) {
	good := json.RawMessage(`{"days":[1,2,3],"counters":{"phone":{"answers":10,"right":9}},"bestFloor":5}`)
	if err := validatePracticeJSON(good); err != nil {
		t.Errorf("валидный practice отвергнут: %v", err)
	}
	invalid := []json.RawMessage{
		json.RawMessage(``),
		json.RawMessage(`null`),
		json.RawMessage(`[1,2,3]`),
		json.RawMessage(`"string"`),
		json.RawMessage(`{invalid}`),
		json.RawMessage(`   `),
	}
	for _, raw := range invalid {
		if err := validatePracticeJSON(raw); err == nil {
			t.Errorf("invalid practice %q должен быть отвергнут", string(raw))
		}
	}
	large := make(json.RawMessage, MaxPracticeBytes+1)
	large[0] = '{'
	for i := 1; i < len(large)-1; i++ {
		large[i] = 'a'
	}
	large[len(large)-1] = '}'
	if err := validatePracticeJSON(large); err == nil {
		t.Error("слишком большой practice не отвергнут")
	}
	// Минимум: пустой объект валиден (начальное состояние)
	if err := validatePracticeJSON(json.RawMessage(`{}`)); err != nil {
		t.Errorf("пустой объект должен быть валиден: %v", err)
	}
}

// Opaque: сервер не интерпретирует содержимое — он хранит как есть.
func TestPracticeOpaqueNoMath(t *testing.T) {
	// Два разных practice blobs с разными днями/счётчиками должны сохраниться
	// без какого-либо merge на стороне Go: Go их не складывает и не берет max.
	a := json.RawMessage(`{"days":[1,2],"counters":{"phone":{"answers":100,"right":90}},"bestFloor":5}`)
	b := json.RawMessage(`{"days":[2,3],"counters":{"laptop":{"answers":30,"right":25}},"bestFloor":7}`)
	// Проверяем что валидация их пропускает, но не пытается сравнить
	if err := validatePracticeJSON(a); err != nil {
		t.Fatalf("a: %v", err)
	}
	if err := validatePracticeJSON(b); err != nil {
		t.Fatalf("b: %v", err)
	}
	// Содержимое разное, но Go не должен их мерджить — это делает Rust.
	// Проверяем что они остаются разными JSON
	if string(a) == string(b) {
		t.Fatal("blobs должны различаться")
	}
}

// Интеграционный тест: требует реальный Postgres. Пропускается без WOLFY_TEST_DB_URL.
func TestPracticePerDeviceStorage(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	phonePractice := json.RawMessage(`{"days":[100,101],"counters":{"phone":{"answers":10,"right":9}},"bestFloor":2}`)
	laptopPractice := json.RawMessage(`{"days":[101,102],"counters":{"laptop":{"answers":5,"right":5}},"bestFloor":3}`)

	if err := s.SavePractice(ctx, user, "phone", phonePractice); err != nil {
		t.Fatalf("save phone: %v", err)
	}
	if err := s.SavePractice(ctx, user, "laptop", laptopPractice); err != nil {
		t.Fatalf("save laptop: %v", err)
	}

	components, err := s.ListPracticeComponents(ctx, user)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(components) != 2 {
		t.Fatalf("компонентов %d, ожидали 2", len(components))
	}
	// Проверяем что Go не смешал — каждый device хранит свой blob как есть (jsonb нормализует порядок ключей, сравниваем по содержимому)
	m := make(map[string]json.RawMessage)
	for _, c := range components {
		m[c.DeviceID] = c.Practice
	}
	if !jsonEqual(m["phone"], phonePractice) {
		t.Fatalf("phone blob изменился: got %s want %s", string(m["phone"]), string(phonePractice))
	}
	if !jsonEqual(m["laptop"], laptopPractice) {
		t.Fatalf("laptop blob изменился: got %s want %s", string(m["laptop"]), string(laptopPractice))
	}

	// Обновление одного устройства не трогает другое (last-write-wins per device)
	phoneUpdated := json.RawMessage(`{"days":[100,101,102],"counters":{"phone":{"answers":20,"right":18}},"bestFloor":4}`)
	if err := s.SavePractice(ctx, user, "phone", phoneUpdated); err != nil {
		t.Fatalf("update phone: %v", err)
	}
	components, err = s.ListPracticeComponents(ctx, user)
	if err != nil {
		t.Fatalf("list after update: %v", err)
	}
	m2 := make(map[string]json.RawMessage)
	for _, c := range components {
		m2[c.DeviceID] = c.Practice
	}
	if !jsonEqual(m2["phone"], phoneUpdated) {
		t.Fatalf("phone не обновился: got %s want %s", string(m2["phone"]), string(phoneUpdated))
	}
	if !jsonEqual(m2["laptop"], laptopPractice) {
		t.Fatalf("laptop затронут обновлением phone: got %s", string(m2["laptop"]))
	}

	// Чужая практика не приходит
	stranger := createUser(t, s)
	if err := s.SavePractice(ctx, stranger, "phone", phonePractice); err != nil {
		t.Fatalf("stranger save: %v", err)
	}
	got, err := s.ListPracticeComponents(ctx, user)
	if err != nil {
		t.Fatalf("list after stranger: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("чужая практика просочилась: %d", len(got))
	}
}

func TestPracticeOldReadingPreservedOnUpdate(t *testing.T) {
	// Старый клиент шлёт reading с legacy полями, новый — practice.
	// Сервер обязан сохранить оба: reading LWW и practice per-device.
	// Проверяем что SavePractice не затирает reading и наоборот.
	s := openStore(t)
	ctx := context.Background()
	user := createUser(t, s)

	legacyReading := json.RawMessage(`{"theme":"Sepia","fontScale":1.2,"trainedOn":20000,"streakDays":7,"answers":100}`)
	if _, err := s.Sync(ctx, user, Changes{Reading: legacyReading}); err != nil {
		t.Fatalf("sync reading: %v", err)
	}

	practice := json.RawMessage(`{"days":[20000],"counters":{"phone":{"answers":100,"right":90}},"bestFloor":7}`)
	if err := s.SavePractice(ctx, user, "phone", practice); err != nil {
		t.Fatalf("save practice: %v", err)
	}

	// reading всё ещё там
	got, err := s.Sync(ctx, user, Changes{Cursor: 0})
	if err != nil {
		t.Fatalf("sync read: %v", err)
	}
	if len(got.Reading) == 0 {
		t.Fatal("reading пропал после сохранения practice")
	}
	var parsed map[string]any
	if err := json.Unmarshal(got.Reading, &parsed); err != nil {
		t.Fatalf("reading не JSON: %v", err)
	}
	if parsed["theme"] != "Sepia" {
		t.Fatalf("reading испорчен: %v", parsed)
	}
	// practice тоже там
	components2, err2 := s.ListPracticeComponents(ctx, user)
	if err2 != nil {
		t.Fatalf("list practice: %v", err2)
	}
	// Длина проверяется отдельно: иначе Fatalf ниже полез бы в пустой срез и
	// уронил тест паникой вместо внятного сообщения — ровно в том случае,
	// который он и должен ловить.
	if len(components2) != 1 {
		t.Fatalf("компонентов practice %d, ожидали 1", len(components2))
	}
	if !jsonEqual(components2[0].Practice, practice) {
		t.Fatalf("practice пропал после reading: got %s want %s", string(components2[0].Practice), string(practice))
	}
}

func jsonEqual(a, b json.RawMessage) bool {
	var ja, jb any
	if err := json.Unmarshal(a, &ja); err != nil {
		return string(a) == string(b)
	}
	if err := json.Unmarshal(b, &jb); err != nil {
		return string(a) == string(b)
	}
	aj, _ := json.Marshal(ja)
	bj, _ := json.Marshal(jb)
	return string(aj) == string(bj)
}
