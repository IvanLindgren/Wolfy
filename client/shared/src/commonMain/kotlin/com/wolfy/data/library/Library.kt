package com.wolfy.data.library

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Библиотека пользователя.
 *
 * Правила живут в ядре на Rust: что считать той же книгой, когда воскрешать
 * удалённую карточку, что тянется следом за удалением и как разрешать
 * столкновения при синхронизации. Здесь — доступ к файлам, поток изменений
 * для экранов и перевод вызовов в команды ядра.
 *
 * Так и должно быть. Два устройства одного читателя обязаны разрешать
 * столкновения одинаково, а две реализации этих правил расходятся тихо:
 * прочитанная глава просто исчезает на одном из них. Раньше расписание,
 * слияние и дедупликация были написаны здесь во второй раз — и одна ошибка в
 * них уже нашлась при переезде.
 *
 * Изменения раздаются потоком: библиотеку одновременно показывает список
 * книг, обновляет читалка и отправляет синхронизация, и держать их в согласии
 * подписками проще, чем звать друг друга напрямую.
 */
@OptIn(ExperimentalUuidApi::class)
class Library(
    private val session: CoreSession,
    private val store: LibraryStore,
    private val now: () -> Long = { currentTimeMillis() },
    private val newId: () -> String = { Uuid.random().toString() },
) {
    val state: StateFlow<LibraryState> get() = session.library

    val books: List<LibraryBook> get() = state.value.visible

    init {
        // До синхронизации номера книг придумывались как попало, а на сервере
        // под них колонка uuid. Раньше это делалось при чтении с диска; теперь
        // читает ядро, и позвать миграцию некому, кроме как отсюда.
        migrate()
    }

    /**
     * Книга, к которой стоит вернуться.
     *
     * Последняя открытая и не дочитанная. Правило целиком в ядре: дочитанную
     * предлагать бессмысленно, не начатую — рано, а книгу без файла нельзя
     * открыть.
     */
    fun continueReading(): LibraryBook? = ask(command("continueReading")).book

    fun book(id: String): LibraryBook? = state.value.books.firstOrNull { it.id == id }

    fun deck(bookId: String): List<Card> = state.value.deck(bookId)

    /**
     * Добавляет книгу в библиотеку.
     *
     * Файл копируется в хранилище приложения: исходник может исчезнуть, а
     * книга, которую читают, исчезать не должна.
     *
     * Заводить ли книгу заново, решает ядро по отпечатку содержимого — и
     * решает до копирования: книга, приехавшая синхронизацией с другого
     * устройства, получает файл, а не заводится второй раз, и копировать
     * файл дважды при этом не приходится.
     */
    fun add(
        sourcePath: String,
        fileName: String,
        title: String,
        author: String?,
    ): LibraryBook {
        val fingerprint = store.fingerprint(sourcePath)
        val plan = ask(command("planAdd") { put("fingerprint", fingerprint) })

        when (plan.plan) {
            "known" -> return book(plan.bookId.orEmpty()) ?: error("ядро назвало неизвестную книгу")
            "attach" -> {
                val id = plan.bookId.orEmpty()
                attachFile(id, sourcePath, fileName)
                return book(id) ?: error("ядро назвало неизвестную книгу")
            }
        }

        return addBook(
            LibraryBook(
                id = newId(),
                path = store.importBook(sourcePath, fileName),
                title = title,
                author = author,
                format = fileName.substringAfterLast('.', "").lowercase(),
                sourceKey = fingerprint,
                addedAt = now(),
            ),
        )
    }

    /** Добавляет EPUB, полученный из доверенного серверного каталога. */
    fun addDownloaded(
        bytes: ByteArray,
        fileName: String,
        title: String,
        author: String?,
        sourceKey: String,
    ): LibraryBook {
        val plan = ask(command("planAdd") { put("fingerprint", sourceKey) })
        plan.bookId?.let { known -> book(known)?.let { return it } }

        return addBook(
            LibraryBook(
                id = newId(),
                path = store.writeBook(fileName, bytes),
                title = title,
                author = author,
                format = "epub",
                sourceKey = sourceKey,
                addedAt = now(),
            ),
        )
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
        send(
            command("attachFile") {
                put("id", id)
                put("path", path)
                put("fingerprint", fingerprint)
            },
        )
    }

    /** Дописывает снятую страницу в книгу снимков. */
    fun appendSnapshot(text: String): LibraryBook {
        val existing = state.value.books.firstOrNull { it.title == SNAPSHOTS && !it.deleted }
        val before = if (existing != null && existing.readable) store.readText(existing.path) else ""

        // Как склеивать страницы, знает ядро: между ними обязана быть пустая
        // строка, иначе последняя фраза страницы слипнется с первой фразой
        // следующей — и уедет вместе с ней в контекст перевода.
        val whole = ask(
            command("appendedPage") {
                put("before", before)
                put("page", text)
            },
        ).text.orEmpty()

        val path = store.writeText(SNAPSHOTS_FILE, whole)

        if (existing != null) {
            send(
                command("attachFile") {
                    put("id", existing.id)
                    put("path", path)
                },
            )
            return book(existing.id) ?: existing
        }

        return addBook(
            LibraryBook(
                id = newId(),
                path = path,
                title = SNAPSHOTS,
                format = "txt",
                addedAt = now(),
            ),
        )
    }

    /** Запоминает, что ядро нашло в книге при открытии. */
    fun describe(id: String, title: String, author: String?, chapters: Int) {
        send(
            command("describe") {
                put("id", id)
                put("title", title)
                author?.let { put("author", it) }
                put("chapters", chapters)
            },
        )
    }

    /** Запоминает, где читатель остановился. */
    fun rememberProgress(id: String, chapter: Int, withinChapter: Float) {
        send(
            command("rememberProgress") {
                put("id", id)
                put("chapter", chapter)
                put("withinChapter", withinChapter)
                put("now", now())
            },
        )
    }

    /** Кладёт слово в колоду книги. */
    fun saveWord(
        bookId: String,
        surface: String,
        lemma: String,
        translation: String = "",
        context: String = "",
        pos: String = "",
        cefr: String = "",
    ): Card = send(
        command("saveWord") {
            put("bookId", bookId)
            put("surface", surface)
            put("lemma", lemma)
            put("translation", translation)
            put("context", context)
            put("pos", pos)
            put("cefr", cefr)
            put("id", newId())
            put("now", now())
        },
    ).card ?: error("ядро не вернуло карточку слова")

    /** Кладёт в колоду фразу целиком. `null` — фраза пустая. */
    fun savePhrase(bookId: String, sentence: String, translation: String): Card? = send(
        command("savePhrase") {
            put("bookId", bookId)
            put("sentence", sentence)
            put("translation", translation)
            put("id", newId())
            put("now", now())
        },
    ).card

    /** Карточка правила — заводится, когда правило впервые спросили. */
    fun ruleCard(rule: String, title: String): Card = send(
        command("ruleCard") {
            put("rule", rule)
            put("title", title)
            put("id", newId())
            put("now", now())
        },
    ).card ?: error("ядро не вернуло карточку правила")

    fun removeWord(bookId: String, lemma: String) {
        send(
            command("removeWord") {
                put("bookId", bookId)
                put("lemma", lemma)
            },
        )
    }

    fun moveToShelf(id: String, shelf: String?) {
        send(
            command("moveToShelf") {
                put("id", id)
                shelf?.let { put("shelf", it) }
                put("now", now())
            },
        )
    }

    /**
     * Убирает книгу.
     *
     * Файл стирается сразу, а запись остаётся с пометкой: файл больше не
     * нужен никому, а пометка обязана доехать до второго устройства, иначе
     * книга там воскреснет. Колода уходит вместе с книгой — это уже забота
     * ядра.
     */
    fun remove(id: String) {
        val book = book(id) ?: return
        if (book.readable) store.deleteBook(book.path)
        send(command("removeBook") { put("id", id) })
    }

    fun addShelf(name: String): Shelf = send(
        command("addShelf") {
            put("name", name)
            put("now", now())
        },
    ).shelf ?: error("ядро не вернуло полку")

    fun removeShelf(name: String) {
        send(command("removeShelf") { put("name", name) })
    }

    // --- синхронизация ---

    /** Записи, изменённые на этом устройстве и ещё не отправленные. */
    fun pending(): Pair<List<LibraryBook>, List<Card>> {
        val outcome = ask(command("pending"))
        return outcome.books.orEmpty() to outcome.cards.orEmpty()
    }

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
        val (books, cards) = pending()
        return Sent(
            revision = state.value.revision,
            books = books.map { it.id }.toSet(),
            cards = cards.map { it.id }.toSet(),
        )
    }

    /** Принимает ответ сервера. Все правила слияния — в ядре. */
    fun applyServer(
        cursor: Long,
        books: List<LibraryBook>,
        cards: List<Card>,
        sent: Sent = Sent(state.value.revision, emptySet(), emptySet()),
    ) {
        send(
            command("applyServer") {
                put("cursor", cursor)
                put("books", json.encodeToJsonElement(books))
                put("cards", json.encodeToJsonElement(cards))
                put("now", now())
                put(
                    "sent",
                    kotlinx.serialization.json.buildJsonObject {
                        put("revision", sent.revision)
                        put("books", JsonArray(sent.books.map(::JsonPrimitive)))
                        put("cards", JsonArray(sent.cards.map(::JsonPrimitive)))
                    },
                )
            },
        )
    }

    /**
     * Приводит прочитанное состояние к нынешнему виду.
     *
     * Номера для переезда придумывает клиент: своего источника случайности у
     * ядра нет. С запасом — сколько книг, столько и номеров; лишние ядро
     * просто не возьмёт.
     */
    private fun migrate() {
        val fresh = state.value.books.map { newId() }
        send(command("migrate") { put("freshIds", JsonArray(fresh.map(::JsonPrimitive))) })
    }

    // --- внутреннее ---

    private fun addBook(book: LibraryBook): LibraryBook =
        send(command("addBook") { put("book", json.encodeToJsonElement(book)) }).book ?: book

    /**
     * Команда ядру.
     *
     * Обновление экранов и запись на диск делает сама сессия: она одна знает,
     * что именно изменилось.
     */
    private fun send(command: kotlinx.serialization.json.JsonObject): Outcome =
        session.run(command)

    /** Вопрос, который ничего не меняет. Отдельным именем — ради читателя кода. */
    private fun ask(command: kotlinx.serialization.json.JsonObject): Outcome = session.run(command)

    private companion object {
        /** Книга, в которую складываются распознанные страницы. */
        const val SNAPSHOTS = "Снимки со страниц"
        const val SNAPSHOTS_FILE = "snapshots.txt"
    }
}

/** Текущее время в миллисекундах — платформы берут его по-разному. */
expect fun currentTimeMillis(): Long
