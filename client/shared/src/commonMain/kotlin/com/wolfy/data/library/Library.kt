package com.wolfy.data.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Библиотека пользователя.
 *
 * Держит состояние в памяти и пишет его на диск после каждого изменения.
 * Писать целиком при каждом касании выглядит расточительно, но библиотека это
 * десятки килобайт, а альтернатива — отложенная запись, которая теряет
 * прогресс при закрытии приложения свайпом. Прочитанная страница обязана
 * пережить закрытие приложения, и это дороже пары миллисекунд.
 *
 * Изменения раздаются потоком: библиотеку одновременно показывает список книг,
 * обновляет читалка и отправляет синхронизация, и держать их в согласии
 * подписками проще, чем звать друг друга напрямую.
 */
@OptIn(ExperimentalUuidApi::class)
class Library(
    private val store: LibraryStore,
    private val now: () -> Long = { currentTimeMillis() },
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    val books: List<LibraryBook> get() = _state.value.visible

    /**
     * Книга, к которой стоит вернуться.
     *
     * Последняя открытая и не дочитанная. Дочитанную предлагать бессмысленно —
     * читатель уже закрыл её, — а не начатую предлагать рано: «книга дня» это
     * продолжение, а не выбор. Книга без файла тоже не годится: предложить
     * продолжить и не суметь открыть хуже, чем не предлагать.
     */
    fun continueReading(): LibraryBook? = books
        .filter { it.started && !it.finished && it.readable }
        .maxByOrNull { it.progress.openedAt }

    fun book(id: String): LibraryBook? = _state.value.books.firstOrNull { it.id == id }

    fun deck(bookId: String): List<Card> = _state.value.deck(bookId)

    /**
     * Добавляет книгу в библиотеку.
     *
     * Файл копируется в хранилище приложения: исходник может исчезнуть, а
     * книга, которую читают, исчезать не должна.
     *
     * Если книга с таким же содержимым уже есть — например, приехала
     * синхронизацией с другого устройства, — она не заводится второй раз, а
     * получает файл. Иначе библиотека, синхронизированная между телефоном и
     * компьютером, удваивалась бы при первом же переносе файлов.
     */
    fun add(
        sourcePath: String,
        fileName: String,
        title: String,
        author: String?,
    ): LibraryBook {
        val fingerprint = store.fingerprint(sourcePath)
        val known = _state.value.books.firstOrNull {
            fingerprint.isNotEmpty() && it.sourceKey == fingerprint && !it.deleted
        }
        if (known != null && !known.readable) {
            val path = store.importBook(sourcePath, fileName)
            edit(known.id) { it.copy(path = path) }
            return book(known.id) ?: known
        }
        if (known != null) return known

        val path = store.importBook(sourcePath, fileName)
        val book = LibraryBook(
            id = newId(),
            path = path,
            title = title,
            author = author,
            format = fileName.substringAfterLast('.', "").lowercase(),
            sourceKey = fingerprint,
            addedAt = now(),
        )
        update { it.copy(books = it.books + book) }
        return book
    }

    /**
     * Привязывает файл к книге, приехавшей по синхронизации.
     *
     * Сервер знает, что вы читаете «Гэтсби» и на какой вы главе, но самого
     * файла у него нет. Поэтому на втором устройстве книга сначала появляется
     * без файла, а читатель показывает, где он его держит.
     */
    fun attachFile(id: String, sourcePath: String, fileName: String) {
        val path = store.importBook(sourcePath, fileName)
        val fingerprint = store.fingerprint(sourcePath)
        edit(id) { book ->
            book.copy(
                path = path,
                sourceKey = book.sourceKey.ifEmpty { fingerprint },
                format = book.format.ifEmpty { fileName.substringAfterLast('.', "").lowercase() },
            )
        }
    }

    /**
     * Добавляет распознанную страницу.
     *
     * Все снимки складываются в одну книгу, а не заводят по книге на страницу.
     * Фотографируют обычно подряд — разворот за разворотом, — и библиотека,
     * заполненная десятком книг по абзацу каждая, перестаёт быть библиотекой.
     *
     * Файл переписывается целиком: страница снимка это килобайты, и городить
     * дозапись ради них незачем.
     */
    fun appendSnapshot(text: String): LibraryBook {
        val page = text.trim()
        val existing = _state.value.books.firstOrNull { it.title == SNAPSHOTS && !it.deleted }

        val whole = when {
            existing == null || !existing.readable -> page
            else -> {
                val before = store.readText(existing.path).trimEnd()
                // Пустая строка между страницами: для ядра граница абзаца это
                // и граница предложения, и без неё последняя фраза страницы
                // слиплась бы с первой фразой следующей.
                if (before.isEmpty()) page else before + PAGE_BREAK + page
            }
        }

        val path = store.writeText(SNAPSHOTS_FILE, whole)

        if (existing != null) {
            edit(existing.id) { it.copy(path = path, sourceKey = "") }
            return book(existing.id) ?: existing
        }

        val book = LibraryBook(
            id = newId(),
            path = path,
            title = SNAPSHOTS,
            format = "txt",
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

    /**
     * Кладёт слово в колоду книги.
     *
     * Повторное сохранение того же слова из той же книги ничего не меняет:
     * колода не должна забиваться одним словом в разных формах.
     */
    fun saveWord(
        bookId: String,
        surface: String,
        lemma: String,
        translation: String = "",
        context: String = "",
        pos: String = "",
        cefr: String = "",
    ): Card {
        val existing = _state.value.cards.firstOrNull { it.bookId == bookId && it.lemma == lemma }
        if (existing != null) {
            // Удалённую карточку возвращаем той же записью: новая запись
            // приехала бы на второе устройство рядом со старой.
            if (existing.deleted) {
                editCard(existing.id) { it.copy(deleted = false) }
                return existing.copy(deleted = false)
            }
            return existing
        }

        val card = Card(
            id = newId(),
            bookId = bookId,
            surface = surface,
            lemma = lemma,
            translation = translation,
            context = context,
            pos = pos,
            cefr = cefr,
            dueAt = now(),
            addedAt = now(),
        )
        update { it.copy(cards = it.cards + card) }
        return card
    }

    /**
     * Кладёт в колоду фразу целиком.
     *
     * Фраза хранится той же карточкой, что слово: у неё те же прочность, срок
     * и серия, и заводить ради неё вторую сущность значит писать вторую
     * синхронизацию, вторую миграцию и второй экран. Отличает её `kind`.
     *
     * Ключ — сама фраза: одно и то же предложение из одной книги сохраняется
     * один раз, сколько бы раз читатель по нему ни нажал.
     */
    fun savePhrase(bookId: String, sentence: String, translation: String): Card? {
        val text = sentence.trim()
        if (text.isEmpty()) return null

        val existing = _state.value.cards.firstOrNull {
            it.bookId == bookId && it.kind == PHRASE && it.surface == text
        }
        if (existing != null) {
            if (existing.deleted) {
                editCard(existing.id) { it.copy(deleted = false) }
                return existing.copy(deleted = false)
            }
            return existing
        }

        val card = Card(
            id = newId(),
            bookId = bookId,
            kind = PHRASE,
            surface = text,
            // Начальная форма у фразы — она сама: искать её не по чему, а
            // пустое поле сломало бы всех, кто по нему показывает карточку.
            lemma = text,
            translation = translation.trim(),
            context = text,
            dueAt = now(),
            addedAt = now(),
        )
        update { it.copy(cards = it.cards + card) }
        return card
    }

    /**
     * Карточка правила — заводится в тот момент, когда правило впервые
     * спросили.
     *
     * Заранее их не создают: правил под шесть десятков, и завести все разом
     * значило бы отправить на сервер колоду, которой читатель не заказывал, и
     * показать ему шесть десятков «к повторению» в первый же день.
     */
    fun ruleCard(rule: String, title: String): Card {
        val existing = _state.value.cards.firstOrNull { it.kind == RULE && it.lemma == rule }
        if (existing != null) {
            if (existing.deleted) {
                editCard(existing.id) { it.copy(deleted = false) }
                return existing.copy(deleted = false)
            }
            return existing
        }

        val card = Card(
            id = newId(),
            kind = RULE,
            surface = title,
            lemma = rule,
            dueAt = now(),
            addedAt = now(),
        )
        update { it.copy(cards = it.cards + card) }
        return card
    }

    /**
     * Меняет карточку после ответа.
     *
     * Само расписание живёт в [com.wolfy.srs.Scheduler] и о библиотеке не
     * знает — оно чистое и потому проверяемое. Библиотека же не знает о
     * расписании: её дело — записать то, что посчитали, и разослать
     * подписчикам.
     */
    fun updateCard(id: String, change: (Card) -> Card) {
        editCard(id, change)
    }

    fun removeWord(bookId: String, lemma: String) {
        val card = _state.value.cards
            .firstOrNull { it.bookId == bookId && it.lemma == lemma && !it.deleted }
            ?: return
        editCard(card.id) { it.copy(deleted = true) }
    }

    fun moveToShelf(id: String, shelf: String?) {
        edit(id) { it.copy(shelf = shelf) }
        // Полка, на которую что-то поставили, обязана существовать в списке:
        // иначе она исчезнет, как только с неё снимут последнюю книгу.
        if (shelf != null) addShelf(shelf)
    }

    /**
     * Убирает книгу.
     *
     * Файл стирается сразу, а запись остаётся с пометкой: файл больше не
     * нужен никому, а пометка обязана доехать до второго устройства, иначе
     * книга там воскреснет.
     */
    fun remove(id: String) {
        val book = book(id) ?: return
        if (book.readable) store.deleteBook(book.path)
        update { current ->
            current.copy(
                books = current.books.map {
                    if (it.id == id) it.copy(deleted = true, path = "", dirty = true) else it
                },
                // Карточки книги уходят вместе с ней: колода без книги
                // бессмысленна, а на сервере у карточки внешний ключ на книгу.
                cards = current.cards.map {
                    if (it.bookId == id && !it.deleted) it.copy(deleted = true, dirty = true) else it
                },
            )
        }
    }

    fun addShelf(name: String): Shelf {
        val trimmed = name.trim()
        val existing = _state.value.shelves.firstOrNull { it.name == trimmed }
        if (existing != null) return existing

        val shelf = Shelf(name = trimmed, createdAt = now())
        update { it.copy(shelves = it.shelves + shelf) }
        return shelf
    }

    fun removeShelf(name: String) {
        update { current ->
            current.copy(
                shelves = current.shelves.filterNot { it.name == name },
                // Книги с удалённой полки не пропадают, а возвращаются к
                // неразобранным: полка это место, а не свойство книги.
                books = current.books.map { book ->
                    if (book.shelf == name) book.copy(shelf = null, dirty = true) else book
                },
            )
        }
    }

    // --- синхронизация ---

    /** Записи, изменённые на этом устройстве и ещё не отправленные. */
    fun pending(): Pair<List<LibraryBook>, List<Card>> =
        _state.value.books.filter { it.dirty } to _state.value.cards.filter { it.dirty }

    /**
     * Снимок отправки: что ушло на сервер и в каком состоянии была библиотека.
     *
     * Нужен, чтобы отличить эхо собственной отправки от чужого изменения. Пока
     * запрос идёт по сети, читатель продолжает читать, и запись успевает
     * измениться ещё раз — принять после этого своё же старое эхо значит
     * потерять то, что он только что сделал.
     */
    data class Sent(
        val revision: Long,
        val books: Set<String>,
        val cards: Set<String>,
    )

    /** Что отправляем и с какого состояния. */
    fun snapshot(): Sent {
        val state = _state.value
        return Sent(
            revision = state.revision,
            books = state.books.filter { it.dirty }.map { it.id }.toSet(),
            cards = state.cards.filter { it.dirty }.map { it.id }.toSet(),
        )
    }

    /**
     * Принимает ответ сервера.
     *
     * Всё, что было отправлено, приезжает назад с присвоенной ревизией — по
     * ней записи и снимаются с отправки. Свежее чужое просто заменяет местное:
     * побеждает последний записавший, и решение об этом принято на сервере.
     *
     * Путь к файлу — единственное, что не приходит извне и не затирается: он у
     * каждого устройства свой, а у второго его может не быть вовсе.
     */
    fun applyServer(
        cursor: Long,
        books: List<LibraryBook>,
        cards: List<Card>,
        sent: Sent = Sent(_state.value.revision, emptySet(), emptySet()),
    ) {
        update { current ->
            // Изменилась ли библиотека, пока шёл запрос. Если да, ответ сервера
            // старше местных правок, и принимать его на них нельзя.
            val quiet = current.revision == sent.revision

            val byId = current.books.associateBy { it.id }.toMutableMap()
            for (incoming in books) {
                val local = byId[incoming.id]
                if (local != null && local.dirty && !(quiet && local.id in sent.books)) {
                    // Местная правка новее ответа: оставляем её ждать отправки.
                    continue
                }
                byId[incoming.id] = incoming.copy(
                    path = local?.path.orEmpty(),
                    dirty = false,
                )
            }

            val cardsById = current.cards.associateBy { it.id }.toMutableMap()
            for (incoming in cards) {
                val local = cardsById[incoming.id]
                if (local != null && local.dirty && !(quiet && local.id in sent.cards)) {
                    continue
                }
                cardsById[incoming.id] = incoming.copy(dirty = false)
            }

            // Полки восстанавливаются из книг: своей таблицы у них нет, и
            // приехавшая с другого устройства «Классика» иначе осталась бы
            // без строки в списке полок.
            val known = current.shelves.map { it.name }.toSet()
            val arrived = byId.values.mapNotNull { it.shelf }.filter { it !in known }.distinct()

            current.copy(
                books = byId.values.sortedBy { it.addedAt },
                cards = cardsById.values.sortedBy { it.addedAt },
                shelves = current.shelves + arrived.map { Shelf(name = it, createdAt = now()) },
                cursor = cursor,
            )
        }
    }

    // --- внутреннее ---

    private fun edit(id: String, change: (LibraryBook) -> LibraryBook) {
        update { current ->
            current.copy(
                books = current.books.map {
                    if (it.id == id) change(it).copy(dirty = true) else it
                },
            )
        }
    }

    private fun editCard(id: String, change: (Card) -> Card) {
        update { current ->
            current.copy(
                cards = current.cards.map {
                    if (it.id == id) change(it).copy(dirty = true) else it
                },
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
            migrate(json.decodeFromString(saved))
        } catch (e: Exception) {
            // Битый файл библиотеки не должен мешать открыть приложение.
            // Книги при этом остаются на диске, и их можно добавить заново —
            // а вот падение на старте не оставило бы пользователю ничего.
            LibraryState()
        }
    }

    /**
     * Приводит прочитанное состояние к нынешнему виду.
     *
     * Пока нужна одна поправка: до синхронизации номера книг придумывались как
     * попало, а на сервере под них колонка uuid. Книга со старым номером
     * получает новый — вместе со своими карточками, иначе колода потеряет
     * хозяина.
     */
    private fun migrate(state: LibraryState): LibraryState {
        val renamed = mutableMapOf<String, String>()
        val books = state.books.map { book ->
            if (looksLikeUuid(book.id)) {
                book
            } else {
                val fresh = newId()
                renamed[book.id] = fresh
                book.copy(id = fresh, rev = 0, dirty = true)
            }
        }
        if (renamed.isEmpty()) return state

        val cards = state.cards.map { card ->
            val owner = renamed[card.bookId] ?: return@map card
            card.copy(bookId = owner, rev = 0, dirty = true)
        }
        return state.copy(books = books, cards = cards)
    }

    private fun looksLikeUuid(id: String): Boolean =
        id.length == 36 && id.count { it == '-' } == 4

    private companion object {
        const val PHRASE = "phrase"
        const val RULE = "rule"

        /** Имя записи в хранилище. */
        const val RECORD = "library"

        /** Книга, в которую складываются распознанные страницы. */
        const val SNAPSHOTS = "Снимки со страниц"
        const val SNAPSHOTS_FILE = "snapshots.txt"

        /**
         * Разделитель между снятыми страницами.
         *
         * Пустая строка: для ядра граница абзаца это и граница предложения,
         * и без неё последняя фраза страницы слиплась бы с первой фразой
         * следующей — а вместе с ней уехала бы в контекст перевода.
         */
        const val PAGE_BREAK = "\n\n"
    }
}

/** Текущее время в миллисекундах — платформы берут его по-разному. */
expect fun currentTimeMillis(): Long
