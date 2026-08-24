package com.wolfy.ui.card

import com.wolfy.ffi.DictionarySense
import com.wolfy.ffi.GrammarChunk
import com.wolfy.ffi.Token

/**
 * Что на карточке главное, а что — подробности.
 *
 * Те же правила, что и в веб-версии: толкование выбирается по части речи,
 * которой слово оказалось в этом предложении, а не по порядку статей в
 * словаре. Словарь перечисляет значения от самого частого, но «run» во фразе
 * «he runs a shop» — глагол, и читателю нужно именно это значение.
 */

/** Толкование той части речи, которой слово оказалось во фразе. */
internal fun primarySense(
    senses: List<DictionarySense>,
    contextPos: String?,
): DictionarySense? =
    senses.firstOrNull { sense -> !contextPos.isNullOrBlank() && sense.pos == contextPos }
        ?: senses.firstOrNull()

/** Остальные значения без уже показанного главного. */
internal fun otherSenses(
    senses: List<DictionarySense>,
    main: DictionarySense?,
    limit: Int = 5,
): List<DictionarySense> {
    val mainIndex = main?.let(senses::indexOf) ?: -1
    return senses.filterIndexed { index, _ -> index != mainIndex }.take(limit)
}

/**
 * Часть речи выбранного слова по разбору предложения.
 *
 * Точнее, чем [com.wolfy.ffi.WordAnalysis.primaryPos]: разбор формы отвечает
 * за слово само по себе, а синтаксические группы — за то, как оно работает
 * именно здесь. Сначала ищем группу, где слово — вершина, затем любую
 * содержащую его.
 */
internal fun contextualPos(
    chunks: List<GrammarChunk>,
    sentenceTokens: List<Token>,
    selected: Token,
): String? {
    if (chunks.isEmpty() || sentenceTokens.isEmpty()) return null
    val position = positionOf(sentenceTokens, selected) ?: return null

    return chunks.firstOrNull { it.head == position }?.tint?.takeIf(String::isNotBlank)
        ?: chunks
            .firstOrNull { position >= it.start && position < it.end }
            ?.tint
            ?.takeIf(String::isNotBlank)
}

/**
 * Номер выбранного слова среди токенов предложения.
 *
 * Токены предложения пересобраны из контекста, и их позиции не совпадают с
 * позициями на странице; совпадение ищется сначала точное, потом по слову.
 * Второго прохода достаточно: тапнули по слову, значит текст у него есть.
 */
private fun positionOf(tokens: List<Token>, selected: Token): Int? {
    tokens.indexOfFirst { it.start == selected.start && it.end == selected.end }
        .takeIf { it >= 0 }
        ?.let { return it }

    return tokens.indexOfFirst {
        it.kind == "word" && it.text.equals(selected.text, ignoreCase = true)
    }.takeIf { it >= 0 }
}
