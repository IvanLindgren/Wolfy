import com.wolfy.data.AiEvent
import com.wolfy.data.AiRecap
import com.wolfy.data.CompanionEvidence
import com.wolfy.data.CompanionOpinion
import com.wolfy.data.CompanionQuestion
import com.wolfy.data.companion.CompanionMemoryRepository
import com.wolfy.data.library.LibraryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class MemoryStore : LibraryStore {
    val records = mutableMapOf<String, String>()
    override fun load(name: String): String? = records[name]
    override fun save(name: String, json: String) { records[name] = json }
    override fun importBook(sourcePath: String, fileName: String) = ""
    override fun deleteBook(path: String) = Unit
    override fun fingerprint(path: String) = ""
    override fun readText(path: String) = ""
    override fun writeText(fileName: String, text: String) = ""
    override fun appendText(fileName: String, text: String) = ""
    override fun writeBook(fileName: String, bytes: ByteArray) = ""
    override fun writeCover(bookId: String, extension: String, bytes: ByteArray) = ""
    override fun findCover(bookId: String): String? = null
    override fun deleteCover(bookId: String) = Unit
    override fun readBinary(path: String): ByteArray? = null
    override fun dictionaryPath() = ""
    override fun installDictionary(compressed: ByteArray) = ""
}

class CompanionMemoryRepositoryTest {
    @Test
    fun повторный_вопрос_возвращается_из_локального_кэша_без_текста_книги() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store) { 100L }
        memory.restore()
        val answer = CompanionQuestion(
            answer = "Он уже уехал.",
            evidence = listOf(CompanionEvidence("Событие", "Герой сел в поезд.")),
            remaining = 7,
        )

        memory.rememberQuestion("book", "Книга", 2, "Где герой?", 4000, "persona", answer)

        val cached = memory.findQuestion("book", 2, "Где герой?", 4000, "persona")
        assertNotNull(cached)
        assertEquals("Он уже уехал.", cached.answer)
        assertTrue(cached.cached)
        assertEquals(-1, cached.remaining)

        // Текст книги на диск не попадает ни при каких условиях: страница
        // уходит в отпечаток и больше никуда. Вопрос читателя, наоборот,
        // сохраняется намеренно — из него собирается память разговора.
        memory.rememberOpinion(
            "book", 2, "секретный текст страницы", "persona",
            CompanionOpinion(title = "Оговорка", opinion = "Он лукавит."),
        )
        assertFalse(store.records.getValue("companion_memory").contains("секретный текст страницы"))
    }

    @Test
    fun пересказ_становится_краткой_памятью_книги_и_переживает_перезапуск() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store) { 200L }
        memory.restore()
        memory.rememberRecap(
            "book", "Книга", 4, 3000,
            AiRecap("Герой нашёл письмо и сменил планы.", listOf(AiEvent("Письмо", "Пришла новость.", "turn")), 6),
        )

        val restored = CompanionMemoryRepository(store)
        restored.restore()
        assertTrue(restored.contextFor("book").contains("Герой нашёл письмо"))
        assertTrue(restored.findRecap("book", 4, 3000)?.cached == true)
    }

    /**
     * Соседние поля запроса не сливаются в один отпечаток.
     *
     * Куски идут в хэш подряд, и без разделителя между ними ("аб","в") и
     * ("а","бв") дали бы одно число: на вопрос вернулся бы уверенный ответ,
     * заданный про другое, а сверить его не с чем - исходного текста в памяти
     * нет, только хэш. Разделитель на месте, и тест держит его там: закрепить
     * условие дешевле, чем однажды искать причину чужого ответа.
     */
    @Test
    fun сдвиг_границы_между_вопросом_и_личностью_не_выдаёт_чужой_ответ() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store) { 100L }
        memory.restore()

        memory.rememberQuestion(
            "book", "Книга", 1, "Кто он", 0, "персона",
            CompanionQuestion(answer = "Ответ про первое.", remaining = 5),
        )

        assertNull(memory.findQuestion("book", 1, "Кто", 0, "онперсона"))
        assertNotNull(memory.findQuestion("book", 1, "Кто он", 0, "персона"))
    }

    /**
     * Один и тот же вопрос за вечер стоит одного запроса, а не двух.
     *
     * В ключ уходило всё прочитанное, а оно прирастает каждой строкой: кэш
     * промахивался всегда, кроме «нажал дважды подряд, не двинувшись». Ради
     * этого случая хранилище на двести пятьдесят ответов заводить незачем.
     */
    @Test
    fun прочитанная_страница_не_отменяет_прошлый_ответ_а_прочитанная_глава_отменяет() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store) { 100L }
        memory.restore()
        memory.rememberQuestion(
            "book", "Книга", 3, "Почему он молчит?", 4000, "persona",
            CompanionQuestion(answer = "Боится.", remaining = 5),
        )

        // Половина экрана вперёд — тот же разговор.
        assertNotNull(
            memory.findQuestion("book", 3, "Почему он молчит?", 4200, "persona"),
            "промах на двух прочитанных строках",
        )
        // Регистр и знаки вопроса не меняют.
        assertNotNull(memory.findQuestion("book", 3, "почему он молчит", 4000, "persona"))
        // Полглавы вперёд — уже другой.
        assertNull(
            memory.findQuestion("book", 3, "Почему он молчит?", 9000, "persona"),
            "ответ про начало главы выдан за ответ про её конец",
        )
        // Другая глава — тем более другой.
        assertNull(memory.findQuestion("book", 4, "Почему он молчит?", 4000, "persona"))
    }

    /**
     * Нажатие кнопки — не вопрос.
     *
     * «Мнение о странице» и «вспомнить сюжет» писались в тот же список, что и
     * вопросы читателя, и весь список уезжал в промпт строкой «недавние
     * запросы читателя». Модель шесть раз подряд получала одну и ту же
     * подпись кнопки; при двадцати местах на список подписи вытесняли оттуда
     * настоящие вопросы, ради которых память и заведена.
     */
    @Test
    fun нажатие_кнопки_не_выдаётся_модели_за_вопрос_читателя() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store) { 300L }
        memory.restore()

        memory.rememberOpinion("book", 4, "текст страницы", "persona", CompanionOpinion(title = "Оговорка", opinion = "Он лукавит."))
        memory.rememberRecap(
            "book", "Книга", 4, 2000,
            AiRecap("Герой уехал.", emptyList(), 8),
        )

        val context = memory.contextFor("book")
        assertTrue(context.contains("Герой уехал"), "пересказ обязан остаться в памяти")
        assertFalse(context.contains("Мнение о странице"))
        assertFalse(context.contains("Вспомнить сюжет"))
        assertEquals(0, memory.stats.questions, "вопросов читатель ещё не задавал")

        memory.rememberQuestion(
            "book", "Книга", 4, "Почему он молчит?", 2000, "persona",
            CompanionQuestion(answer = "Боится.", remaining = 7),
        )
        assertTrue(memory.contextFor("book").contains("Почему он молчит?"))
    }

    /**
     * Старая память читается заново, а не чинится по подписям.
     *
     * В первой схеме вопрос и нажатие лежали в одном списке и различались
     * только текстом. Текст — это интерфейс: разбирать по нему данные значит
     * завести зависимость, которая сломается от правки надписи на кнопке.
     */
    @Test
    fun память_первой_схемы_не_приносит_подписи_кнопок_в_новый_промпт() {
        val store = MemoryStore()
        store.save(
            "companion_memory",
            """{"schemaVersion":1,"settings":{"enabled":true,"shareWithAi":true,"size":"balanced"},
               "cache":[],"books":[],
               "questions":[{"bookId":"book","title":"Книга","text":"Мнение о странице","createdAt":1}]}""",
        )
        val memory = CompanionMemoryRepository(store) { 400L }
        memory.restore()
        assertEquals(0, memory.stats.questions)
        assertEquals("", memory.contextFor("book"))
    }

    @Test
    fun отключение_и_очистка_уважают_настройки() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store)
        memory.restore()
        memory.setSize("deep")
        memory.setEnabled(false)
        assertNull(memory.findRecap("book", 0, 0))
        memory.clear()
        assertEquals("deep", memory.state.value.settings.size)
        assertFalse(memory.state.value.settings.enabled)
        assertEquals(0, memory.stats.answers)
    }
}
