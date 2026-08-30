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
        _state.value = trim(migrate(restored))
    }

    /**
     * Перенос старой памяти.
     *
     * В первой схеме в один список писались и вопросы читателя, и нажатия
     * кнопок, и весь список уезжал в промпт как «недавние запросы читателя».
     * Различить их задним числом можно только по тексту подписи, а подписи —
     * это интерфейс, и опираться на них в разборе данных значит завести
     * зависимость, которая сломается от правки текста кнопки.
     *
     * Поэтому список читается заново, а не чинится: там оставались вопросы
     * последних дней одного устройства, и цена потери — один запрос без
     * продолжения разговора. Цена лечения по подписям — тихая ошибка через
     * полгода.
     */
    private fun migrate(value: CompanionMemory): CompanionMemory =
        if (value.schemaVersion >= SCHEMA_VERSION) {
            value
        } else {
            // Кэш уходит вместе со списком: во второй схеме ключи собираются
            // иначе, и записи первой не совпадут ни с одним новым вопросом.
            // Мёртвый вес, который занимал бы место до вытеснения по времени.
            value.copy(cache = emptyList(), questions = emptyList())
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
                questions = current.questions.filterNot { it.bookId == bookId },
            ),
        )
    }

    /*
     * Ключ собирается из того, что читатель считает своим запросом, а не из
     * всего, что уехало на сервер.
     *
     * Разница видна на вопросе о книге. В ключ входило всё прочитанное — а оно
     * прирастает каждой строкой, и один и тот же вопрос, заданный дважды за
     * вечер, был для памяти двумя разными вопросами. Кэш промахивался всегда,
     * кроме случая «нажал дважды подряд, не двинувшись»; ради этого случая
     * заводить хранилище на двести пятьдесят записей незачем.
     *
     * Прочитанное заменено местом в главе, огрублённым до двадцатой доли.
     * Сырую позицию в ключ и правда класть нельзя — она меняется от каждого
     * движения пальца, — но огрублённая отвечает ровно на тот вопрос, который
     * и решает, годится ли прошлый ответ: случилось ли с тех пор что-нибудь
     * новое. Полглавы вперёд — другой разговор, полстроки — тот же.
     *
     * Мнение о странице живёт по другому правилу: там текст страницы и есть
     * вопрос, и подменять его местом нельзя. Оно только приводится к общему
     * виду, чтобы одна и та же страница, собранная дважды, не разошлась на
     * пробеле.
     */
    fun findOpinion(
        bookId: String,
        chapter: Int,
        pageText: String,
        profileHash: String,
    ): CompanionOpinion? = find(
        key = key("opinion", bookId, chapter.toString(), plain(pageText), profileHash),
    )?.opinion?.copy(remaining = -1, cached = true)

    fun rememberOpinion(
        bookId: String,
        chapter: Int,
        pageText: String,
        profileHash: String,
        value: CompanionOpinion,
    ) {
        remember(
            MemoryCacheEntry(
                key = key("opinion", bookId, chapter.toString(), plain(pageText), profileHash),
                kind = "opinion",
                bookId = bookId,
                createdAt = clock(),
                opinion = value.copy(cached = false),
            ),
            question = null,
        )
    }

    fun findQuestion(
        bookId: String,
        chapter: Int,
        question: String,
        position: Int,
        profileHash: String,
    ): CompanionQuestion? = find(
        key = key("question", bookId, chapter.toString(), plain(question), place(position), profileHash),
    )?.question?.copy(remaining = -1, cached = true)

    fun rememberQuestion(
        bookId: String,
        title: String,
        chapter: Int,
        question: String,
        position: Int,
        profileHash: String,
        value: CompanionQuestion,
    ) {
        remember(
            MemoryCacheEntry(
                key = key("question", bookId, chapter.toString(), plain(question), place(position), profileHash),
                kind = "question",
                bookId = bookId,
                createdAt = clock(),
                question = value.copy(cached = false),
            ),
            question = MemoryQuestion(bookId, title.take(MAX_TITLE), question.take(MAX_QUESTION), clock()),
        )
    }

    /**
     * Пересказ ищется по месту, а не по фрагменту.
     *
     * Фрагмент — это скользящее окно последних экранов: оно меняется от каждой
     * прочитанной строки, и «вспомнить сюжет» дважды за вечер стоило двух
     * самых дорогих запросов из всех, что делает приложение. Пересказ отвечает
     * на вопрос «что было до сих пор», и его тождество — это и есть «до сих
     * пор»: глава и место в ней.
     */
    fun findRecap(bookId: String, chapter: Int, position: Int): AiRecap? = find(
        key = key("recap", bookId, chapter.toString(), place(position)),
    )?.recap?.copy(remaining = -1, cached = true)

    fun rememberRecap(
        bookId: String,
        title: String,
        chapter: Int,
        position: Int,
        value: AiRecap,
    ) {
        val now = clock()
        val current = _state.value
        if (!current.settings.enabled) return
        val entry = MemoryCacheEntry(
            key = key("recap", bookId, chapter.toString(), place(position)),
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
        val hereQuestions = memory.questions.filter { it.bookId == bookId }.take(6)
        val elsewhereQuestions = memory.questions.filterNot { it.bookId == bookId }.take(3)
        if (book == null && hereQuestions.isEmpty() && elsewhereQuestions.isEmpty()) return ""
        val parts = mutableListOf<String>()
        book?.let {
            parts += "Ранее сохранённые краткие пересказы книги (могут содержать ошибки):"
            it.checkpoints.take(3).reversed().forEach { point ->
                parts += "Глава ${point.chapter + 1}: ${point.summary}"
            }
        }
        if (hereQuestions.isNotEmpty()) {
            parts += "Читатель уже спрашивал об этой книге: " + hereQuestions.asReversed().joinToString("; ") { it.text }
        }
        if (elsewhereQuestions.isNotEmpty()) {
            parts += "О чём этот читатель спрашивает обычно: " + elsewhereQuestions.asReversed().joinToString("; ") { it.text }
        }
        return parts.joinToString("\n").take(MAX_PROMPT_MEMORY)
    }

    val stats: CompanionMemoryStats
        get() = CompanionMemoryStats(
            answers = _state.value.cache.size,
            books = _state.value.books.size,
            questions = _state.value.questions.size,
        )

    private fun find(key: String): MemoryCacheEntry? {
        val current = _state.value
        if (!current.settings.enabled) return null
        return current.cache.firstOrNull { it.key == key }
    }

    private fun remember(entry: MemoryCacheEntry, question: MemoryQuestion?) {
        val current = _state.value
        if (!current.settings.enabled) return
        persist(
            current.copy(
                cache = listOf(entry) + current.cache.filterNot { it.key == entry.key },
                questions = question?.let { listOf(it) + current.questions } ?: current.questions,
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
            questions = value.questions.sortedByDescending { it.createdAt }.take(limits.questions),
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

    /**
     * Место в главе, огрублённое до двадцатой доли.
     *
     * Двадцатая доли — примерно половина экрана на телефоне. Это и есть порог
     * «случилось ли что-нибудь новое»: полстроки назад или вперёд разговора не
     * меняют, полглавы меняют. Огрубление намеренно грубое: точность здесь
     * нужна не для правильности ответа, а только для того, чтобы отличить
     * движение пальца от чтения.
     */
    private fun place(position: Int): String =
        (position.coerceIn(0, PLACE_RANGE) / PLACE_STEP).toString()

    /**
     * Текст в том виде, в каком он опознаётся как «тот же самый».
     *
     * Регистр, знаки и пробелы к смыслу вопроса отношения не имеют: «Кто он?»
     * и «кто он» — один вопрос, и платить за второй незачем. Страница же
     * собирается из блоков заново на каждый показ, и разойтись двум сборкам
     * достаточно одного лишнего перевода строки.
     */
    private fun plain(text: String): String = buildString(text.length) {
        var space = false
        for (char in text) {
            when {
                char.isLetterOrDigit() -> {
                    if (space && isNotEmpty()) append(' ')
                    space = false
                    append(char.lowercaseChar())
                }
                else -> space = true
            }
        }
    }

    private data class Limits(val cache: Int, val books: Int, val checkpoints: Int, val questions: Int)

    private fun limitsFor(size: String): Limits = when (size) {
        "compact" -> Limits(cache = 30, books = 4, checkpoints = 4, questions = 8)
        "deep" -> Limits(cache = 250, books = 30, checkpoints = 24, questions = 40)
        else -> Limits(cache = 100, books = 12, checkpoints = 10, questions = 20)
    }

    private companion object {
        const val STORE_KEY = "companion_memory"
        const val SCHEMA_VERSION = 2
        const val MAX_TITLE = 300
        const val MAX_QUESTION = 500
        const val MAX_SUMMARY = 1200
        const val MAX_EVENTS = 6
        const val MAX_EVENT_PART = 300
        const val MAX_PROMPT_MEMORY = 3500

        /** Позиция приезжает как доля главы, умноженная на десять тысяч. */
        const val PLACE_RANGE = 10_000
        const val PLACE_STEP = 500
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
    /**
     * Вопросы читателя — те, что он написал сам.
     *
     * Раньше список назывался `requests`, и в него же попадало каждое нажатие
     * «мнение о странице» и «вспомнить сюжет». Оттуда он целиком уезжал в
     * промпт строкой «недавние запросы читателя», и модель получала подпись
     * кнопки вместо вопроса — шесть раз подряд одну и ту же. Хуже: при
     * двадцати местах на список подписи вытесняли настоящие вопросы, ради
     * которых память и заведена.
     *
     * Нажатие кнопки — не вопрос, и в памяти разговора ему места нет.
     */
    val questions: List<MemoryQuestion> = emptyList(),
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
data class MemoryQuestion(val bookId: String, val title: String, val text: String, val createdAt: Long)

data class CompanionMemoryStats(val answers: Int, val books: Int, val questions: Int)
