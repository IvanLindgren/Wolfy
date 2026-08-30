package com.wolfy.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Показание места.
 *
 * Главная проверяемая вещь здесь не форматирование, а то, что подпись и
 * линейка говорят об одном и том же. Разошлись они однажды молча — линейка
 * мерила книгу, подпись мерила главу, — и заметить это можно было только
 * посчитав руками.
 */
class ReaderPlaceTest {

    @Test
    fun `подпись и линейка одного масштаба`() {
        // Середина третьей главы из десяти: в главе пройдена половина, в книге
        // четверть. Числа разные, и в одном показании они встретиться не могут.
        val chapterShare = readingPlaceFraction(ReadingScope.Chapter, 0.5f, 2, 10)
        val bookShare = readingPlaceFraction(ReadingScope.Book, 0.5f, 2, 10)
        assertEquals(0.5f, chapterShare)
        assertEquals(0.25f, bookShare)

        assertEquals("7 мин", readingPlaceLabel(ReadingScope.Chapter, 7, 2, 10))
        assertEquals("3 из 10", readingPlaceLabel(ReadingScope.Book, 7, 2, 10))
    }

    @Test
    fun `неизвестное время не подменяется другой величиной`() {
        // Ровно та поломка, ради которой всё переписано: раньше на месте минут
        // появлялись проценты книги, и один угол экрана означал то одно, то
        // другое.
        assertNull(readingPlaceLabel(ReadingScope.Chapter, null, 2, 10))
    }

    @Test
    fun `меньше минуты это не то же самое что неизвестно`() {
        assertEquals("<1 мин", readingPlaceLabel(ReadingScope.Chapter, 0, 0, 10))
    }

    @Test
    fun `долгая глава считается часами`() {
        assertEquals("59 мин", readingPlaceLabel(ReadingScope.Chapter, 59, 0, 10))
        assertEquals("1 ч", readingPlaceLabel(ReadingScope.Chapter, 60, 0, 10))
        assertEquals("1 ч 20 мин", readingPlaceLabel(ReadingScope.Chapter, 80, 0, 10))
        assertEquals("2 ч", readingPlaceLabel(ReadingScope.Chapter, 120, 0, 10))
    }

    @Test
    fun `книга без оглавления не показывает выдуманных глав`() {
        assertNull(readingPlaceLabel(ReadingScope.Book, 7, 0, 0))
        assertEquals(0f, readingPlaceFraction(ReadingScope.Book, 0.5f, 0, 0))
    }

    @Test
    fun `касание возвращает читателя туда же`() {
        assertEquals(ReadingScope.Book, ReadingScope.Chapter.next())
        assertEquals(ReadingScope.Chapter, ReadingScope.Chapter.next().next())
    }

    @Test
    fun `полное прочтение объясняет и величину и переключение`() {
        val chapter = readingPlaceDescription(ReadingScope.Chapter, 7, 2, 10)
        assertTrue("главы" in chapter, "озвучка не называет масштаб: $chapter")
        assertTrue("Нажмите" in chapter, "озвучка не говорит про переключение: $chapter")

        val unknown = readingPlaceDescription(ReadingScope.Chapter, null, 2, 10)
        assertTrue("неизвестно" in unknown, "неизвестность обязана называться: $unknown")
    }
}
