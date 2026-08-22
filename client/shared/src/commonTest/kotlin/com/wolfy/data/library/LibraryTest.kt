package com.wolfy.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
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
    var saved: String? = null
    val deleted = mutableListOf<String>()
    var imports = 0

    override fun load(): String? = saved

    override fun save(json: String) {
        saved = json
    }

    override fun importBook(sourcePath: String, fileName: String): String {
        imports++
        return "/store/$fileName"
    }

    override fun deleteBook(path: String) {
        deleted += path
    }
}

/** Время под контролем: без него порядок книг зависел бы от скорости теста. */
private class Clock(var value: Long = 1_000L) {
    fun tick(by: Long = 1_000L): Long {
        value += by
        return value
    }
}

private fun library(store: LibraryStore, clock: Clock) = Library(store, now = { clock.value })

class LibraryTest {

    @Test
    fun книга_добавляется_и_копируется_в_хранилище() {
        val store = FakeStore()
        val library = library(store, Clock())

        val book = library.add("/downloads/gatsby.epub", "gatsby.epub", "The Great Gatsby", "Fitzgerald")

        assertEquals(1, store.imports, "файл обязан быть скопирован в хранилище")
        assertEquals("/store/gatsby.epub", book.path)
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
        store.saved = "{ это не json"

        val library = library(store, Clock())

        assertTrue(library.books.isEmpty())
    }

    @Test
    fun слово_не_попадает_в_колоду_дважды() {
        val store = FakeStore()
        val library = library(store, Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)

        library.saveWord(book.id, "serendipity")
        library.saveWord(book.id, "serendipity")

        assertEquals(1, library.book(book.id)?.savedWords)
    }

    @Test
    fun книга_дня_это_последняя_открытая_и_недочитанная() {
        val store = FakeStore()
        val clock = Clock()
        val library = library(store, clock)

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
        val store = FakeStore()
        val library = library(store, Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)
        val shelf = library.addShelf("Классика")
        library.moveToShelf(book.id, shelf.id)

        library.removeShelf(shelf.id)

        assertEquals(1, library.books.size)
        assertNull(library.book(book.id)?.shelf)
    }

    @Test
    fun удаление_книги_убирает_и_файл() {
        val store = FakeStore()
        val library = library(store, Clock())
        val book = library.add("/a.txt", "a.txt", "Книга", null)

        library.remove(book.id)

        assertEquals(listOf("/store/a.txt"), store.deleted)
        assertTrue(library.books.isEmpty())
    }

    @Test
    fun номер_изменения_растёт_при_каждой_записи() {
        // По нему синхронизация поймёт, чья версия свежее, не сверяя книги
        // по одной.
        val store = FakeStore()
        val library = library(store, Clock())

        val before = library.state.value.revision
        library.add("/a.txt", "a.txt", "Книга", null)
        val after = library.state.value.revision

        assertTrue(after > before, "было $before, стало $after")
    }

    @Test
    fun пустое_название_из_файла_не_затирает_прежнее() {
        val library = library(FakeStore(), Clock())
        val book = library.add("/a.txt", "a.txt", "Имя файла", null)

        library.describe(book.id, title = "   ", author = null, chapters = 3)

        assertEquals("Имя файла", library.book(book.id)?.title)
        assertEquals(3, library.book(book.id)?.chapters)
    }
}
