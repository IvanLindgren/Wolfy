import com.wolfy.data.AiEvent
import com.wolfy.data.AiRecap
import com.wolfy.data.CompanionEvidence
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

        memory.rememberQuestion("book", "Книга", 2, "Где герой?", "секретный текст страницы", "persona", answer)

        val cached = memory.findQuestion("book", 2, "Где герой?", "секретный текст страницы", "persona")
        assertNotNull(cached)
        assertEquals("Он уже уехал.", cached.answer)
        assertTrue(cached.cached)
        assertEquals(-1, cached.remaining)
        assertFalse(store.records.getValue("companion_memory").contains("секретный текст страницы"))
    }

    @Test
    fun пересказ_становится_краткой_памятью_книги_и_переживает_перезапуск() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store) { 200L }
        memory.restore()
        memory.rememberRecap(
            "book", "Книга", 4, "recent excerpt",
            AiRecap("Герой нашёл письмо и сменил планы.", listOf(AiEvent("Письмо", "Пришла новость.", "turn")), 6),
        )

        val restored = CompanionMemoryRepository(store)
        restored.restore()
        assertTrue(restored.contextFor("book").contains("Герой нашёл письмо"))
        assertTrue(restored.findRecap("book", "recent excerpt")?.cached == true)
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
    fun сдвиг_границы_между_вопросом_и_прочитанным_не_выдаёт_чужой_ответ() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store) { 100L }
        memory.restore()

        memory.rememberQuestion(
            "book", "Книга", 1, "Кто он", "стал королём", "persona",
            CompanionQuestion(answer = "Ответ про первое.", remaining = 5),
        )

        assertNull(memory.findQuestion("book", 1, "Кто", "он стал королём", "persona"))
        assertNotNull(memory.findQuestion("book", 1, "Кто он", "стал королём", "persona"))
    }

    @Test
    fun отключение_и_очистка_уважают_настройки() {
        val store = MemoryStore()
        val memory = CompanionMemoryRepository(store)
        memory.restore()
        memory.setSize("deep")
        memory.setEnabled(false)
        assertNull(memory.findRecap("book", "text"))
        memory.clear()
        assertEquals("deep", memory.state.value.settings.size)
        assertFalse(memory.state.value.settings.enabled)
        assertEquals(0, memory.stats.answers)
    }
}
