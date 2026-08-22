package com.wolfy.srs

import com.wolfy.data.library.Card
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Расписание повторений.
 *
 * Проверяется не «работает ли код», а обещания, которые расписание даёт
 * читателю: четыре верных ответа выучивают слово, ошибка возвращает его в ту
 * же тренировку, интенсивность меняет сроки и не меняет требований, а
 * напоминание приходит в человеческое время.
 */
class SchedulerTest {

    private val start = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun card(id: String = "1", hp: Int = Scheduler.FULL_HP) =
        Card(id = id, surface = "book", lemma = "book", hp = hp, dueAt = start, addedAt = start)

    @Test
    fun четыре_верных_ответа_выучивают_слово() {
        var card = card()
        var moment = start
        repeat(4) {
            card = Scheduler.review(card, right = true, intensity = Intensity.Normal, now = moment)
            moment = card.dueAt
        }
        assertEquals(0, card.hp, "прочность после четырёх верных: ${card.hp}")
        assertEquals(4, card.streak)
    }

    @Test
    fun трёх_ответов_не_хватает() {
        var card = card()
        var moment = start
        repeat(3) {
            card = Scheduler.review(card, right = true, intensity = Intensity.Normal, now = moment)
            moment = card.dueAt
        }
        assertTrue(card.hp > 0, "слово выучилось за три ответа: ${card.hp}")
    }

    @Test
    fun ошибка_возвращает_карточку_в_ту_же_тренировку() {
        val answered = Scheduler.review(card(), right = true, intensity = Intensity.Normal, now = start)
        val missed = Scheduler.review(answered, right = false, intensity = Intensity.Normal, now = start)

        assertEquals(0, missed.streak, "серия не сброшена")
        assertTrue(missed.hp > answered.hp, "ошибка не вернула прочность")
        assertTrue(
            missed.dueAt - start <= 15 * minute,
            "карточка вернётся только через ${(missed.dueAt - start) / minute} минут",
        )
    }

    @Test
    fun интенсивность_меняет_сроки_но_не_требования() {
        val gentle = Scheduler.review(card(), right = true, intensity = Intensity.Gentle, now = start)
        val extreme = Scheduler.review(card(), right = true, intensity = Intensity.Extreme, now = start)

        assertTrue(
            gentle.dueAt > extreme.dueAt,
            "лёгкий режим спрашивает не позже экстрима",
        )
        // Главное: прочность снимается одинаково. Иначе «лёгкий» значил бы
        // «выучил хуже», и сравнить свои двести слов с чужими двумястами стало
        // бы невозможно.
        assertEquals(gentle.hp, extreme.hp)
    }

    @Test
    fun сроки_растут_от_ответа_к_ответу() {
        var card = card()
        var moment = start
        val steps = mutableListOf<Long>()
        repeat(5) {
            card = Scheduler.review(card, right = true, intensity = Intensity.Normal, now = moment)
            steps += card.dueAt - moment
            moment = card.dueAt
        }
        assertEquals(steps.sorted(), steps, "лесенка не растёт: $steps")
    }

    @Test
    fun вероятность_вспомнить_к_сроку_около_девяноста_процентов() {
        val reviewed = Scheduler.review(card(), right = true, intensity = Intensity.Normal, now = start)
        val atDue = Scheduler.retention(reviewed, reviewed.dueAt)

        assertTrue(
            atDue in 0.87f..0.93f,
            "к назначенному сроку помнится $atDue вместо ${Scheduler.TARGET_RECALL}",
        )
        assertTrue(
            Scheduler.retention(reviewed, reviewed.dueAt + 30 * day) < atDue,
            "через месяц помнится не хуже, чем в срок",
        )
    }

    @Test
    fun непросмотренная_карточка_не_считается_забытой() {
        assertEquals(0f, Scheduler.retention(card().copy(dueAt = 0), start))
        assertNull(Scheduler.halfForgottenAt(card().copy(dueAt = 0)))
    }

    @Test
    fun напоминание_молчит_когда_повторять_нечего() {
        assertNull(Scheduler.reminderAt(emptyList(), Intensity.Normal, start))
    }

    @Test
    fun напоминание_приходит_в_приличное_время() {
        val cards = (1..20).map {
            Scheduler.review(card(id = "$it"), right = true, intensity = Intensity.Normal, now = start)
        }
        val at = Scheduler.reminderAt(cards, Intensity.Normal, start)

        assertNotNull(at)
        val hour = com.wolfy.data.localHour(at)
        assertTrue(hour in 9..21, "напоминание назначено на $hour часов")
        assertTrue(at >= start, "напоминание назначено в прошлое")
    }

    @Test
    fun маленькая_колода_тоже_напомнит_о_себе() {
        // С тремя карточками ждать восьмой означало бы не напомнить никогда.
        val cards = (1..3).map {
            Scheduler.review(card(id = "$it"), right = true, intensity = Intensity.Normal, now = start)
        }
        assertNotNull(Scheduler.reminderAt(cards, Intensity.Normal, start))
    }

    @Test
    fun поправка_не_считается_по_десятку_ответов() {
        assertEquals(1f, Scheduler.ease(answers = 10, right = 10))
        assertTrue(Scheduler.ease(answers = 100, right = 100) > 1f, "отличник повторяет лишнее")
        assertTrue(Scheduler.ease(answers = 100, right = 50) < 1f, "отстающий не успевает закрепить")
    }

    @Test
    fun выученные_и_созревшие_считаются_по_разному() {
        val learned = card(id = "a", hp = 0).copy(dueAt = start + 100 * day)
        val ripe = card(id = "b").copy(dueAt = start - day)
        val later = card(id = "c").copy(dueAt = start + day)

        assertEquals(listOf("a"), Scheduler.learned(listOf(learned, ripe, later)).map { it.id })
        assertEquals(listOf("b"), Scheduler.due(listOf(learned, ripe, later), start).map { it.id })
    }
}
