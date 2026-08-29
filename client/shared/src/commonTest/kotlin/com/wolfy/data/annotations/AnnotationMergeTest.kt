package com.wolfy.data.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Слияние отметок двух устройств.
 *
 * Часы телефона и ноутбука расходятся, поэтому «побеждает более поздний по
 * времени» означало бы, что победитель зависит от того, у кого спешат часы.
 * Порядок задаёт пара (rev, writer): номер правки в счётчике Лампорта писателя
 * и его стабильный номер.
 *
 * Главное требование - независимость от порядка аргументов. Клиент сливает
 * своё с серверным, сервер серверное с клиентским, и разойтись они не имеют
 * права: разойдясь один раз, они будут переписывать друг друга вечно.
 */
class AnnotationMergeTest {

    private fun item(id: String, rev: Long, writer: String, note: String = "") = Annotation(
        id = id, chapter = 0, start = 0, end = 2, tone = 1, note = note, rev = rev, writer = writer,
    )

    @Test
    fun побеждает_большая_правка() {
        val old = item("x", rev = 1, writer = "phone", note = "старое")
        val new = item("x", rev = 2, writer = "phone", note = "новое")
        assertEquals("новое", merge(listOf(old), listOf(new)).single().note)
        assertEquals("новое", merge(listOf(new), listOf(old)).single().note)
    }

    @Test
    fun при_равной_правке_решает_номер_устройства() {
        // Два устройства правили офлайн и выдали один номер. Ответ обязан быть
        // одинаковым на обоих, иначе они будут переписывать друг друга вечно.
        val phone = item("x", rev = 3, writer = "aaa", note = "с телефона")
        val laptop = item("x", rev = 3, writer = "bbb", note = "с ноутбука")
        assertEquals("с ноутбука", merge(listOf(phone), listOf(laptop)).single().note)
        assertEquals("с ноутбука", merge(listOf(laptop), listOf(phone)).single().note)
    }

    @Test
    fun порядок_аргументов_ничего_не_меняет() {
        val first = listOf(item("a", 1, "p"), item("b", 4, "p"))
        val second = listOf(item("b", 2, "q"), item("c", 1, "q"))
        assertEquals(merge(first, second), merge(second, first))
    }

    @Test
    fun пометка_удаления_доезжает_и_не_воскресает() {
        // Удаление это запись, а не отсутствие записи: у второго устройства
        // отметка ещё жива, и без пометки оно вернуло бы её из своего файла.
        val alive = item("x", rev = 1, writer = "phone")
        val buried = alive.copy(deleted = true, tone = null, note = "", rev = 2)
        val merged = merge(listOf(alive), listOf(buried)).single()
        assertTrue(merged.deleted)
        assertEquals(null, merged.tone)
    }

    @Test
    fun отметки_идут_по_месту_в_книге() {
        // Порядок нужен для списка заметок: читатель ждёт их в порядке книги,
        // а не в порядке, в котором их занесло с двух устройств.
        val merged = merge(
            listOf(item("b", 1, "p").copy(chapter = 2, start = 5)),
            listOf(item("a", 1, "p").copy(chapter = 1, start = 9)),
        )
        assertEquals(listOf(1, 2), merged.map { it.chapter })
    }
}
