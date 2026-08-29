package com.wolfy.ui.reader

import com.wolfy.ffi.Block
import com.wolfy.ffi.CompactSentence
import com.wolfy.ffi.CompactToken
import com.wolfy.ffi.PreparedChapter
import com.wolfy.ffi.VerbChain
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals

class PreparedChapterTest {
    @Test
    fun блоки_строятся_линейно_и_сохраняют_utf16_смещения() {
        // 😀 занимает две UTF-16 code units. Именно такие смещения присылает
        // JNI/JNA, поэтому тест ловит незаметное смешение индексов символов и
        // индексов Kotlin String.
        val chapter = PreparedChapter(
            blocks = listOf(
                Block(kind = "paragraph", text = "A😀 B"),
                Block(kind = "paragraph", text = "C"),
            ),
            tokens = listOf(
                CompactToken(kind = "word", start = 0, end = 1),
                CompactToken(kind = "word", start = 1, end = 3),
                CompactToken(kind = "word", start = 4, end = 5),
                CompactToken(kind = "word", start = 7, end = 8),
            ),
            sentences = listOf(
                CompactSentence(start = 0, end = 5, firstToken = 0, lastToken = 3),
                CompactSentence(start = 7, end = 8, firstToken = 3, lastToken = 4),
            ),
        )

        val blocks = chapter.toReaderBlocks()

        assertEquals(listOf("A", "😀", "B"), blocks[0].parsed?.tokens?.map { it.text })
        assertEquals(listOf("C"), blocks[1].parsed?.tokens?.map { it.text })
        assertEquals(3, blocks[1].firstToken)
        assertEquals(0, blocks[1].parsed?.sentences?.single()?.firstToken)
        assertEquals(1, blocks[1].parsed?.sentences?.single()?.lastToken)
    }

    @Test
    fun таблица_не_исчезает_из_ffi_и_подготовленного_текста() {
        val chapter = PreparedChapter(
            blocks = listOf(
                Block(kind = "table", rows = listOf(listOf("Year", "1984"), listOf("Place", "London"))),
            ),
            tokens = listOf(
                CompactToken(kind = "word", start = 0, end = 4),
                CompactToken(kind = "number", start = 5, end = 9),
                CompactToken(kind = "word", start = 10, end = 15),
                CompactToken(kind = "word", start = 16, end = 22),
            ),
        )

        val block = chapter.toReaderBlocks().single()

        assertEquals("Year 1984 Place London", chapter.plainText())
        assertEquals(listOf(listOf("Year", "1984"), listOf("Place", "London")), block.rows)
        assertEquals(listOf("Year", "1984", "Place", "London"), block.parsed?.tokens?.map { it.text })
    }

    /**
     * Цепочки сказуемого доезжают до абзаца и в его собственных смещениях.
     *
     * Проверка именно проводки, а не поиска. Функция поиска сама по себе была
     * верной, а список у абзаца оставался пустым, потому что при нарезке главы
     * цепочки просто забыли перенести. Тап по служебному глаголу молча вёл
     * себя как раньше, и ни один тест этого не показывал: искать было не в чем.
     */
    @Test
    fun цепочки_сказуемого_переносятся_в_абзац() {
        // Два абзаца: "She is walking." и "He ran."
        val chapter = PreparedChapter(
            blocks = listOf(
                Block(kind = "paragraph", text = "She is walking."),
                Block(kind = "paragraph", text = "He ran."),
            ),
            tokens = listOf(
                CompactToken(kind = "word", start = 0, end = 3),
                CompactToken(kind = "word", start = 4, end = 6),
                CompactToken(kind = "word", start = 7, end = 14),
                CompactToken(kind = "word", start = 17, end = 19),
                CompactToken(kind = "word", start = 20, end = 23),
            ),
            sentences = listOf(
                CompactSentence(start = 0, end = 15, firstToken = 0, lastToken = 3),
                CompactSentence(start = 17, end = 24, firstToken = 3, lastToken = 5),
            ),
            // "is walking" в смещениях главы: 4..14, смысловой глагол с 7.
            chains = listOf(VerbChain(start = 4, end = 14, mainStart = 7)),
        )

        val blocks = chapter.toReaderBlocks()
        val first = blocks[0].parsed
        assertNotNull(first)
        assertEquals(1, first.chains.size)
        // Второй абзац начинается с 17, поэтому смещения первого не сдвинуты.
        assertEquals(4, first.chains[0].start)
        assertEquals(14, first.chains[0].end)

        // И главное: тап по "is" расширяется, а по "walking" - нет.
        assertNotNull(first.chainToExpand(4))
        assertNull(first.chainToExpand(7))

        // Чужая цепочка во второй абзац не протекла.
        assertEquals(0, blocks[1].parsed?.chains?.size)
    }
}
