package com.wolfy.data.annotations

import com.wolfy.data.library.LibraryStore
import com.wolfy.data.library.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Отметки одной книги: краски и заметки.
 *
 * Файл на книгу, а не общая таблица: отметки читают и пишут только пока книга
 * открыта, и держать в памяти чужие незачем.
 *
 * Порядок правок задаёт счётчик Лампорта этого устройства, а не часы. Часы на
 * телефоне и на ноутбуке расходятся, и «побеждает более поздний по времени»
 * означало бы, что победитель зависит от того, у кого спешат часы.
 */
class AnnotationRepository(
    private val store: LibraryStore,
    private val writer: () -> String,
    private val clock: () -> Long = ::currentTimeMillis,
    private val newId: () -> String = ::randomId,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(AnnotationState())
    val state: StateFlow<AnnotationState> = _state.asStateFlow()

    /** Открывает книгу: поднимает её файл с диска. Сеть не трогается. */
    fun open(bookId: String) {
        if (bookId.isBlank()) {
            _state.value = AnnotationState()
            return
        }
        val stored = runCatching { store.load(pathOf(bookId)) }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { json.decodeFromString(Stored.serializer(), raw) }.getOrNull() }
            ?: Stored()
        _state.value = AnnotationState(
            bookId = bookId,
            items = stored.items,
            lamport = stored.lamport,
            seen = stored.seen,
        )
    }

    fun close() {
        _state.value = AnnotationState()
    }

    /** Отметки главы, которые надо покрасить. */
    fun paintedIn(chapter: Int): List<Annotation> =
        _state.value.items.filter { it.paints && it.chapter == chapter }

    /** Отметка, накрывающая этот токен главы, если она есть. */
    fun at(chapter: Int, token: Int): Annotation? = _state.value.items.firstOrNull {
        !it.deleted && it.chapter == chapter && token >= it.start && token < it.end
    }

    /**
     * Заводит отметку. Возвращает её же - вызвавшему обычно нужно тут же
     * открыть поле заметки.
     */
    fun add(chapter: Int, start: Int, end: Int, tone: Int?, quote: String, note: String = ""): Annotation? {
        val current = _state.value
        if (current.bookId.isEmpty()) return null
        val now = clock()
        val rev = current.lamport + 1
        val item = Annotation(
            id = newId(),
            chapter = chapter,
            start = start,
            end = end,
            tone = tone,
            quote = quote.take(MAX_QUOTE),
            note = note.take(MAX_NOTE),
            rev = rev,
            writer = writer(),
            createdAt = now,
            updatedAt = now,
        )
        commit(current.copy(items = current.items + item, lamport = rev))
        return item
    }

    /** Меняет краску или текст заметки. */
    fun update(id: String, tone: Int? = null, note: String? = null, clearTone: Boolean = false) {
        val current = _state.value
        val rev = current.lamport + 1
        val now = clock()
        val items = current.items.map { item ->
            if (item.id != id) item else item.copy(
                tone = if (clearTone) null else tone ?: item.tone,
                note = note?.take(MAX_NOTE) ?: item.note,
                rev = rev,
                writer = writer(),
                updatedAt = now,
            )
        }
        commit(current.copy(items = items, lamport = rev))
    }

    /**
     * Помечает отметку удалённой.
     *
     * Именно помечает. Стереть строку значило бы, что второе устройство о
     * судьбе отметки не узнает и вернёт её из своего файла при первой же
     * синхронизации. Содержимое при этом вычищается: удалённая заметка не
     * должна продолжать лежать в файле текстом.
     */
    fun remove(id: String) {
        val current = _state.value
        val rev = current.lamport + 1
        val now = clock()
        val items = current.items.map { item ->
            if (item.id != id) item else item.copy(
                deleted = true,
                tone = null,
                quote = "",
                note = "",
                rev = rev,
                writer = writer(),
                updatedAt = now,
            )
        }
        commit(current.copy(items = items, lamport = rev))
    }

    /**
     * Принимает список от сервера.
     *
     * Поколение запоминается только после того, как слитый список лёг на диск:
     * это подтверждение сборщику мусора, что снимок долговечно сохранён, и
     * выдавать его авансом нельзя - сервер по нему стирает пометки удаления.
     */
    fun accept(items: List<Annotation>, generation: Long) {
        val current = _state.value
        if (current.bookId.isEmpty()) return
        val merged = merge(current.items, items)
        // Счётчик догоняет чужие правки: иначе следующая местная правка
        // получила бы номер, который у другого устройства уже был.
        val lamport = maxOf(current.lamport, merged.maxOfOrNull { it.rev } ?: 0L)
        commit(current.copy(items = merged, lamport = lamport, seen = maxOf(current.seen, generation)))
    }

    /** Что отправлять наверх. */
    fun outgoing(): List<Annotation> = _state.value.items

    private fun commit(next: AnnotationState) {
        // Переполненное или недоступное хранилище не должно ронять чтение:
        // отметка останется в памяти сессии, а следующая запись попробует
        // снова.
        runCatching {
            store.save(
                pathOf(next.bookId),
                json.encodeToString(
                    Stored.serializer(),
                    Stored(items = next.items, lamport = next.lamport, seen = next.seen),
                ),
            )
        }
        _state.value = next
    }

    private companion object {
        const val MAX_QUOTE = 4_000
        const val MAX_NOTE = 8_000
        fun pathOf(bookId: String) = "notes_$bookId.json"
    }
}

/** Отметки открытой книги. */
data class AnnotationState(
    val bookId: String = "",
    val items: List<Annotation> = emptyList(),
    /** Счётчик Лампорта книги на этом устройстве. */
    val lamport: Long = 0,
    /** Поколение серверного снимка, долговечно сохранённое здесь. */
    val seen: Long = 0,
)

@Serializable
private data class Stored(
    val version: Int = 1,
    val items: List<Annotation> = emptyList(),
    val lamport: Long = 0,
    val seen: Long = 0,
)

/** Номер отметки: время плюс случайный хвост, чтобы два устройства не совпали. */
private fun randomId(): String {
    val stamp = currentTimeMillis().toString(36)
    val tail = (1..8).map { ALPHABET.random() }.joinToString("")
    return "$stamp-$tail"
}

private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
