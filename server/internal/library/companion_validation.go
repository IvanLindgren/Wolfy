package library

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/wolfy/server/internal/store"
)

var companionPersonalityKeys = map[string]bool{
	"warmth": true, "playfulness": true, "energy": true, "directness": true,
	"optimism": true, "emotionality": true, "supportStyle": true,
	"verbosity": true, "curiosity": true, "formality": true,
}

var companionMBTI = map[string]bool{
	"INTJ": true, "INTP": true, "ENTJ": true, "ENTP": true,
	"INFJ": true, "INFP": true, "ENFJ": true, "ENFP": true,
	"ISTJ": true, "ISFJ": true, "ESTJ": true, "ESFJ": true,
	"ISTP": true, "ISFP": true, "ESTP": true, "ESFP": true,
}

type companionProfilePayload struct {
	ID               string          `json:"id"`
	Name             string          `json:"name"`
	Pronouns         *string         `json:"pronouns"`
	Presentation     string          `json:"presentation"`
	Locale           string          `json:"locale"`
	Personality      map[string]int  `json:"personality"`
	MBTI             *string         `json:"mbti"`
	Description      string          `json:"description"`
	Appearance       json.RawMessage `json:"appearance"`
	ReaderMode       string          `json:"readerMode"`
	ReactionsEnabled *bool           `json:"reactionsEnabled"`
	AIConsentAt      int64           `json:"aiConsentAt"`
	CreatedAt        int64           `json:"createdAt"`
	UpdatedAt        int64           `json:"updatedAt"`
}

// ValidateCompanionProfile проверяет тот же профиль и в sync, и перед AI pack.
func ValidateCompanionProfile(body json.RawMessage) error {
	var profile companionProfilePayload
	if err := json.Unmarshal(body, &profile); err != nil {
		return errors.New("профиль компаньона не разобран")
	}
	if !uuidPattern.MatchString(profile.ID) {
		return fmt.Errorf("непонятный номер компаньона: %q", profile.ID)
	}
	name := strings.TrimSpace(profile.Name)
	if utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 40 {
		return errors.New("имя компаньона должно содержать от 1 до 40 знаков")
	}
	if profile.Pronouns != nil && utf8.RuneCountInString(*profile.Pronouns) > 80 {
		return errors.New("обращение к компаньону слишком длинное")
	}
	if profile.Presentation != "" && profile.Presentation != "masculine" && profile.Presentation != "feminine" && profile.Presentation != "neutral" {
		return errors.New("неизвестный стартовый образ компаньона")
	}
	if profile.Locale != "ru" && profile.Locale != "en" {
		return errors.New("неизвестная локаль компаньона")
	}
	if utf8.RuneCountInString(profile.Description) > 1200 {
		return errors.New("описание компаньона слишком длинное")
	}
	if profile.MBTI != nil && !companionMBTI[strings.ToUpper(strings.TrimSpace(*profile.MBTI))] {
		return errors.New("неизвестный MBTI компаньона")
	}
	for key, value := range profile.Personality {
		if !companionPersonalityKeys[key] || value < 0 || value > 100 {
			return fmt.Errorf("неверная шкала характера: %q", key)
		}
	}
	if profile.ReaderMode != "" && profile.ReaderMode != "off" && profile.ReaderMode != "quiet" && profile.ReaderMode != "active" {
		return errors.New("неизвестный режим компаньона в читалке")
	}
	if len(profile.Appearance) > 0 && string(profile.Appearance) != "null" && profile.Appearance[0] != '{' {
		return errors.New("внешность компаньона должна быть объектом")
	}
	if profile.CreatedAt < 0 || profile.UpdatedAt < 0 || profile.AIConsentAt < 0 {
		return errors.New("время профиля компаньона не может быть отрицательным")
	}
	return nil
}

type companionPackPayload struct {
	SchemaVersion int                   `json:"schemaVersion"`
	ProfileHash   string                `json:"profileHash"`
	Locale        string                `json:"locale"`
	GeneratedAt   int64                 `json:"generatedAt"`
	Source        string                `json:"source"`
	Phrases       []companionPackPhrase `json:"phrases"`
}

type companionPackPhrase struct {
	ID              string   `json:"id"`
	Scenario        string   `json:"scenario"`
	Text            string   `json:"text"`
	MinMinutes      int      `json:"minMinutes"`
	CooldownMinutes int      `json:"cooldownMinutes"`
	Weight          int      `json:"weight"`
	Moods           []string `json:"moods"`
	Motion          string   `json:"motion"`
}

var companionScenarioCounts = map[string]int{
	"session_start": 10, "session_resume": 8, "steady_reading": 18,
	"page_completed": 10, "chapter_completed": 10, "long_session": 8,
	"return_after_break": 8, "difficult_page": 8,
	"mood_joy": 4, "mood_sadness": 4, "mood_tension": 4, "mood_mystery": 4,
	"session_end": 4,
}

func validateCompanionPack(body json.RawMessage) error {
	if len(body) == 0 || string(body) == "null" {
		return nil
	}
	var pack companionPackPayload
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&pack); err != nil {
		return errors.New("набор реплик не разобран")
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		return errors.New("после набора реплик есть лишние данные")
	}
	if pack.SchemaVersion != 1 || (pack.Locale != "ru" && pack.Locale != "en") || len(pack.Phrases) != 100 {
		return errors.New("неверная версия, локаль или размер набора реплик")
	}
	if pack.Source != "generated" && pack.Source != "cache" && pack.Source != "fallback" {
		return errors.New("неизвестный источник набора реплик")
	}
	seen := map[string]bool{}
	counts := map[string]int{}
	allowedMotion := map[string]bool{"none": true, "wave": true, "nod": true, "peek": true, "think": true, "speak": true}
	allowedMood := map[string]bool{"joy": true, "sadness": true, "tension": true, "mystery": true}
	for _, phrase := range pack.Phrases {
		if seen[phrase.ID] || !safeCompanionText(phrase.Text) {
			return errors.New("повторяющаяся реплика или небезопасный текст")
		}
		seen[phrase.ID] = true
		counts[phrase.Scenario]++
		if phrase.MinMinutes < 0 || phrase.MinMinutes > 90 || phrase.CooldownMinutes < 0 || phrase.CooldownMinutes > 120 || phrase.Weight < 1 || phrase.Weight > 100 || !allowedMotion[phrase.Motion] {
			return errors.New("неверные ограничения реплики")
		}
		for _, mood := range phrase.Moods {
			if !allowedMood[mood] || phrase.Scenario != "mood_"+mood {
				return errors.New("неверное настроение реплики")
			}
		}
		if !strings.HasPrefix(phrase.Scenario, "mood_") && len(phrase.Moods) != 0 {
			return errors.New("настроение указано не для mood-сценария")
		}
	}
	for scenario, want := range companionScenarioCounts {
		if counts[scenario] != want {
			return errors.New("неверное распределение сценариев")
		}
		for index := 1; index <= want; index++ {
			if !seen[fmt.Sprintf("%s.%02d", scenario, index)] {
				return errors.New("неверные идентификаторы реплик")
			}
		}
	}
	return nil
}

func safeCompanionText(text string) bool {
	text = strings.TrimSpace(text)
	length := utf8.RuneCountInString(text)
	if length < 2 || length > 120 || strings.Contains(text, "\u2014") || strings.Contains(text, "\u2013") || strings.Contains(strings.ToLower(text), "http") || strings.ContainsAny(text, "*#`_|[<") {
		return false
	}
	return !strings.ContainsFunc(text, unicode.IsControl)
}

func validateCompanionPayload(c *store.Companion) error {
	if err := ValidateCompanionProfile(c.Profile); err != nil {
		return err
	}
	return validateCompanionPack(c.PhrasePack)
}
