package com.wolfy.data.companion

import com.wolfy.data.AiEvent
import com.wolfy.data.AiRecap
import com.wolfy.data.CompanionOpinion
import com.wolfy.data.CompanionQuestion
import com.wolfy.data.library.LibraryStore
import com.wolfy.data.library.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Локальная, ограниченная память компаньона.
 *
 * В неё никогда не попадает текст книги. Для точного кэша хранится только
 * короткий отпечаток входа, а для продолжения разговора: проверенные ответы,
 * краткие пересказы и последние вопросы читателя. Запись не синхронизируется:
 * это приватная память конкретного устройства, которую можно стереть целиком.
 */
class CompanionMemoryRepository(
    private val store: LibraryStore,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(CompanionMemory())
    val state: StateFlow<CompanionMemory> = _state.asStateFlow()

    fun restore() {
        val restored = runCatching { store.load(STORE_KEY) }.getOrNull()?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { json.decodeFromString(CompanionMemory.serializer(), raw) }.getOrNull()
        } ?: CompanionMemory()
        _state.value = trim(restored)
    }

    fun setEnabled(enabled: Boolean) = updateSettings { copy(enabled = enabled) }

    fun setShareWithAi(enabled: Boolean) = updateSettings { copy(shareWithAi = enabled) }

    fun setSize(size: String) {
        if (size !in MEMORY_SIZES) return
        updateSettings { copy(size = size) }
    }

    /** Стирает ответы и профиль чтения, но сохраняет выбранные настройки. */
    fun clear() = persist(CompanionMemory(settings = _state.value.settings))

    fun clearBook(bookId: String) {
        val current = _state.value
        persist(
            current.copy(
                cache = current.cache.filterNot { it.bookId == bookId },
                books = current.books.filterNot { it.bookId == bookId },
                requests = current.requests.filterNot { it.bookId == bookId },
            ),
        )
    }

    /*
     * Ключ собирается ровно из того, что влияет на ответ.
     *
     * Место прокрутки в него не входит намеренно. Позиция приезжает как доля
     * главы, умноженная на десять тысяч, то есть меняется от каждого движения
     * пальца, а на ответ не влияет никак: сервер поле position принимает и
     * нигде не читает. В ключе она давала бы промах кэша на любой строке,
     * прочитанной между двумя одинаковыми вопросами, — то есть выключала бы
     * кэш там, ради чего он и заведён. Страницу опознаёт её собственный текст,
     * а разговор о книге — вопрос вместе с прочитанным.
     */
    fun findOpinion(
        bookId: String,
        chapter: Int,
        pageText: String,
        profileHash: String,
    ): CompanionOpinion? = find(
        key = key("opinion", bookId, chapter.toString(), pageText, profileHash),
    )?.opinion?.copy(remaining = -1, cached = true)

    fun rememberOpinion(
        bookId: String,
        title: String,
        chapter: Int,
        pageText: String,
        profileHash: String,
        value: CompanionOpinion,
    ) {
        remember(
            MemoryCacheEntry(
                key = key("opinion", bookId, chapter.toString(), pageText, profileHash),
                kind = "opinion",
                bookId = bookId,
                createdAt = clock(),
                opinion = value.copy(cached = false),
            ),
            request = MemoryRequest(bookId, title.take(MAX_TITLE), "Мнение о странице", clock()),
        )
    }

    fun findQuestion(
        bookId: String,
        chapter: Int,
        question: String,
        context: String,
        profileHash: String,
    ): CompanionQuestion? = find(
        key = key("question", bookId, chapter.toString(), question, context, profileHash),
    )?.question?.copy(remaining = -1, cached = true)

    fun rememberQuestion(
        bookId: String,
        title: String,
        chapter: Int,
        question: String,
        context: String,
        profileHash: String,
        value: CompanionQuestion,
    ) {
        remember(
            MemoryCacheEntry(
                key = key("question", bookId, chapter.toString(), question, context, profileHash),
                kind = "question",
                bookId = bookId,
                createdAt = clock(),
                question = value.copy(cached = false),
            ),
            request = MemoryRequest(bookId, title.take(MAX_TITLE), question.take(MAX_QUESTION), clock()),
        )
    }

    fun findRecap(bookId: String, excerpt: String): AiRecap? = find(
        key = key("recap", bookId, excerpt),
    )?.recap?.copy(remaining = -1, cached = true)

    fun rememberRecap(
        bookId: String,
        title: String,
        chapter: Int,
        excerpt: String,
        value: AiRecap,
    ) {
        val now = clock()
        val current = _state.value
        if (!current.settings.enabled) return
        val entry = MemoryCacheEntry(
            key = key("recap", bookId, excerpt),
            kind = "recap",
            bookId = bookId,
            createdAt = now,
            recap = value.copy(cached = false),
        )
        val checkpoint = BookCheckpoint(
            chapter = chapter,
            summary = value.summary.take(MAX_SUMMARY),
            events = value.events.take(MAX_EVENTS).map {
                MemoryEvent(it.title.take(MAX_EVENT_PART), it.text.take(MAX_EVENT_PART), it.kind)
            },
            updatedAt = now,
        )
        val existing = current.books.firstOrNull { it.bookId == bookId }
        val book = BookMemory(
            bookId = bookId,
            title = title.take(MAX_TITLE),
            checkpoints = ((existing?.checkpoints ?: emptyList()).filterNot { it.chapter == chapter } + checkpoint)
                .sortedByDescending { it.updatedAt },
            updatedAt = now,
        )
        persist(
            current.copy(
                cache = listOf(entry) + current.cache.filterNot { it.key == entry.key },
                books = listOf(book) + current.books.filterNot { it.bookId == bookId },
                requests = listOf(MemoryRequest(bookId, title.take(MAX_TITLE), "Вспомнить сюжет", now)) + current.requests,
            ),
        )
    }

    /**
     * Выжимка для следующего запроса. Она ограничена отдельно от кэша и
     * помечает прошлые ответы как возможные ошибки, а не как источник истины.
     */
    fun contextFor(bookId: String): String {
        val memory = _state.value
        if (!memory.settings.enabled || !memory.settings.shareWithAi) return ""
        val book = memory.books.firstOrNull { it.bookId == bookId }
        val requests = memory.requests.filter { it.bookId == bookId }.take(6)
        val generalRequests = memory.requests.filterNot { it.bookId == bookId }.take(3)
        if (book == null && requests.isEmpty() && generalRequests.isEmpty()) return ""
        val parts = mutableListOf<String>()
        book?.let {
            parts += "Ранее сохранённые краткие пересказы книги (могут содержать ошибки):"
            it.checkpoints.take(3).reversed().forEach { point ->
                parts += "Глава ${point.chapter + 1}: ${point.summary}"
            }
        }
        if (requests.isNotEmpty()) {
            parts += "Недавние запросы читателя: " + requests.asReversed().joinToString("; ") { it.text }
        }
        if (generalRequests.isNotEmpty()) {
            parts += "Обычный стиль запросов читателя: " + generalRequests.asReversed().joinToString("; ") { it.text }
        }
        return parts.joinToString("\n").take(MAX_PROMPT_MEMORY)
    }

    val stats: CompanionMemoryStats
        get() = CompanionMemoryStats(
            answers = _state.value.cache.size,
            books = _state.value.books.size,
            requests = _state.value.requests.size,
        )

    private fun find(key: String): MemoryCacheEntry? {
        val current = _state.value
        if (!current.settings.enabled) return null
        return current.cache.firstOrNull { it.key == key }
    }

    private fun remember(entry: MemoryCacheEntry, request: MemoryRequest) {
        val current = _state.value
        if (!current.settings.enabled) return
        persist(
            current.copy(
                cache = listOf(entry) + current.cache.filterNot { it.key == entry.key },
                requests = listOf(request) + current.requests,
            ),
        )
    }

    private fun updateSettings(change: CompanionMemorySettings.() -> CompanionMemorySettings) {
        persist(_state.value.copy(settings = _state.value.settings.change()))
    }

    private fun persist(value: CompanionMemory) {
        val fixed = trim(value)
        // Переполненное или временно недоступное хранилище не должно оставлять
        // лист ответа в вечной загрузке. Сессия продолжит помнить в RAM.
        runCatching { store.save(STORE_KEY, json.encodeToString(CompanionMemory.serializer(), fixed)) }
        _state.value = fixed
    }

    private fun trim(value: CompanionMemory): CompanionMemory {
        val limits = limitsFor(value.settings.size)
        val books = value.books.sortedByDescending { it.updatedAt }.take(limits.books).map { book ->
            book.copy(checkpoints = book.checkpoints.sortedByDescending { it.updatedAt }.take(limits.checkpoints))
        }
        return value.copy(
            schemaVersion = SCHEMA_VERSION,
            settings = value.settings.copy(size = value.settings.size.takeIf { it in MEMORY_SIZES } ?: "balanced"),
            cache = value.cache.sortedByDescending { it.createdAt }.take(limits.cache),
            books = books,
            requests = value.requests.sortedByDescending { it.createdAt }.take(limits.requests),
        )
    }

    /**
     * Отпечаток входа: сам текст никуда не пишется, сравнивается только хэш.
     *
     * Ширина взята шестьдесят четыре бита, а не тридцать два. Цена совпадения
     * здесь несимметрично высока: компаньон уверенно ответит про чужую
     * страницу, а проверить это нечем — исходного текста рядом нет. На
     * двухстах пятидесяти записях «большой» памяти тридцать два бита дают
     * примерно семь шансов на миллион; редко, но никогда не ноль, и ловится
     * такое только жалобой. Шестьдесят четыре снимают вопрос.
     *
     * Между кусками подмешивается длина куска. Разделитель тут был и раньше
     * (шаг `xor 0` плюс умножение), и своё дело делал: слить соседние поля в
     * одно он не позволял. Но `xor 0` не добавлял ни бита, а длина добавляет,
     * и стоит она столько же.
     */
    private fun key(kind: String, vararg parts: String): String {
        var hash = 0xcbf29ce484222325uL.toLong() // FNV-1a, 64 бита.
        for (part in parts) {
            for (char in part) {
                hash = hash xor char.code.toLong()
                hash *= 0x100000001b3L
            }
            hash = hash xor part.length.toLong()
            hash *= 0x100000001b3L
        }
        return "$kind:${hash.toULong().toString(16)}"
    }

    private data class Limits(val cache: Int, val books: Int, val checkpoints: Int, val requests: Int)

    private fun limitsFor(size: String): Limits = when (size) {
        "compact" -> Limits(cache = 30, books = 4, checkpoints = 4, requests = 8)
        "deep" -> Limits(cache = 250, books = 30, checkpoints = 24, requests = 40)
        else -> Limits(cache = 100, books = 12, checkpoints = 10, requests = 20)
    }

    private companion object {
        const val STORE_KEY = "companion_memory"
        const val SCHEMA_VERSION = 1
        const val MAX_TITLE = 300
        const val MAX_QUESTION = 500
        const val MAX_SUMMARY = 1200
        const val MAX_EVENTS = 6
        const val MAX_EVENT_PART = 300
        const val MAX_PROMPT_MEMORY = 3500
        val MEMORY_SIZES = setOf("compact", "balanced", "deep")
    }
}

@Serializable
data class CompanionMemorySettings(
    val enabled: Boolean = true,
    val shareWithAi: Boolean = true,
    val size: String = "balanced",
)

@Serializable
data class CompanionMemory(
    val schemaVersion: Int = 1,
    val settings: CompanionMemorySettings = CompanionMemorySettings(),
    val cache: List<MemoryCacheEntry> = emptyList(),
    val books: List<BookMemory> = emptyList(),
    val requests: List<MemoryRequest> = emptyList(),
)

@Serializable
data class MemoryCacheEntry(
    val key: String,
    val kind: String,
    val bookId: String,
    val createdAt: Long,
    val opinion: CompanionOpinion? = null,
    val question: CompanionQuestion? = null,
    val recap: AiRecap? = null,
)

@Serializable
data class BookMemory(
    val bookId: String,
    val title: String,
    val checkpoints: List<BookCheckpoint> = emptyList(),
    val updatedAt: Long,
)

@Serializable
data class BookCheckpoint(
    val chapter: Int,
    val summary: String,
    val events: List<MemoryEvent> = emptyList(),
    val updatedAt: Long,
)

@Serializable
data class MemoryEvent(val title: String, val text: String, val kind: String)

@Serializable
data class MemoryRequest(val bookId: String, val title: String, val text: String, val createdAt: Long)

data class CompanionMemoryStats(val answers: Int, val books: Int, val requests: Int)
