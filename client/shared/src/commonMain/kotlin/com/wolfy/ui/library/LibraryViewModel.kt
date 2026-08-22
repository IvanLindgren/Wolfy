package com.wolfy.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.Shelf
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.WolfyCore
import com.wolfy.platform.PickedBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что показывает экран библиотеки. */
@Immutable
data class LibraryUiState(
    val books: List<LibraryBook> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    /** Книга, к которой стоит вернуться, или `null`, если открытых нет. */
    val continueReading: LibraryBook? = null,
    /** Сообщение читателю: книга не открылась, файл не скопировался. */
    val message: String? = null,
)

/**
 * Библиотека: список книг, добавление файлов, полки.
 *
 * Хранилищем занимается [Library], а здесь живёт только то, что относится к
 * экрану: порядок книг, сообщения об ошибках и разбор только что добавленного
 * файла.
 */
class LibraryViewModel(
    private val library: Library,
    private val core: WolfyCore,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<LibraryUiState> = combine(library.state, message) { library, message ->
        LibraryUiState(
            // Недавно открытые впереди, а никогда не открытые — по дате
            // добавления. Алфавит здесь был бы честнее, но библиотеку читают
            // не с начала: сверху должно лежать то, к чему возвращаются.
            books = library.books.sortedWith(
                compareByDescending<LibraryBook> { it.progress.openedAt }
                    .thenByDescending { it.addedAt },
            ),
            shelves = library.shelves,
            continueReading = library.books
                .filter { it.started && !it.finished }
                .maxByOrNull { it.progress.openedAt },
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /**
     * Добавляет выбранный файл.
     *
     * Порядок здесь важнее, чем кажется: файл сначала копируется в хранилище и
     * только потом разбирается. У обычного текста названия внутри нет, и ядро
     * берёт его из имени файла — а до копирования файл ещё лежит во временном
     * каталоге под служебным именем вроде «import-book». Разбери его раньше — и
     * в библиотеке появится книга с этим именем на обложке.
     *
     * Разбор нужен не только ради названия: заодно выясняется, читается ли файл
     * вообще, и сказать об этом лучше сейчас, чем при попытке начать чтение.
     */
    fun import(picked: PickedBook) {
        viewModelScope.launch {
            message.value = null
            val book = try {
                library.add(
                    sourcePath = picked.path,
                    fileName = picked.name,
                    title = picked.name.substringBeforeLast('.'),
                    author = null,
                )
            } catch (e: Exception) {
                message.value = "Не получилось добавить книгу: ${e.message}"
                return@launch
            }

            try {
                val described = withContext(Dispatchers.Default) { describe(book.path) }
                library.describe(
                    id = book.id,
                    title = described.title ?: book.title,
                    author = described.author,
                    chapters = described.chapters,
                )
            } catch (e: CoreException) {
                // Книга уже в библиотеке, но не открывается. Убираем её обратно:
                // плитка, которая не открывается, хуже, чем её отсутствие.
                library.remove(book.id)
                message.value = "Не получилось открыть книгу: ${e.message}"
            }
        }
    }

    fun remove(id: String) {
        library.remove(id)
    }

    fun addShelf(name: String) {
        if (name.isNotBlank()) library.addShelf(name.trim())
    }

    fun removeShelf(id: String) {
        library.removeShelf(id)
    }

    fun moveToShelf(bookId: String, shelfId: String?) {
        library.moveToShelf(bookId, shelfId)
    }

    /** Название, автор и число глав из самого файла. */
    private fun describe(path: String): Described {
        val opened = core.openBook(path)
        return try {
            Described(
                title = opened.info.title?.takeIf { it.isNotBlank() },
                author = opened.info.author?.takeIf { it.isNotBlank() },
                chapters = opened.info.chapters.size,
            )
        } finally {
            // Номер книги обязателен к закрытию: пока он открыт, ядро держит
            // файл, а импорт может повторяться сколько угодно раз подряд.
            core.closeBook(opened.handle)
        }
    }

    private data class Described(
        val title: String?,
        val author: String?,
        val chapters: Int,
    )
}
