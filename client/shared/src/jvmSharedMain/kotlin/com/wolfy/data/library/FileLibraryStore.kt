package com.wolfy.data.library

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Библиотека в файлах — общая реализация для Android и Windows.
 *
 * Обе платформы работают на JVM, и различие между ними ровно одно: где лежит
 * каталог приложения. Оно и вынесено в аргумент, а всё остальное — общий код.
 */
internal class FileLibraryStore(
    private val directory: File,
    private val moveFile: (Path, Path, Array<out CopyOption>) -> Path = { source, target, options ->
        Files.move(source, target, *options)
    },
    private val renameFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
) : LibraryStore {

    private val books = File(directory, "books")

    private fun file(name: String) = File(directory, "$name.json")

    override fun load(name: String): String? =
        file(name).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    /**
     * Запись через временный файл с fsync.
     *
     * Прямая запись оставила бы пользователя без библиотеки, если приложение
     * закроют посреди неё: файл уже обрезан, а новое содержимое ещё не
     * дописано. Переименование же на всех поддерживаемых системах атомарно,
     * но только после того, как временный файл гарантированно сброшен на диск.
     * Поэтому временный файл пишется через `FileDescriptor.sync()`, а после
     * переименования синхронизируется и каталог — иначе перезапись может
     * потеряться при сбое питания до сброса журнала файловой системы.
     */
    override fun save(name: String, json: String) {
        directory.mkdirs()
        val index = file(name)
        val temporary = File(directory, "$name.json.tmp")
        val bytes = json.toByteArray(Charsets.UTF_8)
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(temporary)
            fos.write(bytes)
            fos.fd.sync()
            fos.close()
            fos = null

            // Попытка атомарной замены — идеальный случай.
            try {
                moveFile(
                    temporary.toPath(),
                    index.toPath(),
                    arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                )
                forceDirectory()
                return
            } catch (_: Exception) {
                // Не все ФС умеют ATOMIC_MOVE (например, разные точки монтирования).
            }

            // Неатомарная, но всё ещё replace — безопаснее delete+rename.
            try {
                moveFile(
                    temporary.toPath(),
                    index.toPath(),
                    arrayOf(StandardCopyOption.REPLACE_EXISTING),
                )
                forceDirectory()
                return
            } catch (_: Exception) {
                // Падаем в ручной fallback ниже.
            }

            // Ручной fallback для Windows, где renameTo не перезаписывает.
            index.delete()
            if (!renameFile(temporary, index)) {
                // Последний шанс — прямая запись с fsync (не атомарно, но лучше потери).
                FileOutputStream(index).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
            } else {
                // renameTo сработал после delete — синхронизируем результат.
                forceFile(index)
            }
            forceDirectory()
        } finally {
            try {
                fos?.close()
            } catch (_: Exception) {}
            // Временный файл должен исчезнуть в любом случае; если move удался,
            // его уже нет, иначе подчищаем.
            temporary.delete()
        }
    }

    private fun forceFile(file: File) {
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        } catch (_: Exception) {}
    }

    private fun forceDirectory() {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {}
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

    override fun readText(path: String): String {
        val file = File(path)
        return if (file.isFile) file.readText(Charsets.UTF_8) else ""
    }

    override fun writeText(fileName: String, text: String): String {
        books.mkdirs()
        val file = File(books, fileName)
        file.writeText(text, Charsets.UTF_8)
        return file.absolutePath
    }

    override fun appendText(fileName: String, text: String): String {
        books.mkdirs()
        val file = File(books, fileName)
        // Append не открывает и не копирует уже собранную книгу. fsync нужен
        // после каждой страницы: приложение могут закрыть ровно после OCR.
        FileOutputStream(file, true).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        return file.absolutePath
    }

    override fun writeBook(fileName: String, bytes: ByteArray): String {
        books.mkdirs()
        val file = uniqueFile(fileName)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    // --- обложки ---

    private val coversDirectory = File(directory, "covers")

    override fun writeCover(bookId: String, extension: String, bytes: ByteArray): String {
        coversDirectory.mkdirs()
        // Одна книга — одна обложка: прежние стираются до записи новой.
        coversDirectory.listFiles()
            ?.filter { it.name.startsWith("$bookId.") }
            ?.forEach { it.delete() }
        val file = File(coversDirectory, "$bookId.${extension.lowercase()}")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    override fun findCover(bookId: String): String? =
        coversDirectory
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith("$bookId.") && it.length() > 0L }
            ?.absolutePath

    override fun deleteCover(bookId: String) {
        coversDirectory
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.name.startsWith("$bookId.") }
            ?.forEach { it.delete() }
    }

    override fun readBinary(path: String): ByteArray? =
        runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun bookSize(path: String): Long? = File(path).takeIf { it.isFile }?.length()

    override fun readBookChunk(path: String, offset: Long, maxBytes: Int): ByteArray? = runCatching {
        require(offset >= 0 && maxBytes in 1..(2 * 1024 * 1024))
        val file = File(path)
        if (!file.isFile || offset >= file.length()) return@runCatching null
        val length = minOf(maxBytes.toLong(), file.length() - offset).toInt()
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            ByteArray(length).also { input.readFully(it) }
        }
    }.getOrNull()

    override fun createBookDownload(fileName: String): String {
        books.mkdirs()
        val target = uniqueFile(fileName)
        FileOutputStream(target, false).use { it.fd.sync() }
        return target.absolutePath
    }

    override fun appendBookChunk(path: String, bytes: ByteArray): Boolean = runCatching {
        val target = File(path)
        require(target.absolutePath.startsWith(books.absolutePath))
        FileOutputStream(target, true).use { output -> output.write(bytes); output.fd.sync() }
        true
    }.getOrDefault(false)

    override fun dictionaryPath(): String =
        File(directory, DICTIONARY_FILE).takeIf { dictionary ->
            dictionary.isFile && dictionary.length() > 0L && runCatching {
                dictionary.bufferedReader(Charsets.UTF_8).use { it.readLine() == DICTIONARY_HEADER }
            }.getOrDefault(false)
        }?.absolutePath.orEmpty()

    override fun installDictionary(compressed: ByteArray): String {
        directory.mkdirs()
        val target = File(directory, DICTIONARY_FILE)
        val temporary = File(directory, "$DICTIONARY_FILE.tmp")

        try {
            compressed.inputStream().use { bytes ->
                GZIPInputStream(bytes).use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
            require(temporary.length() > 0L) { "словарь пуст" }
            val header = temporary.bufferedReader(Charsets.UTF_8).use { it.readLine().orEmpty() }
            require(header == DICTIONARY_HEADER) { "неверный формат словаря" }

            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                // Не все файловые системы умеют атомарную замену, но даже
                // там REPLACE_EXISTING безопаснее ручного delete + rename:
                // исправный словарь не исчезает до самого переноса.
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            return target.absolutePath
        } finally {
            temporary.delete()
        }
    }

    /**
     * Отпечаток — SHA-256 содержимого.
     *
     * Файл читается кусками, а не целиком: книга бывает и на сотню мегабайт,
     * а держать её в памяти ради хеша незачем.
     */
    override fun fingerprint(path: String): String {
        val file = File(path)
        if (!file.isFile) return ""

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte ->
                val value = byte.toInt() and 0xFF
                value.toString(16).padStart(2, '0')
            }
        } catch (e: Exception) {
            // Файл мог исчезнуть между выбором и чтением. Книга просто не
            // узнается автоматически — это не повод не добавлять её.
            ""
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

    private companion object {
        const val DICTIONARY_FILE = "wolfy_dictionary.tsv"
        const val DICTIONARY_HEADER = "# wolfy english dictionary v2"
    }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
