import com.wolfy.data.library.LibraryStore
import com.wolfy.data.library.lastReadBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Хранилище в памяти: тесту нужен только `load`. */
private class FakeStore(private val records: Map<String, String>) : LibraryStore {
    val asked = mutableListOf<String>()

    override fun load(name: String): String? {
        asked.add(name)
        return records[name]
    }

    // Всё остальное хранилище тесту не нужно: `lastReadBook` только читает.
    override fun save(name: String, json: String) = Unit
    override fun importBook(sourcePath: String, fileName: String): String = ""
    override fun deleteBook(path: String) = Unit
    override fun fingerprint(path: String): String = ""
    override fun readText(path: String): String = ""
    override fun writeText(fileName: String, text: String): String = ""
    override fun appendText(fileName: String, text: String): String = ""
    override fun writeBook(fileName: String, bytes: ByteArray): String = ""
    override fun writeCover(bookId: String, extension: String, bytes: ByteArray): String = ""
    override fun findCover(bookId: String): String? = null
    override fun deleteCover(bookId: String) = Unit
    override fun readBinary(path: String): ByteArray? = null
    override fun dictionaryPath(): String = ""
    override fun installDictionary(compressed: ByteArray): String = ""
}

class LastReadTest {
    private fun библиотека(books: String) = """{"books":[$books],"cards":[],"shelves":[]}"""

    private fun книга(
        id: String,
        title: String,
        path: String = "/books/$id.epub",
        openedAt: Long,
        deleted: Boolean = false,
    ) = """{"id":"$id","title":"$title","path":"$path","chapters":10,
            "progress":{"chapter":2,"withinChapter":0.5,"openedAt":$openedAt},
            "deleted":$deleted}"""

    // Имя записи повторено в двух местах намеренно; разойтись они не должны,
    // иначе виджет будет вечно показывать «нечего читать».
    @Test
    fun читается_запись_library() {
        val store = FakeStore(emptyMap())
        store.lastReadBook()
        assertEquals(listOf("library"), store.asked)
    }

    @Test
    fun выбирается_открытая_последней() {
        val store = FakeStore(
            mapOf(
                "library" to библиотека(
                    книга("a", "Ранняя", openedAt = 100) + "," +
                        книга("b", "Поздняя", openedAt = 900) + "," +
                        книга("c", "Средняя", openedAt = 500),
                ),
            ),
        )
        assertEquals("Поздняя", store.lastReadBook()?.title)
    }

    // Книга без файла приехала синхронизацией: открыть её нечем, и
    // приглашение вело бы в тупик.
    @Test
    fun книга_без_файла_не_годится() {
        val store = FakeStore(
            mapOf(
                "library" to библиотека(
                    книга("a", "Есть файл", openedAt = 100) + "," +
                        книга("b", "Без файла", path = "", openedAt = 900),
                ),
            ),
        )
        assertEquals("Есть файл", store.lastReadBook()?.title)
    }

    @Test
    fun удалённая_книга_не_годится() {
        val store = FakeStore(
            mapOf("library" to библиотека(книга("a", "Удалена", openedAt = 900, deleted = true))),
        )
        assertNull(store.lastReadBook())
    }

    @Test
    fun ни_разу_не_открытая_книга_не_зовёт() {
        val store = FakeStore(mapOf("library" to библиотека(книга("a", "Новая", openedAt = 0))))
        assertNull(store.lastReadBook())
    }

    // Виджет не должен ронять лаунчер из-за повреждённой записи.
    @Test
    fun битая_запись_читается_как_пустая() {
        assertNull(FakeStore(mapOf("library" to "{не json")).lastReadBook())
        assertNull(FakeStore(mapOf("library" to "")).lastReadBook())
        assertNull(FakeStore(emptyMap()).lastReadBook())
    }
}
