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
    /** Палитра частей речи для грамматической подсветки. */
    val partsOfSpeech: PartOfSpeechColors,
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

/** Палитра частей речи одинакова во всех темах — меняется только фон. */
private val partsOfSpeech = PartOfSpeechColors(
    noun = Color(0xFF2C5AA0),
    verb = Color(0xFFB83A2A),
    adjective = Color(0xFF3F7A3F),
    adverb = Color(0xFFB08A3C),
    pronoun = Color(0xFF7B5EA7),
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
    partsOfSpeech = partsOfSpeech,
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
    partsOfSpeech = PartOfSpeechColors(
        // На тёмной бумаге те же цвета выглядят грязными, поэтому они
        // осветлены: тон сохранён, светлота поднята.
        noun = Color(0xFF7FA6DC),
        verb = Color(0xFFE07A64),
        adjective = Color(0xFF87BE87),
        adverb = Color(0xFFD9B871),
        pronoun = Color(0xFFB79BDB),
    ),
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
    partsOfSpeech = PartOfSpeechColors(
        noun = Color(0xFF7FA6DC),
        verb = Color(0xFFE07A64),
        adjective = Color(0xFF87BE87),
        adverb = Color(0xFFD9B871),
        pronoun = Color(0xFFB79BDB),
    ),
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
    partsOfSpeech = PartOfSpeechColors(
        noun = Color(0xFF8FB4E8),
        verb = Color(0xFFF08A72),
        adjective = Color(0xFF96CC96),
        adverb = Color(0xFFE5C47D),
        pronoun = Color(0xFFC5A9E8),
    ),
    dark = true,
)

/** Тема оформления, выбранная читателем. */
enum class ReadingTheme(val title: String, val colors: WolfyColors) {
    Paper("Бумага", PaperColors),
    Sepia("Сепия", SepiaColors),
    Dark("Тёмная", DarkColors),
    Oled("OLED", OledColors),
}
