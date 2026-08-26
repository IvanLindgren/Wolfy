package com.wolfy.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.CatalogueBook
import com.wolfy.data.CatalogueResult
import com.wolfy.data.RemoteBookResult
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.Library
import com.wolfy.data.library.Card
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.Shelf
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.WolfyCore
import com.wolfy.data.OcrResult
import com.wolfy.platform.PickedBook
import com.wolfy.platform.PickedCover
import com.wolfy.platform.PickedPhoto
import com.wolfy.platform.decodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    /** Все карточки всех колод: экраны берут отсюда счётчики и списки слов. */
    val cards: List<Card> = emptyList(),
    /** Счётчики колод заранее, чтобы плитки не фильтровали весь список карт. */
    val deckCounts: Map<String, Int> = emptyMap(),
    val shelves: List<Shelf> = emptyList(),
    /** Книга, к которой стоит вернуться, или `null`, если открытых нет. */
    val continueReading: LibraryBook? = null,
    /** Сообщение читателю: книга не открылась, файл не скопировался. */
    val message: String? = null,
    /** Идёт распознавание снимка страницы. */
    val recognizing: Boolean = false,
    /**
     * Номер смены обложек.
     *
     * Обложки живут вне ядра, и библиотека о их смене не знает; экрану нужен
     * повод перечитать картинки, и этот счётчик им служит.
     */
    val coversVersion: Int = 0,
) {
    /** Колода книги. */
    fun deck(bookId: String): List<Card> = cards.filter { it.bookId == bookId && !it.deleted }

    /** Сколько слов книги лежит в колоде. */
    fun deckSize(bookId: String): Int = deckCounts[bookId] ?: 0
}

/**
 * Что показывает каталог Открытой библиотеки.
 *
 * Каталог живёт в той же модели, что и библиотека: найденная книга после
 * скачивания запоминается по номеру работы, чтобы повторное «скачать» вернуло
 * уже имеющуюся книгу, а не завело вторую.
 */
@Immutable
data class CatalogUiState(
    val query: String = "",
    val searching: Boolean = false,
    /** Что нашлось в последний поиск. Пусто до первого поиска и при неудаче. */
    val results: List<CatalogueBook> = emptyList(),
    /** Поиск уже выполнялся: отличаем «ничего не искали» от «ничего не нашлось». */
    val searched: Boolean = false,
    /** Работы, чьи файлы качаются прямо сейчас. */
    val downloading: Set<String> = emptySet(),
    /** Скачанные: номер работы → книга в библиотеке. */
    val downloaded: Map<String, LibraryBook> = emptyMap(),
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
    private val api: WolfyApi,
    /** Хранилище нужно обложкам: они живут вне библиотеки ядра. */
    private val store: com.wolfy.data.library.LibraryStore,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val recognizing = MutableStateFlow(false)
    private val coversVersion = MutableStateFlow(0)

    /** Книга, только что собранная из снимка: оболочка её откроет. */
    private val _recognized = MutableSharedFlow<LibraryBook>(extraBufferCapacity = 1)
    val recognized: SharedFlow<LibraryBook> = _recognized

    /** Книга, только что скачанная из каталога: оболочка её откроет. */
    private val _addedFromCatalog = MutableSharedFlow<LibraryBook>(extraBufferCapacity = 1)
    val addedFromCatalog: SharedFlow<LibraryBook> = _addedFromCatalog

    val state: StateFlow<LibraryUiState> = combine(
        library.state,
        message,
        recognizing,
        coversVersion,
    ) { library, message, recognizing, covers ->
        LibraryUiState(
            // Недавно открытые впереди, а никогда не открытые — по дате
            // добавления. Алфавит здесь был бы честнее, но библиотеку читают
            // не с начала: сверху должно лежать то, к чему возвращаются.
            books = library.visible.sortedWith(
                compareByDescending<LibraryBook> { it.progress.openedAt }
                    .thenByDescending { it.addedAt },
            ),
            cards = library.cards.filterNot { it.deleted },
            deckCounts = library.cards
                .asSequence()
                .filterNot { it.deleted }
                .groupingBy { it.bookId }
                .eachCount(),
            shelves = library.shelves,
            continueReading = library.visible
                .filter { it.started && !it.finished && it.readable }
                .maxByOrNull { it.progress.openedAt },
            message = message,
            recognizing = recognizing,
            coversVersion = covers,
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

    /**
     * Распознаёт снимок страницы и складывает его в книгу снимков.
     *
     * Единственное место приложения, где приходится ждать: модель смотрит на
     * картинку несколько секунд. Поэтому ожидание показывается явно — молча
     * замерший экран читатель понимает как поломку.
     */
    fun recognize(photo: PickedPhoto) {
        viewModelScope.launch {
            message.value = null
            recognizing.value = true
            try {
                when (val result = api.recognize(photo)) {
                    is OcrResult.Failed -> message.value = result.message
                    is OcrResult.Ready -> {
                        val book = library.appendSnapshot(result.text)
                        val described = withContext(Dispatchers.Default) { describe(book.path) }
                        library.describe(book.id, book.title, null, described.chapters)
                        library.book(book.id)?.let { _recognized.tryEmit(it) }
                    }
                }
            } catch (e: CoreException) {
                message.value = "Страница распозналась, но не открылась: ${e.message}"
            } finally {
                recognizing.value = false
            }
        }
    }

    fun remove(id: String) {
        library.remove(id)
    }

    fun addShelf(name: String) {
        if (name.isNotBlank()) library.addShelf(name)
    }

    fun removeShelf(name: String) {
        library.removeShelf(name)
    }

    fun moveToShelf(bookId: String, shelf: String?) {
        library.moveToShelf(bookId, shelf)
    }

    /**
     * Привязывает файл к книге, приехавшей по синхронизации.
     *
     * Сервер знает, что читатель на четвёртой главе «Гэтсби», но файла у него
     * нет — и не будет. На втором устройстве книгу надо показать пальцем.
     */
    fun attachFile(bookId: String, picked: PickedBook) {
        viewModelScope.launch {
            message.value = null
            try {
                library.attachFile(bookId, picked.path, picked.name)
                val book = library.book(bookId) ?: return@launch
                val described = withContext(Dispatchers.Default) { describe(book.path) }
                library.describe(bookId, described.title ?: book.title, described.author, described.chapters)
            } catch (e: CoreException) {
                message.value = "Не получилось открыть файл: ${e.message}"
            }
        }
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

    // --- обложки ---

    /** Декодированные картинки по книгам; ключ — путь, чтобы видеть смену. */
    // `null to null` — проверили: своей обложки нет. Такой negative cache
    // не запускает бесконечный I/O-цикл у плитки без картинки.
    private val coverCache = mutableMapOf<String, Pair<String?, ImageBitmap?>>()
    private val coverJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Своя обложка книги, готовая к показу.
     *
     * `null` — обложка ещё грузится или её нет. Метод не ходит на диск: его
     * вызывает Composition, где даже `listFiles()` уже заметный jank.
     */
    fun coverFor(bookId: String): ImageBitmap? = coverCache[bookId]?.second

    /** Запрашивается только видимой плиткой; I/O и decode происходят в фоне. */
    fun requestCover(bookId: String) {
        if (coverCache.containsKey(bookId)) return
        if (coverJobs.containsKey(bookId)) return
        coverJobs[bookId] = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val path = store.findCover(bookId)
                val bitmap = path?.let { file ->
                    store.readBinary(file)?.let { bytes -> runCatching { decodeImage(bytes) }.getOrNull() }
                }
                path to bitmap
            }
            coverJobs.remove(bookId)
            val (path, bitmap) = loaded
            coverCache[bookId] = path to bitmap
            // StateFlow даёт Composition новый кадр только после готового
            // bitmap; никаких чтений/декодирования по пути Composable.
            coversVersion.value += 1
        }
    }

    /** Ставит книге обложку из галереи. `null` — картинка не подошла. */
    fun setCover(bookId: String, picked: PickedCover?) {
        if (picked == null) {
            message.value = "Картинка не подошла: нужен файл png, jpg или webp до 24 МБ."
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.writeCover(bookId, picked.extension, picked.bytes) }
            }.onSuccess {
                coverJobs.remove(bookId)?.cancel()
                coverCache.remove(bookId)
                requestCover(bookId)
            }.onFailure { message.value = "Обложку не получилось сохранить: ${it.message}" }
        }
    }

    /** Убирает свою обложку: книга возвращается к набранной. */
    fun clearCover(bookId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteCover(bookId) }
            coverJobs.remove(bookId)?.cancel()
            coverCache[bookId] = null to null
            coversVersion.value += 1
        }
    }

    // --- каталог Открытой библиотеки ---

    private val catalogState = MutableStateFlow(CatalogUiState())
    val catalog: StateFlow<CatalogUiState> = catalogState

    private fun changeCatalog(change: (CatalogUiState) -> CatalogUiState) {
        catalogState.value = change(catalogState.value)
    }

    /** Печатает в поле поиска без похода в сеть. */
    fun typeQuery(query: String) = changeCatalog { it.copy(query = query) }

    fun searchCatalogue(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return

        viewModelScope.launch {
            changeCatalog { it.copy(searching = true, query = clean, message = null) }
            when (val result = api.searchCatalogue(clean)) {
                is CatalogueResult.Ready -> changeCatalog {
                    it.copy(searching = false, searched = true, results = result.books)
                }
                is CatalogueResult.Failed -> changeCatalog {
                    it.copy(
                        searching = false,
                        searched = true,
                        results = emptyList(),
                        message = result.message,
                    )
                }
            }
        }
    }

    /**
     * Скачивает найденную книгу и заводит её в библиотеке.
     *
     * Источник записывается номером работы каталога: одна и та же книга,
     * скачанная на телефоне и на компьютере, узнаётся как одна — тем же
     * способом, каким узнаются книги из ленты «Открытий».
     */
    fun downloadCatalogue(item: CatalogueBook) {
        if (item.id in catalogState.value.downloading) return
        if (catalogState.value.downloaded.containsKey(item.id)) return

        viewModelScope.launch {
            message.value = null
            changeCatalog { it.copy(downloading = it.downloading + item.id, message = null) }

            when (val result = api.downloadCatalogueBook(item)) {
                is RemoteBookResult.Failed -> changeCatalog {
                    it.copy(downloading = it.downloading - item.id, message = result.message)
                }

                is RemoteBookResult.Ready -> {
                    val book = withContext(Dispatchers.IO) {
                        val added = library.addDownloaded(
                            bytes = result.bytes,
                            fileName = result.fileName,
                            title = item.title,
                            author = item.author.takeIf(String::isNotBlank),
                            sourceKey = "openlibrary:${item.id}",
                        )
                        runCatching {
                            val described = withContext(Dispatchers.Default) { describe(added.path) }
                            library.describe(
                                id = added.id,
                                title = described.title ?: added.title,
                                author = described.author
                                    ?: item.author.takeIf(String::isNotBlank),
                                chapters = described.chapters,
                            )
                        }
                        library.book(added.id) ?: added
                    }
                    changeCatalog {
                        it.copy(
                            downloading = it.downloading - item.id,
                            downloaded = it.downloaded + (item.id to book),
                            message = "«${item.title}» добавлена в библиотеку.",
                        )
                    }
                    _addedFromCatalog.tryEmit(book)
                }
            }
        }
    }
}
