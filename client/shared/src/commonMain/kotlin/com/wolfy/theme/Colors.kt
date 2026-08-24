package com.wolfy.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Палитра одной темы чтения.
 *
 * Тем четыре, и они не «светлая и тёмная»: читают в разном свете и в разное
 * время суток, а глазам важен не столько яркий контраст, сколько правильный
 * тон бумаги. Отсюда сепия отдельно от тёмной и OLED отдельно от обеих.
 *
 * Своя палитра, а не `ColorScheme` из Material: у Material свои роли
 * (primary, surfaceVariant, onTertiaryContainer), и натягивать на них
 * газетный набор — значит всё время переводить одно в другое в голове.
 * Здесь роли названы так, как о них думают: бумага, чернила, акцент.
 */
@Immutable
data class WolfyColors(
    /** Фон страницы — цвет бумаги. */
    val paper: Color,
    /** Основной текст — цвет чернил. */
    val ink: Color,
    /** Второстепенный текст: подписи, счётчики, микро-лейблы. */
    val inkMuted: Color,
    /** Тонкие разделительные линии газетной вёрстки. */
    val rule: Color,
    /** Подложка карточек и панелей — чуть плотнее бумаги. */
    val surface: Color,
    /** Сигнальный красный: действия, теги, прогресс. */
    val accent: Color,
    /** Золото: награды и серии занятий. */
    val gold: Color,
    /** Маркер сохранённого слова в тексте. */
    val highlight: Color,
    /** Текст поверх акцентной заливки. */
    val onAccent: Color,
    /**
     * Выворотная плашка: чёрная полоса баннера, главная кнопка, активная
     * таблетка переключателя.
     *
     * Отдельный цвет, а не `ink`. Чернила в тёмных темах светлые — это верно
     * для текста и неверно для плашки: баннер серии, задуманный как
     * единственное тёмное пятно на полосе, в тёмной теме превращался в
     * светлое, то есть ровно в свою противоположность. Плашка обязана
     * оставаться плашкой во всех четырёх темах.
     */
    val inverse: Color,
    /** Текст на выворотной плашке. */
    val onInverse: Color,
    /** Палитра частей речи для грамматической подсветки. */
    val partsOfSpeech: PartOfSpeechColors,
    /** Цветовые семейства правил: одно семейство всегда узнаётся одинаково. */
    val ruleFamilies: RuleFamilyColors,
    /** Тёмная ли тема — нужно для системных панелей и статус-бара. */
    val dark: Boolean,
)

/**
 * Цвета частей речи.
 *
 * Пять цветов, а не десять. Раскрасить все части речи технически можно, но
 * страница превратится в светофор и читать её станет невозможно. Цвет получают
 * знаменательные части речи и местоимения — то, что помогает разобрать
 * структуру фразы; предлоги, артикли и союзы остаются цветом чернил.
 */
@Immutable
data class PartOfSpeechColors(
    val noun: Color,
    val verb: Color,
    val adjective: Color,
    val adverb: Color,
    val pronoun: Color,
) {
    /**
     * Цвет для части речи из ядра или `null`, если её не подсвечиваем.
     *
     * Имена приходят из ядра в universal tagset — том же, которым размечен
     * лексикон.
     */
    fun forTag(tag: String): Color? = when (tag) {
        "NOUN" -> noun
        "VERB" -> verb
        "ADJ" -> adjective
        "ADV" -> adverb
        "PRON" -> pronoun
        else -> null
    }
}

@Immutable
data class RuleFamilyColors(
    val tense: Color,
    val voice: Color,
    val mood: Color,
    val condition: Color,
    val comparison: Color,
    val reference: Color,
) {
    fun forFamily(rule: String): Color = when {
        "passive" in rule || "voice" in rule -> voice
        "conditional" in rule || rule.startsWith("if-") -> condition
        "compar" in rule || "superlative" in rule -> comparison
        "relative" in rule || "reported" in rule || "reference" in rule -> reference
        "subjunctive" in rule || "wish" in rule || "modal" in rule -> mood
        else -> tense
    }
}

/** Палитра частей речи одинакова во всех темах — меняется только фон. */
private val partsOfSpeech = PartOfSpeechColors(
    noun = Color(0xFF2C5AA0),
    verb = Color(0xFFB83A2A),
    adjective = Color(0xFF3F7A3F),
    adverb = Color(0xFFB08A3C),
    pronoun = Color(0xFF7B5EA7),
)

private val lightRuleFamilies = RuleFamilyColors(
    tense = Color(0xFFFFD9D2),
    voice = Color(0xFFDCE8FA),
    mood = Color(0xFFE8DDF6),
    condition = Color(0xFFFFE4B8),
    comparison = Color(0xFFD9EBD8),
    reference = Color(0xFFE5E2D9),
)

private val darkRuleFamilies = RuleFamilyColors(
    tense = Color(0xFF63382F),
    voice = Color(0xFF304A6C),
    mood = Color(0xFF4B3B63),
    condition = Color(0xFF65502C),
    comparison = Color(0xFF36563A),
    reference = Color(0xFF46443E),
)

/** Классическая газета: белизна бумаги и густые чернила. */
val PaperColors = WolfyColors(
    paper = Color(0xFFF4F4F1),
    ink = Color(0xFF111111),
    inkMuted = Color(0xFF6B6B66),
    rule = Color(0xFFD9D8D2),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFFB83A2A),
    gold = Color(0xFFB08A3C),
    highlight = Color(0xFFF7E27A),
    onAccent = Color(0xFFFFFFFF),
    inverse = Color(0xFF111111),
    onInverse = Color(0xFFF7F7F4),
    partsOfSpeech = partsOfSpeech,
    ruleFamilies = lightRuleFamilies,
    dark = false,
)

/** Сепия: мягкий вечерний свет, тёплая бумага. */
val SepiaColors = WolfyColors(
    paper = Color(0xFF3A2E22),
    ink = Color(0xFFE8D9BE),
    inkMuted = Color(0xFFA6947A),
    rule = Color(0xFF574636),
    surface = Color(0xFF463628),
    accent = Color(0xFFD9694F),
    gold = Color(0xFFD4A855),
    highlight = Color(0x66D4A855),
    onAccent = Color(0xFF2A2018),
    inverse = Color(0xFF241B12),
    onInverse = Color(0xFFEFE2CB),
    partsOfSpeech = PartOfSpeechColors(
        // На тёмной бумаге те же цвета выглядят грязными, поэтому они
        // осветлены: тон сохранён, светлота поднята.
        noun = Color(0xFF7FA6DC),
        verb = Color(0xFFE07A64),
        adjective = Color(0xFF87BE87),
        adverb = Color(0xFFD9B871),
        pronoun = Color(0xFFB79BDB),
    ),
    ruleFamilies = darkRuleFamilies,
    dark = true,
)

/** Тёмная: глубокие чернила без тепла. */
val DarkColors = WolfyColors(
    paper = Color(0xFF17140F),
    ink = Color(0xFFD8D2C4),
    inkMuted = Color(0xFF8B8578),
    rule = Color(0xFF33302A),
    surface = Color(0xFF221E18),
    accent = Color(0xFFD9694F),
    gold = Color(0xFFD4A855),
    highlight = Color(0x66D4A855),
    onAccent = Color(0xFF17140F),
    inverse = Color(0xFF070605),
    onInverse = Color(0xFFE6E1D5),
    partsOfSpeech = PartOfSpeechColors(
        noun = Color(0xFF7FA6DC),
        verb = Color(0xFFE07A64),
        adjective = Color(0xFF87BE87),
        adverb = Color(0xFFD9B871),
        pronoun = Color(0xFFB79BDB),
    ),
    ruleFamilies = darkRuleFamilies,
    dark = true,
)

/**
 * OLED: чёрный без остатка.
 *
 * Смысл темы в том, что на OLED-экране чёрный пиксель не светится вовсе, и
 * ночью это заметно глазам, а днём — батарее. Поэтому фон именно `#000000`,
 * а не «почти чёрный»: любое отличие от нуля включает подсветку.
 */
val OledColors = WolfyColors(
    paper = Color(0xFF000000),
    ink = Color(0xFFFFFFFF),
    inkMuted = Color(0xFF8A8A8A),
    rule = Color(0xFF2A2A2A),
    surface = Color(0xFF0E0E0E),
    accent = Color(0xFFE0674C),
    gold = Color(0xFFD4A855),
    highlight = Color(0x66D4A855),
    onAccent = Color(0xFF000000),
    // Не чистый чёрный: на OLED фон и есть чистый чёрный, и плашка на нём
    // просто исчезла бы. Кромка светлее фона — единственное, чем она здесь
    // может себя обозначить.
    inverse = Color(0xFF1A1A1A),
    onInverse = Color(0xFFFFFFFF),
    partsOfSpeech = PartOfSpeechColors(
        noun = Color(0xFF8FB4E8),
        verb = Color(0xFFF08A72),
        adjective = Color(0xFF96CC96),
        adverb = Color(0xFFE5C47D),
        pronoun = Color(0xFFC5A9E8),
    ),
    ruleFamilies = darkRuleFamilies,
    dark = true,
)

/** Тема оформления, выбранная читателем. */
enum class ReadingTheme(val title: String, val colors: WolfyColors) {
    Paper("Бумага", PaperColors),
    Sepia("Сепия", SepiaColors),
    Dark("Тёмная", DarkColors),
    Oled("OLED", OledColors),
}
