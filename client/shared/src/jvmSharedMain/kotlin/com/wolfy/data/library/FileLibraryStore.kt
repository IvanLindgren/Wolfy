package com.wolfy.data.library

import java.io.File

/**
 * Библиотека в файлах — общая реализация для Android и Windows.
 *
 * Обе платформы работают на JVM, и различие между ними ровно одно: где лежит
 * каталог приложения. Оно и вынесено в аргумент, а всё остальное — общий код.
 */
internal class FileLibraryStore(private val directory: File) : LibraryStore {

    private val books = File(directory, "books")

    private fun file(name: String) = File(directory, "$name.json")

    override fun load(name: String): String? =
        file(name).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    /**
     * Запись через временный файл.
     *
     * Прямая запись оставила бы пользователя без библиотеки, если приложение
     * закроют посреди неё: файл уже обрезан, а новое содержимое ещё не
     * дописано. Переименование же на всех поддерживаемых системах атомарно.
     */
    override fun save(name: String, json: String) {
        directory.mkdirs()
        val index = file(name)
        val temporary = File(directory, "$name.json.tmp")
        temporary.writeText(json, Charsets.UTF_8)
        if (!temporary.renameTo(index)) {
            // На Windows переименование поверх существующего файла срывается.
            // Терять при этом уже записанное нельзя, поэтому копируем.
            index.delete()
            if (!temporary.renameTo(index)) {
                index.writeText(json, Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    override fun importBook(sourcePath: String, fileName: String): String {
        books.mkdirs()
        val target = uniqueFile(fileName)
        File(sourcePath).copyTo(target, overwrite = false)
        return target.absolutePath
    }

    override fun deleteBook(path: String) {
        val file = File(path)
        // Удаляем только своё: путь мог прийти из старой версии библиотеки и
        // указывать куда угодно на диске пользователя.
        if (file.absolutePath.startsWith(books.absolutePath)) {
            file.delete()
        }
    }

    /**
     * Имя, которое не займёт чужой файл.
     *
     * Две книги с одинаковым именем — обычное дело: «book.epub» скачивается
     * под этим именем откуда угодно. Перезаписать первую значило бы молча
     * подменить читателю книгу.
     */
    private fun uniqueFile(fileName: String): File {
        val safe = fileName.map { if (it.isLetterOrDigit() || it in "-_. ") it else '_' }
            .joinToString("")
            .trim()
            .ifBlank { "book" }

        val candidate = File(books, safe)
        if (!candidate.exists()) return candidate

        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "")
        var attempt = 2
        while (true) {
            val name = if (extension.isEmpty()) "$stem ($attempt)" else "$stem ($attempt).$extension"
            val next = File(books, name)
            if (!next.exists()) return next
            attempt++
        }
    }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
