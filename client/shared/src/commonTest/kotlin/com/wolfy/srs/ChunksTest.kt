package com.wolfy.srs

import com.wolfy.ffi.Finding
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Sentence
import com.wolfy.ffi.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Разбивка фразы на блоки.
 *
 * Разбор здесь подставной, а не настоящий: настоящий приходит из ядра на Rust,
 * которого в общих тестах нет. Проверяется не он, а вёрстка плиток — то, как
 * блоки собираются из границ, которые ядро уже нашло.
 */
class ChunksTest {

    /** Токены предложения: слова с их местом в строке. */
    private fun parse(sentence: String): ParsedText {
        val tokens = mutableListOf<Token>()
        var at = 0
        for (word in sentence.split(' ')) {
            val clean = word.trimEnd('.', ',', '!', '?')
            tokens += Token(kind = "word", start = at, end = at + clean.length, text = clean)
            at += word.length + 1
        }
        return ParsedText(
            tokens = tokens,
            sentences = listOf(
                Sentence(
                    start = 0,
                    end = sentence.length,
                    firstToken = 0,
                    lastToken = tokens.lastIndex,
                    text = sentence,
                ),
            ),
        )
    }

    private fun chain(rule: String, start: Int, end: Int) = Finding(
        rule = rule,
        title = rule,
        formula = "",
        explanation = "",
        start = start,
        end = end,
    )

    @Test
    fun сказуемое_остаётся_одним_блоком() {
        val sentence = "I have been reading this book for a month"
        val blocks = Chunks.split(
            sentence = sentence,
            parsed = parse(sentence),
            findings = listOf(chain("present-perfect-continuous", 1, 4)),
        )
        assertTrue(
            blocks.contains("have been reading"),
            "цепочка сказуемого рассыпалась: $blocks",
        )
    }

    @Test
    fun служебное_слово_тянет_за_собой_следующее() {
        val sentence = "I have been reading this book for a month"
        val blocks = Chunks.split(sentence, parse(sentence), listOf(chain("x", 1, 4)))
        assertTrue(blocks.contains("this book"), "определитель отвалился: $blocks")
        assertTrue(blocks.contains("for a month"), "предлог отвалился: $blocks")
    }

    @Test
    fun блоки_складываются_обратно_в_предложение() {
        val sentence = "She left the library at dusk"
        val blocks = Chunks.split(sentence, parse(sentence), emptyList())
        assertEquals(sentence, blocks.joinToString(" "))
    }

    @Test
    fun разбор_во_всё_предложение_не_склеивает_его_в_один_блок() {
        // Условное правило покрывает обе половины фразы, и склеивать по нему —
        // значит выдать читателю одну плитку вместо задания.
        val sentence = "If I had known I would have called you"
        val blocks = Chunks.split(
            sentence = sentence,
            parsed = parse(sentence),
            findings = listOf(chain("conditional-third", 0, 9)),
        )
        assertTrue(blocks.size > 1, "предложение стало одним блоком: $blocks")
    }

    @Test
    fun слишком_дробная_фраза_в_колоду_не_идёт() {
        assertFalse(Chunks.trainable(listOf("a")))
        assertFalse(Chunks.trainable(List(12) { "$it" }))
        assertTrue(Chunks.trainable(listOf("I", "have been reading", "this book")))
    }

    @Test
    fun сверка_не_придирается_к_знакам_и_регистру() {
        assertTrue(Chunks.same("I have been reading this book", "I have been reading this book."))
        assertTrue(Chunks.same("she left", "She left!"))
        assertTrue(Chunks.same("she  left", "She left"))
        assertFalse(Chunks.same("she leaves", "She left"))
    }
}
