package com.wolfy.srs

import com.wolfy.data.library.Card
import com.wolfy.ffi.Exercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сборка заданий.
 *
 * Главное здесь — два обещания. Первое: одно и то же задание при повторе
 * выглядит одинаково, иначе читатель запоминает расположение плиток, а не
 * слово. Второе: способ спросить растёт вместе с прочностью карточки — сперва
 * узнать, потом собрать, потом вспомнить с нуля.
 */
class DrillsTest {

    private fun word(
        id: String = "1",
        lemma: String = "library",
        hp: Int = Scheduler.FULL_HP,
        translation: String = "библиотека",
        context: String = "She left the library at dusk.",
    ) = Card(
        id = id,
        surface = lemma,
        lemma = lemma,
        translation = translation,
        context = context,
        hp = hp,
    )

    private val deck = listOf(
        word(id = "2", lemma = "dusk", translation = "сумерки"),
        word(id = "3", lemma = "shelf", translation = "полка"),
        word(id = "4", lemma = "candle", translation = "свеча"),
    )

    @Test
    fun способ_спросить_растёт_вместе_с_прочностью() {
        assertEquals(DrillKind.Choice, Drills.forWord(word(hp = 90), deck).kind)
        assertEquals(DrillKind.Letters, Drills.forWord(word(hp = 60), deck).kind)
        assertEquals(DrillKind.Typing, Drills.forWord(word(hp = 10), deck).kind)
    }

    @Test
    fun выбор_из_четырёх_невозможен_без_чужих_переводов() {
        // Придумать правдоподобно неверный перевод приложению нечем, а «дом /
        // стол / бегать» рядом с «библиотека» не проверяют ничего.
        val alone = Drills.forWord(word(hp = 100), deck = listOf(word()))
        assertEquals(DrillKind.Letters, alone.kind)
    }

    @Test
    fun задание_не_прыгает_между_показами() {
        val first = Drills.forWord(word(hp = 60), deck)
        val second = Drills.forWord(word(hp = 60), deck)
        assertEquals(first.pieces, second.pieces)
        assertEquals(first.given, second.given)
    }

    @Test
    fun открытых_букв_меньше_чем_слово() {
        val drill = Drills.forWord(word(hp = 60), deck)
        assertTrue(drill.given.isNotEmpty(), "не открыто ни одной буквы")
        assertTrue(drill.given.size < drill.answer.length, "открыто всё слово")
        // Скрытые буквы обязаны найтись в банке — иначе слово не собрать.
        val hidden = drill.answer.indices.filter { it !in drill.given }.map { drill.answer[it] }
        val pool = drill.pieces.toMutableList()
        hidden.forEach { letter ->
            assertTrue(pool.remove(letter.toString()), "буквы «$letter» нет в банке")
        }
    }

    @Test
    fun предложение_из_книги_не_выдаёт_ответ() {
        val drill = Drills.forWord(word(hp = 60), deck)
        assertTrue(
            !drill.subject.contains("library", ignoreCase = true),
            "слово осталось в предложении: ${drill.subject}",
        )
        assertTrue(drill.subject.contains("…"), "пропуска в предложении нет")
    }

    @Test
    fun грамматическое_задание_берётся_из_ядра_целиком() {
        val exercise = Exercise(
            rule = "present-perfect",
            topic = "tenses",
            task = "form",
            sentence = "She ___ the book.",
            translation = "Она прочитала книгу.",
            question = "Present Perfect",
            options = listOf("reads", "has read", "is reading", "had read"),
            answer = 1,
            formula = "have/has + V3",
            explanation = "Действие уже случилось, важен результат",
        )
        val drill = Drills.forRule(exercise, cardId = "rule-1")

        assertEquals(DrillKind.Gap, drill.kind)
        assertEquals("has read", drill.answer)
        assertEquals(exercise.options, drill.pieces)
        assertEquals(exercise.explanation, drill.explanation)
    }

    private fun phrase(hp: Int) = Card(
        id = "p1",
        kind = "phrase",
        surface = "I have been reading this book for a month.",
        lemma = "I have been reading this book for a month.",
        translation = "Я читаю эту книгу уже месяц.",
        context = "I have been reading this book for a month.",
        hp = hp,
    )

    @Test
    fun крепкую_фразу_спрашивают_пропуском_а_слабую_конструктором() {
        val blocks = listOf("I", "have been reading", "this book", "for a month")

        val gap = Drills.forPhrase(phrase(hp = 100), blocks)
        assertEquals(DrillKind.Gap, gap.kind)
        assertTrue(gap.subject.contains("___"), "пропуска нет: ${gap.subject}")
        assertTrue(gap.pieces.contains(gap.answer), "верного варианта нет среди четырёх")
        assertEquals(gap.pieces.size, gap.pieces.distinct().size, "повтор в вариантах")

        assertEquals(DrillKind.Builder, Drills.forPhrase(phrase(hp = 40), blocks).kind)
    }

    @Test
    fun варианты_пропуска_из_одной_смысловой_группы() {
        // Выбор между «for» и «the» не спрашивает ни о чём: неверный вариант
        // виден, не читая фразы.
        val gap = Drills.forPhrase(phrase(hp = 100), emptyList())
        val prepositions = setOf("for", "since", "during", "until")
        val articles = setOf("a", "an", "the")
        assertTrue(
            gap.pieces.all { it in prepositions } || gap.pieces.all { it in articles },
            "варианты из разных групп: ${gap.pieces}",
        )
    }

    @Test
    fun пропуск_во_фразе_не_прыгает() {
        val first = Drills.forPhrase(phrase(hp = 100), emptyList())
        val second = Drills.forPhrase(phrase(hp = 100), emptyList())
        assertEquals(first.subject, second.subject)
        assertEquals(first.pieces, second.pieces)
    }

    @Test
    fun фраза_без_служебных_слов_остаётся_конструктором() {
        val card = phrase(hp = 100).copy(
            surface = "She smiled quietly",
            lemma = "She smiled quietly",
        )
        assertEquals(DrillKind.Builder, Drills.forPhrase(card, listOf("She", "smiled", "quietly")).kind)
    }

    @Test
    fun перемешивание_ничего_не_теряет() {
        val items = (1..10).toList()
        val mixed = Drills.shuffled(items, seed = 42)
        assertEquals(items.toSet(), mixed.toSet())
        assertEquals(mixed, Drills.shuffled(items, seed = 42))
    }
}
