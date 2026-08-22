package com.wolfy.srs

import com.wolfy.data.Settings
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryStore
import com.wolfy.ffi.Article
import com.wolfy.ffi.Chapter
import com.wolfy.ffi.Exercise
import com.wolfy.ffi.Finding
import com.wolfy.ffi.OpenBook
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.WolfyCore
import com.wolfy.ffi.WordAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Хранилище в памяти: тренировка проверяется своими правилами, а не диском. */
private class FakeStore : LibraryStore {
    private val records = mutableMapOf<String, String>()

    override fun load(name: String): String? = records[name]
    override fun save(name: String, json: String) {
        records[name] = json
    }

    override fun importBook(sourcePath: String, fileName: String): String = "/store/$fileName"
    override fun deleteBook(path: String) = Unit
    override fun fingerprint(path: String): String = ""
    override fun readText(path: String): String = ""
    override fun writeText(path: String, text: String): String = path
}

/**
 * Ядро-заглушка.
 *
 * Настоящее ядро на Rust в общих тестах недоступно, а тренировке от него нужны
 * ровно две вещи: список упражнений и разбор фразы. Остальное сюда попадает
 * потому, что так объявлен интерфейс.
 */
private class FakeCore(private val exercises: List<Exercise>) : WolfyCore {
    override fun version(): String = "тест"
    override fun analyzeWord(word: String): WordAnalysis = error("не нужно тренировке")
    override fun tokenize(text: String): ParsedText = ParsedText()
    override fun explain(sentence: String): List<Finding> = emptyList()
    override fun reference(): List<Article> = emptyList()
    override fun exercises(): List<Exercise> = exercises
    override fun openBook(path: String): OpenBook = error("не нужно тренировке")
    override fun readChapter(handle: Long, index: Int): Chapter = error("не нужно тренировке")
    override fun closeBook(handle: Long) = Unit
}

/**
 * Тренировка целиком: очередь, ответ, срок и серия.
 *
 * Расписание и сборку заданий проверяют свои тесты — здесь проверяется, что
 * они соединены правильно. Ошибка соединения не видна ни одному из них: обе
 * половины работают, а карточка после верного ответа остаётся на месте.
 */
class TrainingTest {

    private val exercise = Exercise(
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

    private class World(exercises: List<Exercise> = emptyList()) {
        val store = FakeStore()
        var now = 1_700_000_000_000L
        val library = Library(store, now = { now }, newId = { "card-" + (++counter) })
        val settings = Settings(store)
        val training = TrainingViewModel(library, settings, FakeCore(exercises), now = { now })
        private var counter = 0
    }

    @Test
    fun верный_ответ_отодвигает_карточку_и_продлевает_серию() {
        val world = World()
        world.library.saveWord(
            bookId = "book",
            surface = "library",
            lemma = "library",
            translation = "библиотека",
            context = "She left the library at dusk.",
        )

        world.training.start(Deck.Words)
        val drill = world.training.training.value.drill
        assertNotNull(drill, "задание не собралось")

        world.training.answer(drill.answer)

        val verdict = world.training.training.value.verdict
        assertNotNull(verdict)
        assertTrue(verdict.right, "верный ответ не засчитан")

        val card = world.library.state.value.cards.first()
        assertTrue(card.dueAt > world.now, "карточка осталась просроченной")
        assertTrue(card.hp < Scheduler.FULL_HP, "прочность не снялась")
        assertEquals(1, card.streak)

        assertEquals(1, world.settings.current.streakDays, "серия дней не пошла")
        assertEquals(1, world.settings.current.answers)
        assertEquals(1, world.settings.current.right)
    }

    @Test
    fun ошибка_возвращает_карточку_и_не_обрывает_серию_дней() {
        val world = World()
        world.library.saveWord(bookId = "book", surface = "dusk", lemma = "dusk", translation = "сумерки")

        world.training.start(Deck.Words)
        world.training.answer("совсем не то")

        val card = world.library.state.value.cards.first()
        assertEquals(0, card.streak)
        assertTrue(card.dueAt - world.now <= 15 * 60_000, "карточка не вернулась в тренировку")
        // Серия — про то, что человек сегодня занимался, а не про то, что он
        // отвечал верно. Обрывать её за ошибку значит наказывать за попытку.
        assertEquals(1, world.settings.current.streakDays)
        assertEquals(0, world.settings.current.right)
    }

    @Test
    fun второй_ответ_на_то_же_задание_не_считается() {
        val world = World()
        world.library.saveWord(bookId = "book", surface = "shelf", lemma = "shelf", translation = "полка")

        world.training.start(Deck.Words)
        val drill = world.training.training.value.drill!!
        world.training.answer(drill.answer)
        world.training.answer(drill.answer)

        // Иначе двойное нажатие забирало бы у карточки два срока сразу.
        assertEquals(1, world.settings.current.answers)
        assertEquals(1, world.library.state.value.cards.first().streak)
    }

    @Test
    fun карточка_правила_заводится_в_момент_вопроса() {
        val world = World(exercises = listOf(exercise))
        assertTrue(world.library.state.value.cards.isEmpty(), "карточки правил созданы заранее")

        world.training.start(Deck.Rules)
        val drill = world.training.training.value.drill
        assertNotNull(drill)
        assertEquals("has read", drill.answer)

        val card = world.library.state.value.cards.single()
        assertEquals("rule", card.kind)
        assertEquals("present-perfect", card.lemma)

        world.training.answer("has read")
        assertEquals(1, world.library.state.value.cards.single().streak)
    }

    @Test
    fun порция_кончается_итогом() {
        val world = World()
        world.library.saveWord(bookId = "book", surface = "candle", lemma = "candle", translation = "свеча")

        world.training.start(Deck.Words)
        world.training.answer("что угодно")
        world.training.next()

        val state = world.training.training.value
        assertTrue(state.finished, "порция не кончилась")
        assertNull(state.drill)
    }

    @Test
    fun пустая_колода_честно_говорит_что_пуста() {
        val world = World()
        world.training.start(Deck.Phrases)

        val state = world.training.training.value
        assertTrue(state.finished)
        assertEquals(0, state.total)
        assertNull(state.drill)
    }

    @Test
    fun несозревшая_карточка_в_порцию_не_попадает() {
        val world = World()
        world.library.saveWord(bookId = "book", surface = "moor", lemma = "moor", translation = "пустошь")

        world.training.start(Deck.Words)
        world.training.answer(world.training.training.value.drill!!.answer)
        world.training.next()

        // Тот же день, срок ещё не наступил: повторять нечего.
        world.training.start(Deck.Words)
        assertEquals(0, world.training.training.value.total)
    }

    @Test
    fun интенсивность_сохраняется_между_сессиями() {
        val world = World()
        world.training.setIntensity(Intensity.Extreme)
        assertEquals(Intensity.Extreme, world.settings.current.reviewIntensity)
        // Настройки читаются с диска заново — так их увидит следующий запуск.
        assertEquals(Intensity.Extreme, Settings(world.store).current.reviewIntensity)
    }
}
