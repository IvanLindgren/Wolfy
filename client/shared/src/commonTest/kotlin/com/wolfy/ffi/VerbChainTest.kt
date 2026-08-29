package com.wolfy.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Тап по служебному глаголу расширяется до всей группы сказуемого.
 *
 * «is» сам по себе в словаре пуст: читатель, ткнувший в него, спрашивает не про
 * глагол-связку, а про форму - и ответ на этот вопрос «is walking», а не «is».
 *
 * Обратное так же важно. Касание по смысловому глаголу остаётся касанием по
 * слову: «walking» искать в словаре осмысленно, и подменять там перевод
 * разбором значило бы отнимать у читателя ровно то, за чем он тыкал. Отличает
 * их ядро, отдавая вместе с цепочкой начало смыслового глагола.
 */
class VerbChainTest {

    // "She is walking home." — «is» на 4..6, «walking» на 7..14.
    private val parsed = ParsedText(
        chains = listOf(VerbChain(start = 4, end = 14, mainStart = 7)),
    )

    @Test
    fun касание_связки_расширяется_до_цепочки() {
        val chain = parsed.chainToExpand(4)
        assertEquals(4 until 14, chain?.range)
    }

    @Test
    fun касание_внутри_связки_тоже_расширяется() {
        // Палец попадает в середину слова чаще, чем в его первую букву.
        assertEquals(4 until 14, parsed.chainToExpand(5)?.range)
    }

    @Test
    fun касание_смыслового_глагола_остаётся_карточкой_слова() {
        assertNull(parsed.chainToExpand(7))
        assertNull(parsed.chainToExpand(11))
    }

    @Test
    fun касание_вне_цепочки_ничего_не_расширяет() {
        assertNull(parsed.chainToExpand(0))
        assertNull(parsed.chainToExpand(14))
        assertNull(parsed.chainToExpand(20))
    }

    @Test
    fun без_цепочек_расширять_нечего() {
        assertNull(ParsedText().chainToExpand(4))
    }
}
