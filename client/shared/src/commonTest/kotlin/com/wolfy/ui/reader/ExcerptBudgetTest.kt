package com.wolfy.ui.reader

import com.wolfy.data.companion.unicodeLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Бюджет фрагмента для пересказа и вопроса о книге.
 *
 * Сервер отклоняет фрагмент длиннее 18 000 рун и короче 200. Раньше клиент
 * набирал куски ровно на свой потолок, а разделители дописывались сверху — и
 * фрагмент перескакивал границу на считаные символы. Внутри одной главы это не
 * проявлялось, а стоило прочитанному охватить вторую, пересказ переставал
 * работать навсегда: ровно то «работает через раз», с которого начался разбор.
 */
class ExcerptBudgetTest {

    private val serverLimit = 18_000

    @Test
    fun `склейка нескольких глав не выходит за бюджет`() {
        val chapter = "а".repeat(9_000)
        val excerpt = assembleExcerpt(fromChapter = 5, budget = ReaderViewModel.RECAP_CHARS) { chapter }

        assertTrue(
            excerpt.unicodeLength() <= ReaderViewModel.RECAP_CHARS,
            "фрагмент длиннее бюджета: ${excerpt.unicodeLength()}",
        )
        assertTrue(
            excerpt.unicodeLength() <= serverLimit,
            "фрагмент не пройдёт серверную проверку: ${excerpt.unicodeLength()}",
        )
        assertTrue(excerpt.contains(EXCERPT_SEPARATOR), "главы склеены без разделителя")
    }

    @Test
    fun `бюджет соблюдается при любом числе стыков`() {
        // Много коротких глав — стыков больше, и именно они раньше выносили
        // фрагмент за предел.
        for (chapters in 1..40) {
            val excerpt = assembleExcerpt(fromChapter = chapters - 1, budget = 500) { "б".repeat(30) }
            assertTrue(
                excerpt.unicodeLength() <= 500,
                "$chapters глав дали ${excerpt.unicodeLength()} знаков при бюджете 500",
            )
        }
    }

    @Test
    fun `порядок глав хронологический`() {
        val excerpt = assembleExcerpt(fromChapter = 2, budget = 1_000) { index -> "глава$index" }
        assertEquals("глава0${EXCERPT_SEPARATOR}глава1${EXCERPT_SEPARATOR}глава2", excerpt)
    }

    @Test
    fun `пустые главы не создают пустых стыков`() {
        val excerpt = assembleExcerpt(fromChapter = 3, budget = 1_000) { index ->
            if (index % 2 == 0) "текст$index" else "   "
        }
        assertEquals("текст0${EXCERPT_SEPARATOR}текст2", excerpt)
    }

    @Test
    fun `суррогатная пара не рвётся на границе бюджета`() {
        // Обрезка по UTF-16 оставила бы половину пары и испортила бы кодировку.
        val text = "😀".repeat(50)
        val excerpt = assembleExcerpt(fromChapter = 0, budget = 10) { text }

        assertEquals(10, excerpt.unicodeLength())
        assertTrue(excerpt.none { it.isHighSurrogate() && excerpt.indexOf(it) == excerpt.lastIndex })
        assertEquals("😀".repeat(10), excerpt)
    }
}
