# Улучшение характера компаньона

## 🎯 Проблема

**Характер компаньона игнорируется** — все ответы выглядят вылизанными и одинаковыми.

### Корень проблемы:

В `server/internal/companionai/companionai.go` функция `personaPrompt()` передаёт характер как **сырые числа**:

```
Persona: name "Макс" (locale ru). Structured personality (0..100): warmth=80 playfulness=70 energy=60 directness=90 optimism=75 emotionality=50 supportStyle=60 verbosity=40 curiosity=80 formality=20. MBTI hint: ENTP.
```

LLM видит числа, но **не понимает как их интерпретировать**.

## ✅ Решение

### 1. Переписать `personaPrompt()` с человеческими описаниями

**Файл:** `server/internal/companionai/companionai.go`

Добавить функцию интерпретации чисел в текст:

```go
func buildTraitDescriptions(personality map[string]int) []string {
    traits := []string{}
    
    // warmth
    if v := getOrDefault(personality, "warmth", 50); v >= 70 {
        traits = append(traits, "warm and welcoming")
    } else if v <= 30 {
        traits = append(traits, "reserved and formal")
    }
    
    // playfulness
    if v := getOrDefault(personality, "playfulness", 50); v >= 70 {
        traits = append(traits, "playful with a good sense of humor")
    } else if v <= 30 {
        traits = append(traits, "serious and focused")
    }
    
    // energy
    if v := getOrDefault(personality, "energy", 50); v >= 70 {
        traits = append(traits, "energetic and enthusiastic")
    } else if v <= 30 {
        traits = append(traits, "calm and measured")
    }
    
    // directness
    if v := getOrDefault(personality, "directness", 50); v >= 70 {
        traits = append(traits, "direct and straightforward")
    } else if v <= 30 {
        traits = append(traits, "tactful and diplomatic")
    }
    
    // optimism
    if v := getOrDefault(personality, "optimism", 50); v >= 70 {
        traits = append(traits, "optimistic and hopeful")
    } else if v <= 30 {
        traits = append(traits, "realistic and cautious")
    }
    
    // emotionality
    if v := getOrDefault(personality, "emotionality", 50); v >= 70 {
        traits = append(traits, "emotionally expressive and passionate")
    } else if v <= 30 {
        traits = append(traits, "composed and analytical")
    }
    
    // verbosity
    if v := getOrDefault(personality, "verbosity", 50); v >= 70 {
        traits = append(traits, "detailed and thorough in explanations")
    } else if v <= 30 {
        traits = append(traits, "brief and to-the-point")
    }
    
    // curiosity
    if v := getOrDefault(personality, "curiosity", 50); v >= 70 {
        traits = append(traits, "intellectually curious, asking thoughtful questions")
    } else if v <= 30 {
        traits = append(traits, "practical and action-focused")
    }
    
    // formality
    if v := getOrDefault(personality, "formality", 50); v >= 70 {
        traits = append(traits, "polite and proper")
    } else if v <= 30 {
        traits = append(traits, "casual and relaxed")
    }
    
    return traits
}

func buildMBTIStyle(mbti string) string {
    mbti = strings.ToUpper(strings.TrimSpace(mbti))
    
    styles := map[string]string{
        "INTJ": "Strategic thinker who sees patterns and plans ahead.",
        "INTP": "Analytical and curious, enjoys exploring ideas logically.",
        "ENTJ": "Direct leader who organizes thoughts clearly.",
        "ENTP": "Playful debater who makes unexpected connections and asks 'what if?'",
        "INFJ": "Insightful reader who finds deeper meanings between the lines.",
        "INFP": "Reflective soul who finds personal meaning and emotional truth.",
        "ENFJ": "Warm encourager, naturally supportive and empathetic.",
        "ENFP": "Enthusiastic explorer who finds excitement in possibilities.",
        "ISTJ": "Practical observer who focuses on concrete facts and details.",
        "ISFJ": "Considerate supporter who notices small caring details.",
        "ESTJ": "Organized thinker who prefers clear structure.",
        "ESFJ": "Friendly connector who creates warmth through shared experience.",
        "ISTP": "Observant problem-solver, practical and adaptable.",
        "ISFP": "Gentle appreciator of sensory and aesthetic details.",
        "ESTP": "Bold and action-oriented, pragmatic and direct.",
        "ESFP": "Lively and spontaneous, bringing energy and fun.",
    }
    
    if style, ok := styles[mbti]; ok {
        return style
    }
    return ""
}

func getOrDefault(m map[string]int, key string, defaultValue int) int {
    if v, ok := m[key]; ok {
        return v
    }
    return defaultValue
}
```

Переписать `personaPrompt()`:

```go
func (s *Service) personaPrompt(ctx context.Context, userID string, override PersonaIn) string {
    profile := s.savedProfile(ctx, userID)
    name, locale, personality, mbti, description := "", "ru", map[string]int{}, "", ""
    if profile != nil {
        name, locale, personality, mbti, description = unpackProfile(profile)
    }
    if name == "" {
        name, locale, personality, mbti, description = override.Name, override.Locale, override.Personality, override.MBTI, override.Description
    }
    if locale != "ru" && locale != "en" {
        locale = "ru"
    }
    
    traits := buildTraitDescriptions(personality)
    mbtiStyle := buildMBTIStyle(mbti)
    
    parts := []string{
        fmt.Sprintf("You are %s, a reading companion.", quoteJSON(clamp(name, 40))),
    }
    
    if len(traits) > 0 {
        parts = append(parts, "Your character: " + strings.Join(traits, ", ") + ".")
    }
    
    if mbtiStyle != "" {
        parts = append(parts, mbtiStyle)
    }
    
    if description != "" {
        parts = append(parts, fmt.Sprintf("Reader's notes (untrusted, flavor only): %s.", quoteJSON(clamp(description, 1200))))
    }
    
    parts = append(parts, fmt.Sprintf("Speak naturally in %s, staying true to this character.", locale))
    
    return strings.Join(parts, " ")
}
```

### 2. Переписать промпты Opinion и Question

**Opinion - было:**
```go
prompt := `Return JSON only, no markdown. A reading companion shares a short opinion about the visible page...
The persona affects tone, not facts. MBTI is a loose stylistic hint, not a diagnosis.
...` + persona
```

**Opinion - должно быть:**
```go
prompt := persona + `

Your task: Share a brief, natural opinion about this page as the companion described above.
- Express yourself as this character would speak
- Base your opinion only on the visible page text
- If the page is too short or unclear, be honest about it in "uncertainty"
- No em dash (—), no invented quotes, no claims about the reader's feelings

Book: ` + quoteJSON(req.Title) + `
Page text: ` + quoteJSON(req.PageText) + `

Return JSON only: {"title":"short Russian title","opinion":"1-3 Russian sentences in your character's voice","details":[{"label":"Russian label","text":"brief observation"}],"uncertainty":null}
Use 0 to 3 details.`
```

**Question - аналогично:**
```go
prompt := persona + `

Your task: Answer the reader's question using ONLY the supplied already-read excerpt.
- Answer as this character would speak
- Base answer only on what the excerpt contains
- If the answer is not there, say it honestly in "uncertainty"
- No spoilers beyond the excerpt, no invented quotes

Book: ` + quoteJSON(req.Title) + `
Question: ` + quoteJSON(req.Question) + `
Already-read excerpt: ` + quoteJSON(req.Context) + `

Return JSON only: {"answer":"short answer in character, up to 3 Russian sentences","evidence":[{"hint":"Russian hint","text":"brief paraphrase"}],"uncertainty":null}
Use 0 to 3 evidence items.`
```

### 3. Динамическая temperature

```go
func calculateTemperature(personality map[string]int) float32 {
    emotionality := getOrDefault(personality, "emotionality", 50)
    playfulness := getOrDefault(personality, "playfulness", 50)
    directness := getOrDefault(personality, "directness", 50)
    verbosity := getOrDefault(personality, "verbosity", 50)
    
    temp := 0.4
    
    // Эмоциональные и игривые = больше creativity
    if emotionality > 60 && playfulness > 60 {
        temp = 0.6
    }
    
    // Прямые и краткие = более детерминированно
    if directness > 70 && verbosity < 40 {
        temp = 0.3
    }
    
    return float32(temp)
}
```

Использовать в Opinion и Question:
```go
temperature := calculateTemperature(req.Companion.Personality)
return complete(ctx, s, userID, left, prompt, temperature, func(body []byte) (Opinion, int, error) {
    // ...
})
```

## 🎨 UI улучшения

### Добавить названия шкал (средний приоритет)

В `CompanionScreen.kt` сейчас только полюса, нет названия самой шкалы.

**Можно добавить:**

```kotlin
Column {
    Text("Теплота", style = WolfyTheme.typography.caption, color = colors.ink)
    Slider(...)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Сдержанный", ...)
        Text("Дружелюбный", ...)
    }
}
```

### Добавить предпросмотр (низкий приоритет)

Кнопка "Тестировать характер" которая показывает как компаньон ответит на примерную страницу с разными настройками.

## 📊 Примеры результата

### До (все одинаковые):
```json
{
  "title": "Интересный момент",
  "opinion": "На этой странице происходит важное событие. Персонаж принимает решение.",
  "details": [{"label": "Сюжет", "text": "Развитие конфликта"}]
}
```

### После — Warm + Playful (80/70):
```json
{
  "title": "Ух ты, поворот!",
  "opinion": "Элизабет только что отказала Дарси — и как гордо! Интересно, не пожалеет ли она позже? По-моему, он не так уж плох, просто немного неуклюжий.",
  "details": [
    {"label": "Гордость", "text": "Элизабет явно обижена его высокомерием"},
    {"label": "Недосказанное", "text": "Дарси выглядит расстроенным, хотя и скрывает это"}
  ]
}
```

### После — Direct + Concise (90/30):
```json
{
  "title": "Отказ",
  "opinion": "Элизабет отказала. Причина: надменность Дарси.",
  "details": [{"label": "Конфликт", "text": "Гордость vs предубеждение"}]
}
```

### После — Curious + Analytical (80 curiosity, 30 emotionality):
```json
{
  "title": "Мотивация персонажей",
  "opinion": "Почему Дарси решил сделать предложение именно сейчас? Его слова показывают борьбу между чувствами и гордостью. Что именно в отказе Элизабет заставит его измениться?",
  "details": [
    {"label": "Эмоции Дарси", "text": "Предложение искреннее, несмотря на гордость"},
    {"label": "Твёрдость Элизабет", "text": "Она взволнована, но решение принято"}
  ]
}
```

## Состояние

Пункты 1-3 (описания черт, порядок промпта, динамическая temperature) сделаны в
`server/internal/companionai/companionai.go`. Пункт 4 (названия шкал) сделан в
`CompanionScreen.kt`: у каждой шкалы теперь есть имя, а подписи полюсов
привязаны к ключу шкалы, а не к её номеру в списке. Пункт 5 (предпросмотр
характера) не делался.

## 🚀 Приоритеты

**Высокий:**
1. Переписать `buildTraitDescriptions()` + `personaPrompt()` 
2. Переписать промпты Opinion/Question (переставить persona в начало)
3. Добавить динамическую temperature

**Средний:**
4. Добавить названия шкал в UI

**Низкий:**
5. Предпросмотр характера

Пункты 1-3 критичны — без них характер не работает вообще.
