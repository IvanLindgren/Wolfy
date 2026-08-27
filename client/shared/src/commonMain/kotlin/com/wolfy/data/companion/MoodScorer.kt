package com.wolfy.data.companion

/**
 * Локальная оценка настроения страницы.
 *
 * Нужна только для выбора одной из заготовленных реплик, поэтому это простой
 * лексиконный счётчик, а не модель: одинаковый текст обязан давать одинаковый
 * ответ на Android, desktop и web без сети и без тяжёлых зависимостей.
 *
 * Компаньон говорит о тексте страницы, а не о состоянии читателя: «здесь
 * тревожно», а не «тебе страшно».
 */
object MoodScorer {
    /** Настроения, различаемые на MVP. */
    const val NEUTRAL = "neutral"
    const val JOY = "joy"
    const val SADNESS = "sadness"
    const val TENSION = "tension"
    const val MYSTERY = "mystery"
    const val CALM = "calm"

    /** Итог анализа: настроение, уверенность и независимая оценка трудности. */
    data class Result(val mood: String, val confidence: Float, val difficulty: Float)

    // Небольшой проверяемый лексикон. Слова в нижнем регистре, без производных:
    // нормализация текста срезает окончания грубо, зато предсказуемо.
    private val joy = setOf(
        "happy", "joy", "joyful", "laugh", "laughed", "smile", "smiled", "delight",
        "wonderful", "glad", "cheerful", "bright", "merry", "celebration", "dance",
        "люб", "радост", "весел", "счастл", "улыб", "смех", "смеял", "праздник",
        "светл", "прекрасн", "чудесн", "счастлив",
    )
    private val sadness = setOf(
        "sad", "sorrow", "grief", "cry", "cried", "tears", "weep", "lonely",
        "misery", "mourn", "goodbye", "lost", "pain", "hurt",
        "груст", "печал", "тоска", "плак", "слёз", "слез", " горе", "одинок",
        "прощай", "боль", "утрат", "скорб",
    )
    private val tension = setOf(
        "fear", "afraid", "terror", "panic", "danger", "dangerous", "threat",
        "scream", "shout", "blood", "fight", "attack", "chase", "escape", "sudden",
        "страх", "ужас", "паник", "опасн", "угроз", "крик", "кров", "драк",
        "напад", "погон", "бежал", "внезапн", "тревог",
    )
    private val mystery = setOf(
        "mystery", "mysterious", "secret", "shadow", "whisper", "strange", "riddle",
        "hidden", "vanish", "disappeared", "clue", "fog", "dark", "silence", "keys",
        "тайн", "загадк", "секрет", "тень", "шёпот", "шепот", "странн", "скрыт",
        "исчез", "пропал", "намёк", "туман", "темнот", "тишин",
    )
    private val calm = setOf(
        "calm", "quiet", "peace", "gentle", "soft", "warm", "slow", "rest", "breeze",
        "спокой", "тих", "мир", "нежн", "мягк", "тепл", "медленн", "отдых", "ветер",
    )
    private val difficult = setOf(
        "moreover", "nevertheless", "notwithstanding", "consequently", "furthermore",
        "hitherto", "thereby", "wherein", "hereby", "henceforth",
        "впрочем", "тем не менее", "следовательно", "постольку", "невзирая",
    )

    /** Отрицания: они переворачивают вклад следующего слова. */
    private val negations = setOf(
        "not", "no", "never", "neither", "nor", "without",
        "не", "нет", "ни", "без", "вовсе",
    )

    private const val MAX_WORDS = 1200
    /** Столько совпадений подряд сами по себе делают настроение уверенным. */
    private const val CONFIDENT_HITS = 2
    /** Либо плотность совпадений не ниже этой доли слов. */
    private const val CONFIDENT_DENSITY = 0.12f

    /**
     * Оценивает фрагмент страницы.
     *
     * Вход ограничен последними [MAX_WORDS] словами: анализ обязан быть
     * дешёвым и не расти с размером главы. Совпадения ищутся по началу слова:
     * лексикон хранит основы, а не все формы. Настроение признаётся только
     * при достаточной плотности совпадений: одна случайная подсказка в
     * длинном тексте не делает страницу грустной.
     */
    fun analyze(text: String): Result {
        val normalized = text.lowercase()
        val tokens = normalized.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }
        val limited = if (tokens.size > MAX_WORDS) tokens.takeLast(MAX_WORDS) else tokens

        var difficultHits = 0
        var negated = false
        var questions = 0
        var exclamations = 0
        val hits = linkedMapOf(
            JOY to 0, SADNESS to 0, TENSION to 0, MYSTERY to 0, CALM to 0,
        )
        var joyScore = 0f
        var sadnessScore = 0f
        var tensionScore = 0f
        var mysteryScore = 0f
        var calmScore = 0f
        for (token in limited) {
            if (token in negations) {
                negated = true
                continue
            }
            val weight = if (negated) -1f else 1f
            negated = false
            if (matches(token, joy)) { joyScore += weight; hits[JOY] = hits[JOY]!! + 1 }
            if (matches(token, sadness)) { sadnessScore += weight; hits[SADNESS] = hits[SADNESS]!! + 1 }
            if (matches(token, tension)) { tensionScore += weight; hits[TENSION] = hits[TENSION]!! + 1 }
            if (matches(token, mystery)) { mysteryScore += weight; hits[MYSTERY] = hits[MYSTERY]!! + 1 }
            if (matches(token, calm)) { calmScore += weight; hits[CALM] = hits[CALM]!! + 1 }
            if (token.startsWithAny(difficult)) difficultHits += 1
            if (token == "?") questions += 1
            if (token == "!") exclamations += 1
        }
        // Вопросы и восклицания: слабые признаки напряжения и радости.
        tensionScore += questions * 0.3f + exclamations * 0.2f
        joyScore += exclamations * 0.3f

        val scoresFinal = linkedMapOf(
            JOY to joyScore, SADNESS to sadnessScore, TENSION to tensionScore,
            MYSTERY to mysteryScore, CALM to calmScore,
        )
        val best = scoresFinal.maxByOrNull { it.value } ?: return Result(NEUTRAL, 0f, 0f)
        val bestHits = hits[best.key] ?: 0
        val density = if (limited.isEmpty()) 0f else bestHits.toFloat() / limited.size
        val confident = best.value > 0f && (bestHits >= CONFIDENT_HITS || density >= CONFIDENT_DENSITY)
        val mood = if (confident) best.key else NEUTRAL
        val confidence = when {
            !confident -> 0f
            bestHits >= CONFIDENT_HITS -> (bestHits / 6f).coerceAtMost(1f)
            else -> (density / CONFIDENT_DENSITY * 0.5f).coerceAtMost(0.5f)
        }
        val difficulty = (difficultHits / 20f).coerceIn(0f, 1f)
        return Result(mood, confidence, difficulty)
    }

    private fun matches(token: String, lexicon: Set<String>): Boolean = token.startsWithAny(lexicon)

    private fun String.startsWithAny(prefixes: Set<String>): Boolean {
        for (prefix in prefixes) if (startsWith(prefix)) return true
        return false
    }
}
