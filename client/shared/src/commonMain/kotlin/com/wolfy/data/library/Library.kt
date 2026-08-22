package com.wolfy.data.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Библиотека пользователя.
 *
 * Держит список книг в памяти и пишет его на диск после каждого изменения.
 * Писать целиком при каждом касании выглядит расточительно, но библиотека это
 * десятки килобайт, а альтернатива — отложенная запись, которая теряет
 * прогресс при закрытии приложения свайпом. Прочитанная страница обязана
 * пережить закрытие приложения, и это дороже пары миллисекунд.
 *
 * Изменения раздаются потоком: библиотеку одновременно показывает список книг
 * и обновляет читалка, и держать их в согласии подписками проще, чем звать
 * друг друга напрямую.
 */
class Library(
    private val store: LibraryStore,
    private val now: () -> Long = { currentTimeMillis() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    val books: List<LibraryBook> get() = _state.value.books

    /**
     * Книга, к которой стоит вернуться.
     *
     * Последняя открытая и не дочитанная. Дочитанную предлагать бессмысленно —
     * читатель уже закрыл её, — а не начатую предлагать рано: «книга дня» это
     * продолжение, а не выбор.
     */
    fun continueReading(): LibraryBook? = _state.value.books
        .filter { it.started && !it.finished }
        .maxByOrNull { it.progress.openedAt }

    fun book(id: String): LibraryBook? = _state.value.books.firstOrNull { it.id == id }

    /**
     * Добавляет книгу в библиотеку.
     *
     * Файл копируется в хранилище приложения: исходник может исчезнуть, а
     * книга, которую читают, исчезать не должна.
     */
    fun add(sourcePath: String, fileName: String, title: String, author: String?): LibraryBook {
        val path = store.importBook(sourcePath, fileName)
        val book = LibraryBook(
            id = newId(),
            path = path,
            title = title,
            author = author,
            addedAt = now(),
        )
        update { it.copy(books = it.books + book) }
        return book
    }

    /** Запоминает, что ядро нашло в книге при открытии. */
    fun describe(id: String, title: String, author: String?, chapters: Int) {
        edit(id) { book ->
            book.copy(
                // Название из файла точнее того, что видно по имени файла, но
                // пустое название хуже имени файла — оставляем прежнее.
                title = title.ifBlank { book.title },
                author = author ?: book.author,
                chapters = chapters,
            )
        }
    }

    /** Запоминает, где читатель остановился. */
    fun rememberProgress(id: String, chapter: Int, withinChapter: Float) {
        edit(id) { book ->
            book.copy(
                progress = Progress(
                    chapter = chapter,
                    withinChapter = withinChapter.coerceIn(0f, 1f),
                    openedAt = now(),
                ),
            )
        }
    }

    /** Кладёт слово в колоду книги. Повторное сохранение ничего не меняет. */
    fun saveWord(id: String, lemma: String) {
        val book = book(id) ?: return
        if (lemma in book.deck) return
        edit(id) { it.copy(deck = it.deck + lemma) }
    }

    fun removeWord(id: String, lemma: String) {
        edit(id) { it.copy(deck = it.deck - lemma) }
    }

    fun moveToShelf(id: String, shelf: String?) {
        edit(id) { it.copy(shelf = shelf) }
    }

    /** Убирает книгу вместе с файлом. */
    fun remove(id: String) {
        val book = book(id) ?: return
        store.deleteBook(book.path)
        update { it.copy(books = it.books.filterNot { candidate -> candidate.id == id }) }
    }

    fun addShelf(name: String): Shelf {
        val shelf = Shelf(id = newId(), name = name, createdAt = now())
        update { it.copy(shelves = it.shelves + shelf) }
        return shelf
    }

    fun removeShelf(id: String) {
        update { current ->
            current.copy(
                shelves = current.shelves.filterNot { it.id == id },
                // Книги с удалённой полки не пропадают, а возвращаются к
                // неразобранным: полка это место, а не свойство книги.
                books = current.books.map { book ->
                    if (book.shelf == id) book.copy(shelf = null) else book
                },
            )
        }
    }

    private fun edit(id: String, change: (LibraryBook) -> LibraryBook) {
        update { current ->
            current.copy(
                books = current.books.map { if (it.id == id) change(it) else it },
            )
        }
    }

    private fun update(change: (LibraryState) -> LibraryState) {
        val next = change(_state.value)
        val stamped = next.copy(revision = next.revision + 1)
        _state.value = stamped
        store.save(RECORD, json.encodeToString(stamped))
    }

    private fun read(): LibraryState {
        val saved = store.load(RECORD) ?: return LibraryState()
        return try {
            json.decodeFromString(saved)
        } catch (e: Exception) {
            // Битый файл библиотеки не должен мешать открыть приложение.
            // Книги при этом остаются на диске, и их можно добавить заново —
            // а вот падение на старте не оставило бы пользователю ничего.
            LibraryState()
        }
    }

    private fun newId(): String = "b" + now().toString(16) + "-" + (idCounter++).toString(16)

    private var idCounter = 0

    private companion object {
        /** Имя записи в хранилище. */
        const val RECORD = "library"
    }
}

/** Текущее время в миллисекундах — платформы берут его по-разному. */
expect fun currentTimeMillis(): Long
