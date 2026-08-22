package com.wolfy.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилище в памяти.
 *
 * Файлы книг не копируются никуда: библиотеку проверяют её собственные
 * правила, а не умение JVM переложить байты. Путь возвращается выдуманный, и
 * этого достаточно — библиотека его только запоминает.
 */
private class FakeStore : LibraryStore {
    val records = mutableMapOf<String, String>()
    val deleted = mutableListOf<String>()
    var imports = 0

    /** Отпечатки по пути: так тест изображает два файла с одним содержимым. */
    val fingerprints = mutableMapOf<String, String>()

    override fun load(name: String): String? = records[name]

    override fun save(name: String, json: String) {
        records[name] = json
    }

    override fun importBook(sourcePath: String, fileName: String): String {
        imports++
        return "/store/$fileName"
    }

    override fun deleteBook(path: String) {
        deleted += path
    }

    override fun fingerprint(path: String): String = fingerprints[path] ?: ""
}

/** Время под контролем: без него порядок книг зависел бы от скорости теста. */
private class Clock(var value: Long = 1_000L) {
    fun tick(by: Long = 1_000L): Long {
        value += by
        return value
    }
}

/** Номера по счётчику: сравнивать в тестах случайные UUID невозможно. */
private class Ids {
    private var next = 0
    fun new(): String {
        next++
        return "00000000-0000-4000-8000-" + next.toString().padStart(12, '0')
    }
}

private fun library(store: LibraryStore, clock: Clock, ids: Ids = Ids()) =
    Library(store, now = { clock.value }, newId = ids::new)

class LibraryTest {

    @Test
    fun книга_добавляется_и_копируется_в_хранилище() {
        val store = FakeStore()
        val library = library(store, Clock())

        val book = library.add("/downloads/gatsby.epub", "gatsby.epub", "The Great Gatsby", "Fitzgerald")

        assertEquals(1, store.imports, "файл обязан быть скопирован в хранилище")
        assertEquals("/store/gatsby.epub", book.path)
        assertEquals("epub", book.format)
        assertEquals(listOf(book), library.books)
    }

    @Test
    fun библиотека_переживает_перезапуск() {
        // Ровно то, ради чего хранилище и существует: прочитанное место
        // обязано пережить закрытие приложения свайпом.
        val store = FakeStore()
        val clock = Clock()

        val first = library(store, clock)
        val book = first.add("/a.txt", "a.txt", "Книга", null)
        first.describe(book.id, "Книга", "Автор", chapters = 10)
        clock.tick()
        first.rememberProgress(book.id, chapter = 4, withinChapter = 0.5f)

        val second = library(store, clock)
        val restored = second.book(book.id)

        assertEquals(4, restored?.progress?.chapter)
        assertEquals("Автор", restored?.author)
        assertEquals(0.45f, restored?.fraction)
    }

    @Test
    fun битый_файл_библиотеки_не_мешает_запуску() {
        // Падение на старте не оставило бы пользователю ничего. Пустая
        // библиотека хотя бы позволяет добавить книги заново.
        val store = FakeStore()
        store.records["library"] = "{ это не json"

        val library = library(store, Clock())

        assertTrue(library.books.isEmpty())
    }

    @Test
    fun слово_не_попадает_в_колоду_дважды() {
        val library = library(FakeStore(), Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)

        library.saveWord(book.id, "serendipity", "serendipity")
        library.saveWord(book.id, "Serendipities", "serendipity")

        assertEquals(1, library.deck(book.id).size)
    }

    @Test
    fun убранное_слово_возвращается_той_же_записью() {
        // Новая запись приехала бы на второе устройство рядом со старой, и в
        // колоде оказалось бы два одинаковых слова.
        val library = library(FakeStore(), Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)

        val first = library.saveWord(book.id, "lamp", "lamp")
        library.removeWord(book.id, "lamp")
        val again = library.saveWord(book.id, "lamp", "lamp")

        assertEquals(first.id, again.id)
        assertEquals(1, library.deck(book.id).size)
    }

    @Test
    fun книга_дня_это_последняя_открытая_и_недочитанная() {
        val clock = Clock()
        val library = library(FakeStore(), clock)

        val old = library.add("/a.txt", "a.txt", "Ранняя", null)
        val recent = library.add("/b.txt", "b.txt", "Поздняя", null)
        val done = library.add("/c.txt", "c.txt", "Дочитанная", null)
        library.describe(old.id, "Ранняя", null, chapters = 10)
        library.describe(recent.id, "Поздняя", null, chapters = 10)
        library.describe(done.id, "Дочитанная", null, chapters = 10)

        library.rememberProgress(old.id, chapter = 1, withinChapter = 0f)
        clock.tick()
        library.rememberProgress(recent.id, chapter = 2, withinChapter = 0f)
        clock.tick()
        // Дочитанная открыта позже всех, но предлагать её незачем: читатель
        // уже закрыл эту книгу.
        library.rememberProgress(done.id, chapter = 10, withinChapter = 1f)

        assertEquals("Поздняя", library.continueReading()?.title)
    }

    @Test
    fun никогда_не_открытая_книга_дня_не_становится() {
        val library = library(FakeStore(), Clock())
        library.add("/a.txt", "a.txt", "Новая", null)

        assertNull(library.continueReading(), "«книга дня» это продолжение, а не выбор")
    }

    @Test
    fun удалённая_полка_возвращает_книги_в_неразобранные() {
        // Полка — место, а не свойство книги: с исчезновением места книга
        // никуда не девается.
        val library = library(FakeStore(), Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)
        library.addShelf("Классика")
        library.moveToShelf(book.id, "Классика")

        library.removeShelf("Классика")

        assertEquals(1, library.books.size)
        assertNull(library.book(book.id)?.shelf)
    }

    @Test
    fun удаление_книги_убирает_файл_но_оставляет_запись() {
        // Файл больше не нужен никому, а запись обязана доехать до второго
        // устройства: стёртую оно не заметит, и книга там воскреснет.
        val store = FakeStore()
        val library = library(store, Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)
        library.saveWord(book.id, "word", "word")

        library.remove(book.id)

        assertEquals(listOf("/store/a.txt"), store.deleted)
        assertTrue(library.books.isEmpty(), "из видимых книга ушла")
        assertTrue(library.book(book.id)?.deleted == true, "запись осталась с пометкой")
        assertTrue(library.deck(book.id).isEmpty(), "колода ушла вместе с книгой")
    }

    @Test
    fun тот_же_файл_не_добавляется_дважды() {
        // Одна и та же книга, добавленная с телефона и с компьютера, обязана
        // остаться одной книгой, а не превратиться в две с одним названием.
        val store = FakeStore()
        store.fingerprints["/downloads/gatsby.epub"] = "abc123"
        store.fingerprints["/desktop/gatsby.epub"] = "abc123"
        val library = library(store, Clock())

        val first = library.add("/downloads/gatsby.epub", "gatsby.epub", "Гэтсби", null)
        val second = library.add("/desktop/gatsby.epub", "gatsby.epub", "Гэтсби", null)

        assertEquals(first.id, second.id)
        assertEquals(1, library.books.size)
    }

    @Test
    fun изменённая_запись_ждёт_отправки() {
        val library = library(FakeStore(), Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)
        library.saveWord(book.id, "lamp", "lamp")

        val (books, cards) = library.pending()

        assertEquals(1, books.size)
        assertEquals(1, cards.size)
    }

    @Test
    fun ответ_сервера_снимает_записи_с_отправки() {
        val library = library(FakeStore(), Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)

        library.applyServer(
            cursor = 7,
            books = listOf(book.copy(rev = 7, dirty = false)),
            cards = emptyList(),
        )

        assertTrue(library.pending().first.isEmpty(), "отправленное больше не ждёт")
        assertEquals(7, library.state.value.cursor)
    }

    @Test
    fun путь_к_файлу_синхронизацией_не_затирается() {
        // Путь у каждого устройства свой, и сервер о нём не знает. Затереть
        // его ответом сервера значило бы отобрать книгу у того, кто её читает.
        val library = library(FakeStore(), Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)

        library.applyServer(
            cursor = 3,
            books = listOf(book.copy(path = "", title = "Переименована", rev = 3, dirty = false)),
            cards = emptyList(),
        )

        val after = library.book(book.id)
        assertEquals("/store/a.txt", after?.path)
        assertEquals("Переименована", after?.title)
    }

    @Test
    fun книга_без_файла_не_предлагается_к_чтению() {
        // Такая приезжает по синхронизации: сервер знает, что вы читаете
        // «Гэтсби», но самого файла у него нет.
        val library = library(FakeStore(), Clock())
        library.applyServer(
            cursor = 1,
            books = listOf(
                LibraryBook(
                    id = "00000000-0000-4000-8000-000000000042",
                    title = "Гэтсби",
                    chapters = 9,
                    progress = Progress(chapter = 4, openedAt = 500),
                    rev = 1,
                    dirty = false,
                ),
            ),
            cards = emptyList(),
        )

        assertFalse(library.books.first().readable)
        assertNull(library.continueReading(), "предложить и не суметь открыть хуже, чем не предлагать")
    }

    @Test
    fun полка_с_другого_устройства_появляется_в_списке() {
        // Своей таблицы у полок нет: книга хранит название, и полка приезжает
        // вместе с книгой. В списке полок её надо восстановить.
        val library = library(FakeStore(), Clock())
        library.applyServer(
            cursor = 1,
            books = listOf(
                LibraryBook(
                    id = "00000000-0000-4000-8000-000000000043",
                    title = "Дюна",
                    shelf = "Science Fiction",
                    rev = 1,
                    dirty = false,
                ),
            ),
            cards = emptyList(),
        )

        assertEquals(listOf("Science Fiction"), library.state.value.shelves.map { it.name })
    }

    @Test
    fun старые_номера_книг_переписываются_на_uuid() {
        // До синхронизации номера придумывались как попало, а на сервере под
        // них колонка uuid. Колода обязана переехать вместе с книгой.
        val store = FakeStore()
        store.records["library"] = """
            {
              "books": [{"id": "b1a02a07-0", "title": "Старая", "path": "/store/a.txt"}],
              "cards": [{"id": "c1", "bookId": "b1a02a07-0", "surface": "lamp", "lemma": "lamp"}],
              "shelves": [], "cursor": 0, "revision": 3
            }
        """.trimIndent()

        val library = library(store, Clock())
        val book = library.books.single()

        assertEquals(36, book.id.length, "номер обязан стать uuid: ${book.id}")
        assertEquals(1, library.deck(book.id).size, "колода потеряла хозяина")
    }
}
