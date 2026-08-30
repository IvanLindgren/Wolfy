package com.wolfy.ui.reader

import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Слово в подсказке про касание.
 *
 * Проверяется не «нашлось хоть что-то», а именно отбор: подсказка называет
 * читателю конкретное слово на странице, и неудачный выбор здесь хуже, чем его
 * отсутствие. «Коснитесь слова, например Dorian» обещает перевод имени, а
 * «например was» — обещает бесполезность.
 */
class FirstWordTest {

    private fun абзац(text: String): ReaderBlock {
        val tokens = mutableListOf<Token>()
        var index = 0
        for (piece in text.split(' ')) {
            val clean = piece.trim { !it.isLetter() && it != '-' && it != '\'' }
            val kind = if (clean.isNotEmpty() && clean.any { it.isLetter() }) "word" else "punctuation"
            tokens += Token(kind = kind, start = index, end = index + clean.length, text = clean)
            index += piece.length + 1
        }
        return ReaderBlock(
            kind = "paragraph",
            text = text,
            level = null,
            parsed = ParsedText(tokens = tokens),
            imagePath = null,
            alt = null,
        )
    }

    @Test
    fun `берётся первое длинное строчное слово`() {
        val word = invitingWord(listOf(абзац("The studio was filled with the rich odour of roses")))
        // Не «filled»: «studio» стоит раньше и правилам отвечает так же.
        assertEquals("studio", word, "выбрано не первое подходящее слово")
    }

    @Test
    fun `имена собственные и начало предложения не предлагаются`() {
        // «Dorian» переводить некуда, а «Innumerable» с прописной от имени по
        // виду не отличается.
        val word = invitingWord(listOf(абзац("Innumerable Dorian Wotton laburnum")))
        assertEquals("laburnum", word)
    }

    @Test
    fun `короткие и слишком длинные слова пропускаются`() {
        val word = invitingWord(listOf(абзац("was the pinkfloweringthorn quietly")))
        assertEquals("quietly", word, "порог длины не сработал")
    }

    @Test
    fun `слова с дефисом и апострофом не годятся в первый пример`() {
        val word = invitingWord(listOf(абзац("honey-coloured shouldering")))
        assertEquals("shouldering", word)
    }

    @Test
    fun `без подходящего слова пример не выдумывается`() {
        assertNull(invitingWord(listOf(абзац("It was a hot day"))))
        assertNull(invitingWord(emptyList()))
    }

    @Test
    fun `заголовки и подписи в поиск не идут`() {
        val heading = ReaderBlock(
            kind = "heading",
            text = "Chapter the seventeenth",
            level = 1,
            parsed = ParsedText(tokens = listOf(Token("word", 0, 9, "seventeenth"))),
            imagePath = null,
            alt = null,
        )
        assertEquals("laburnum", invitingWord(listOf(heading, абзац("The laburnum"))))
    }
}
