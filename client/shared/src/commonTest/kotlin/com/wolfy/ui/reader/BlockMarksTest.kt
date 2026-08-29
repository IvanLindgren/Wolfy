package com.wolfy.ui.reader

import com.wolfy.data.annotations.Annotation
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Перевод координат отметки: номера токенов главы против символов абзаца.
 *
 * Отметка хранится в токенах, а рисуется по символам, и между этими двумя
 * системами каждый раз пересчёт. Ошибка на единицу здесь выглядит как «краска
 * чуть-чуть не туда» - её не видно ни в логах, ни на скриншоте, а починить
 * потом нельзя: неверные границы уже уехали на второе устройство.
 */
class BlockMarksTest {

    // "Alice was tired." — три слова и точка, блок начинается с 10-го токена
    // главы: перед ним лежал другой абзац.
    private fun block(first: Int = 10): ReaderBlock {
        val text = "Alice was tired."
        val tokens = listOf(
            Token("word", 0, 5, "Alice"),
            Token("space", 5, 6, " "),
            Token("word", 6, 9, "was"),
            Token("space", 9, 10, " "),
            Token("word", 10, 15, "tired"),
            Token("punctuation", 15, 16, "."),
        )
        return ReaderBlock(
            kind = "paragraph",
            text = text,
            level = null,
            parsed = ParsedText(tokens = tokens),
            imagePath = null,
            alt = null,
            firstToken = first,
        )
    }

    private fun mark(start: Int, end: Int, tone: Int? = 1) = Annotation(
        id = "a", chapter = 0, start = start, end = end, tone = tone,
    )

    @Test
    fun краска_ложится_на_свои_символы() {
        // Токены главы 10..12 — это "Alice was" в символах 0..8.
        val marks = marksFor(block(), listOf(mark(10, 13)))
        assertEquals(1, marks.size)
        assertEquals(0 until 9, marks[0].range)
    }

    @Test
    fun отметка_соседнего_абзаца_не_красит_этот() {
        assertTrue(marksFor(block(first = 10), listOf(mark(0, 5))).isEmpty())
        assertTrue(marksFor(block(first = 10), listOf(mark(30, 40))).isEmpty())
    }

    @Test
    fun отметка_через_несколько_абзацев_обрезается_по_границам() {
        // Выделили с середины прошлого абзаца до середины этого: нашему
        // достаётся его часть, а не ничего.
        val marks = marksFor(block(first = 10), listOf(mark(4, 13)))
        assertEquals(1, marks.size)
        assertEquals(0 until 9, marks[0].range)

        // И симметрично: началось здесь, кончилось в следующем.
        val tail = marksFor(block(first = 10), listOf(mark(14, 40)))
        assertEquals(1, tail.size)
        assertEquals(10 until 16, tail[0].range)
    }

    @Test
    fun отметка_без_краски_ничего_не_рисует() {
        // Заметка без выделения существует: читатель написал мысль, но красить
        // не стал. Рисовать по ней нечего.
        assertTrue(marksFor(block(), listOf(mark(10, 13, tone = null))).isEmpty())
    }

    @Test
    fun обратный_перевод_возвращает_те_же_токены() {
        val span = chapterTokensOf(block(), 0 until 9)
        assertEquals(10 until 13, span)
    }

    @Test
    fun выделение_внутри_пробела_отметкой_не_становится() {
        // Символы 5..6 — пробел между словами. Отметка на пустоте это промах,
        // а не запись, и заводить её значит копить мусор в файле книги.
        assertNull(chapterTokensOf(block(), 5 until 6))
    }

    @Test
    fun блок_без_разбора_координат_не_имеет() {
        val plain = block().copy(parsed = null)
        assertTrue(marksFor(plain, listOf(mark(10, 13))).isEmpty())
        assertNull(chapterTokensOf(plain, 0 until 5))
    }
}
