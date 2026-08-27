package com.wolfy.data.companion

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Профиль книжного компаньона.
 *
 * Компаньон необязателен: приложение целиком работает без него, поэтому
 * профиль здесь только данные, без логики чтения. Поля повторяют серверный
 * контракт: профиль едет в синхронизации как единая LWW-запись, и расхождение
 * имён между клиентом и сервером стоило бы отдельной миграции на ровном месте.
 *
 * Внешность хранит идентификаторы слоёв, а не готовую картинку: превью
 * собирается из слоёв пака, поэтому сменить причёску можно без пересоздания
 * чего-либо.
 */
@Serializable
data class CompanionProfile(
    val id: String,
    val name: String,
    val pronouns: String? = null,
    /** BCP 47. На MVP приложение говорит о компаньоне на двух языках. */
    val locale: String = "ru",
    val personality: CompanionPersonality = CompanionPersonality(),
    /** Один из 16 кодов MBTI или null. Подсказка стиля, не диагноз. */
    val mbti: String? = null,
    /** Собственное описание речи компаньона. Не приказы модели. */
    val description: String = "",
    val appearance: CompanionAppearance = CompanionAppearance(),
    val phrasePack: CompanionPhrasePack? = null,
    /** Реплики при чтении можно выключить, оставив персонажа и ручные вопросы. */
    val reactionsEnabled: Boolean = true,
    /** Режим в читалке: `off`, `quiet`, `active`. */
    val readerMode: String = "active",
    /** Время согласия на передачу фрагмента серверному ИИ, ноль: согласия нет. */
    val aiConsentAt: Long = 0,
    /**
     * Канонический хеш персональных полей.
     *
     * Считается на сохранении и едет в синхронизации: по нему сервер находит
     * сохранённый набор реплик и не платит квотой за тот же характер.
     */
    val profileHash: String = "",
    /** Серверная монотонная ревизия. Ноль: профиль ещё не синхронизировался. */
    val rev: Long = 0,
    val deleted: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Десять шкал характера, 0..100, шаг 1.
 *
 * Поля целые, а не дробные: ползунок двигается по единицам, и хранение
 *Float превратило бы сравнение профилей в сравнение с погрешностью.
 * Пятьдесят нейтрально; в интерфейсе показываются полюса, а не числа.
 */
@Serializable
data class CompanionPersonality(
    val warmth: Int = 50,
    val playfulness: Int = 50,
    val energy: Int = 50,
    val directness: Int = 50,
    val optimism: Int = 50,
    val emotionality: Int = 50,
    val supportStyle: Int = 50,
    val verbosity: Int = 50,
    val curiosity: Int = 50,
    val formality: Int = 50,
) {
    /** Возвращает копию со значением [value] на шкале [key]. */
    fun with(key: String, value: Int): CompanionPersonality = when (key) {
        "warmth" -> copy(warmth = value)
        "playfulness" -> copy(playfulness = value)
        "energy" -> copy(energy = value)
        "directness" -> copy(directness = value)
        "optimism" -> copy(optimism = value)
        "emotionality" -> copy(emotionality = value)
        "supportStyle" -> copy(supportStyle = value)
        "verbosity" -> copy(verbosity = value)
        "curiosity" -> copy(curiosity = value)
        "formality" -> copy(formality = value)
        else -> this
    }

    fun get(key: String): Int = when (key) {
        "warmth" -> warmth
        "playfulness" -> playfulness
        "energy" -> energy
        "directness" -> directness
        "optimism" -> optimism
        "emotionality" -> emotionality
        "supportStyle" -> supportStyle
        "verbosity" -> verbosity
        "curiosity" -> curiosity
        "formality" -> formality
        else -> 50
    }

    /** Канонический вид для хеширования: фиксированный порядок ключей. */
    fun toCanonicalJson(): JsonObject = buildJsonObject {
        for (key in KEYS) put(key, get(key))
    }

    companion object {
        val KEYS = listOf(
            "warmth", "playfulness", "energy", "directness", "optimism",
            "emotionality", "supportStyle", "verbosity", "curiosity", "formality",
        )
        const val MIN = 0
        const val MAX = 100
    }
}

/**
 * Внешность: пак слоёв и выбор внутри слотов.
 *
 * [seed] управляет кнопкой «Удивить меня»: одинаковый seed и версия пака
 * дают одинаковую внешность на всех клиентах, без случайности на каждый пуск.
 */
@Serializable
data class CompanionAppearance(
    val packId: String = PACK_ID,
    val packVersion: Int = 1,
    val base: String = "base.base",
    val body: String = "body.none",
    val hair: String = "hair.none",
    val brows: String = "brows.none",
    val eyes: String = "eyes.none",
    val nose: String = "nose.none",
    val mouth: String = "mouth.none",
    val beard: String = "beard.none",
    /** Спина не занята на MVP: слот в схеме, чтобы позже войти без миграций. */
    val accessoryBack: String = "accessoryBack.none",
    val accessoryFront: String = "accessoryFront.none",
    val gesture: String = "gesture.none",
    /** Палитра: имена предопределённых цветов из [com.wolfy.ui.companion.CompanionPalette]. */
    val skin: String = "paper",
    val hairColor: String = "ink",
    val outfitColor: String = "brick",
    val accentColor: String = "gold",
    val seed: Long = 0,
) {
    fun asset(slot: String): String = when (slot) {
        "base" -> base
        "body" -> body
        "hair" -> hair
        "brows" -> brows
        "eyes" -> eyes
        "nose" -> nose
        "mouth" -> mouth
        "beard" -> beard
        "accessoryBack" -> accessoryBack
        "accessoryFront" -> accessoryFront
        "gesture" -> gesture
        else -> "$slot.none"
    }

    fun withAsset(slot: String, assetId: String): CompanionAppearance = when (slot) {
        "base" -> copy(base = assetId)
        "body" -> copy(body = assetId)
        "hair" -> copy(hair = assetId)
        "brows" -> copy(brows = assetId)
        "eyes" -> copy(eyes = assetId)
        "nose" -> copy(nose = assetId)
        "mouth" -> copy(mouth = assetId)
        "beard" -> copy(beard = assetId)
        "accessoryBack" -> copy(accessoryBack = assetId)
        "accessoryFront" -> copy(accessoryFront = assetId)
        "gesture" -> copy(gesture = assetId)
        else -> this
    }

    companion object {
        const val PACK_ID = "notionists-wolfy-v1"
    }
}

/**
 * Набор из ста коротких реплик для обычного чтения.
 *
 * Ровно сто и ровно заданное распределение по сценариям: движок выбора опирается
 * на это распределение, и «примерно сто» сломало бы антиповтор и кулдауны.
 * Генерация и валидация на сервере; локальный fallback живёт в приложении.
 */
@Serializable
data class CompanionPhrasePack(
    /** Версия схемы распределения. Меняется вместе с составом сценариев. */
    val schemaVersion: Int = 1,
    /** Хеш персональных полей профиля, для которого сгенерирован набор. */
    val profileHash: String = "",
    val locale: String = "ru",
    val generatedAt: Long = 0,
    /** Откуда набор: `generated`, `fallback`, `cache`. Для диагностики. */
    val source: String = SOURCE_FALLBACK,
    val phrases: List<CompanionPhrase> = emptyList(),
) {
    companion object {
        const val SOURCE_GENERATED = "generated"
        const val SOURCE_FALLBACK = "fallback"
        const val SOURCE_CACHE = "cache"
    }
}

/** Одна реплика. Текст без markdown, переносов строк и длинного тире. */
@Serializable
data class CompanionPhrase(
    val id: String,
    val scenario: String,
    val text: String,
    /** Минуты чтения, после которых реплика уместна. */
    val minMinutes: Int = 0,
    /** Тишина после показа этой реплики, минуты. */
    val cooldownMinutes: Int = 20,
    /** Вес при выборе среди подходящих. */
    val weight: Int = 1,
    /** Какое настроение страницы уместно: пусто — любое. */
    val moods: List<String> = emptyList(),
    /** Лёгкое движение фигуры в момент показа. */
    val motion: String = "none",
)

/** Итог проверки профиля: список нарушений. Пустой список — профиль годен. */
data class ProfileIssues(val issues: List<String>) {
    val valid: Boolean get() = issues.isEmpty()
}

/**
 * Проверка профиля перед сохранением и перед отправкой на сервер.
 *
 * Те же пределы проверяет сервер: локальная проверка даёт читателю понятную
 * ошибку сразу, не дожидаясь ответа сети.
 */
fun validateProfile(profile: CompanionProfile): ProfileIssues {
    val issues = mutableListOf<String>()
    val nameLength = profile.name.trim().unicodeLength()
    if (nameLength !in 1..MAX_NAME) issues.add("name")
    if (profile.pronouns.orEmpty().unicodeLength() > MAX_PRONOUNS) issues.add("pronouns")
    if (profile.description.unicodeLength() > MAX_DESCRIPTION) issues.add("description")
    profile.mbti?.let {
        if (it.uppercase() !in MBTI_CODES) issues.add("mbti")
    }
    if (profile.locale !in LOCALES) issues.add("locale")
    if (profile.aiConsentAt < 0) issues.add("aiConsentAt")
    for (key in CompanionPersonality.KEYS) {
        val value = profile.personality.get(key)
        if (value < CompanionPersonality.MIN || value > CompanionPersonality.MAX) issues.add(key)
    }
    return ProfileIssues(issues)
}

/** Проверка набора реплик: контракт, который сервер держит перед отправкой. */
fun validatePhrasePack(pack: CompanionPhrasePack): ProfileIssues {
    val issues = mutableListOf<String>()
    if (pack.schemaVersion != 1) issues.add("schemaVersion")
    if (pack.locale !in LOCALES) issues.add("locale")
    if (pack.phrases.size != PHRASE_COUNT) issues.add("count")
    val byScenario = pack.phrases.groupingBy { it.scenario }.eachCount()
    for ((scenario, count) in SCENARIO_COUNTS) {
        if (byScenario[scenario] != count) issues.add(scenario)
    }
    val ids = pack.phrases.map { it.id }
    if (ids.size != ids.distinct().size) issues.add("duplicateIds")
    for ((scenario, count) in SCENARIO_COUNTS) {
        for (index in 1..count) {
            val expected = "$scenario.${index.toString().padStart(2, '0')}"
            if (expected !in ids) issues.add("missingId:$expected")
        }
    }
    for (phrase in pack.phrases) {
        val length = phrase.text.unicodeLength()
        if (length !in MIN_PHRASE..MAX_PHRASE) issues.add("textLength:${phrase.id}")
        if (phrase.text.any { it.isISOControl() || it == '\n' || it == '\r' }) issues.add("control:${phrase.id}")
        if (EM_DASH in phrase.text || EN_DASH in phrase.text || phrase.text.contains("http", ignoreCase = true)) issues.add("prohibited:${phrase.id}")
        if (phrase.minMinutes !in 0..90) issues.add("minMinutes:${phrase.id}")
        if (phrase.cooldownMinutes !in 0..MAX_COOLDOWN) issues.add("cooldown:${phrase.id}")
        if (phrase.weight !in 1..100) issues.add("weight:${phrase.id}")
        if (phrase.motion !in MOTIONS) issues.add("motion:${phrase.id}")
        if (phrase.moods.any { it !in MOODS || phrase.scenario != "mood_$it" }) issues.add("moods:${phrase.id}")
        if (!phrase.scenario.startsWith("mood_") && phrase.moods.isNotEmpty()) issues.add("moods:${phrase.id}")
    }
    return ProfileIssues(issues)
}

/**
 * Канонический хеш персональных полей профиля.
 *
 * Одежда и внешность в хеш не входят: переодеть компаньона можно без
 * перегенерации реплик. Порядок ключей фиксирован, поэтому одинаковый характер
 * даёт одинаковый хеш на всех платформах — это ключ идемпотентности серверной
 * генерации.
 */
fun profileHash(profile: CompanionProfile): String {
    val payload = buildJsonObject {
        put("locale", profile.locale)
        put("personality", profile.personality.toCanonicalJson())
        put("mbti", profile.mbti?.uppercase())
        put("description", profile.description.trim())
    }
    return fnv1a32(payload.toString())
}

/** FNV-1a 32 по кодовым точкам: короткий и одинаковый на всех платформах. */
fun fnv1a32(text: String): String {
    var hash = 0x811c9dc5u.toInt()
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        val codePoint = if (ch.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            val point = Character.toCodePoint(ch, text[index + 1])
            index += 2
            point
        } else {
            index += 1
            ch.code
        }
        hash = hash xor codePoint
        hash *= 0x01000193
    }
    return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

/** Пределы и словари контракта. */
const val MAX_NAME = 40
const val MAX_PRONOUNS = 80
const val MAX_DESCRIPTION = 1200
const val PHRASE_COUNT = 100
const val MIN_PHRASE = 2
const val MAX_PHRASE = 120
const val MAX_COOLDOWN = 120
const val EM_DASH = "\u2014"
const val EN_DASH = "\u2013"

/** Распределение ста реплик по сценариям. Изменение требует новой схемы. */
val SCENARIO_COUNTS: Map<String, Int> = mapOf(
    "session_start" to 10,
    "session_resume" to 8,
    "steady_reading" to 18,
    "page_completed" to 10,
    "chapter_completed" to 10,
    "long_session" to 8,
    "return_after_break" to 8,
    "difficult_page" to 8,
    "mood_joy" to 4,
    "mood_sadness" to 4,
    "mood_tension" to 4,
    "mood_mystery" to 4,
    "session_end" to 4,
)

/** Разрешённые лёгкие движения фигуры. */
val MOTIONS: Set<String> = setOf("none", "wave", "nod", "peek", "think", "speak")
val MOODS: Set<String> = setOf("joy", "sadness", "tension", "mystery")

/** Локали компаньона на MVP. */
val LOCALES: Set<String> = setOf("ru", "en")

/** Шестнадцать кодов MBTI. */
val MBTI_CODES: Set<String> = setOf(
    "INTJ", "INTP", "ENTJ", "ENTP",
    "INFJ", "INFP", "ENFJ", "ENFP",
    "ISTJ", "ISFJ", "ESTJ", "ESFJ",
    "ISTP", "ISFP", "ESTP", "ESFP",
)

/** Длина пользовательского текста в кодовых точках, одинаковая на JVM и web. */
fun String.unicodeLength(): Int = codePointCount(0, length)

/** Обрезает по кодовым точкам и никогда не оставляет половину surrogate pair. */
fun String.takeCodePoints(max: Int): String {
    if (max <= 0) return ""
    var utf16 = 0
    var points = 0
    while (utf16 < length && points < max) {
        val first = this[utf16]
        utf16 += if (first.isHighSurrogate() && utf16 + 1 < length && this[utf16 + 1].isLowSurrogate()) 2 else 1
        points += 1
    }
    return substring(0, utf16)
}
