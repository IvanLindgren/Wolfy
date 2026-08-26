package com.wolfy.ffi

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import kotlinx.serialization.json.Json

/**
 * Мост к ядру на Rust через JNA.
 *
 * ## Почему JNA, а не JNI
 *
 * Ядро экспортирует обычные C-функции, и дотянуться до них можно двумя
 * способами: написать в Rust отдельный слой `Java_com_wolfy_...` под JNI или
 * взять JNA. JNI быстрее на вызов, но требует второго набора функций в ядре —
 * с именами, зависящими от пакета Kotlin, и с собственной обработкой строк.
 * Это второй контракт поверх уже существующего, и он ломался бы при каждом
 * переименовании класса.
 *
 * Накладные расходы JNA — доли микросекунды на вызов. Разбор слова занимает
 * единицы микросекунд при пороге в 15 миллисекунд, так что выбор очевиден:
 * один контракт вместо двух.
 *
 * ## Строки и кодировка
 *
 * Ядро говорит в UTF-8, JVM внутри — в UTF-16, а JNA по умолчанию переводит
 * строки в кодировку платформы, которая на русской Windows будет cp1251.
 * Поэтому строки передаются байтами: `String.toUtf8()` на входе,
 * `Pointer.getString(0, "UTF-8")` на выходе. Полагаться на настройку
 * `jna.encoding` нельзя — она глобальная и её может переопределить кто угодно.
 */
internal interface CoreLibrary : Library {
    fun wolfy_version(): Pointer?
    fun wolfy_last_error(): Pointer?
    fun wolfy_string_free(text: Pointer?)

    fun wolfy_analyze_word(word: ByteArray): Pointer?
    fun wolfy_tokenize(text: ByteArray): Pointer?
    fun wolfy_explain(text: ByteArray): Pointer?
    fun wolfy_grammar_reference(): Pointer?
    fun wolfy_grammar_exercises(): Pointer?

    fun wolfy_book_open(path: ByteArray): Long
    fun wolfy_book_metadata(handle: Long): Pointer?
    fun wolfy_book_chapter(handle: Long, index: Long): Pointer?
    fun wolfy_book_resource(handle: Long, path: ByteArray, outLen: LongByReference): Pointer?
    fun wolfy_bytes_free(bytes: Pointer?, len: Long)
    fun wolfy_book_prepared_chapter(handle: Long, index: Long): Pointer?
    fun wolfy_text_anchors(text: ByteArray): Pointer?
    fun wolfy_book_chapter_anchors(handle: Long, index: Long): Pointer?
    fun wolfy_book_chapter_segment(
        handle: Long,
        index: Long,
        from: Long,
        targetWords: Long,
    ): Pointer?
    fun wolfy_inspect_word(word: ByteArray, sentence: ByteArray): Pointer?
    fun wolfy_book_close(handle: Long)

    fun wolfy_session_open(library: ByteArray?, settings: ByteArray?): Long
    fun wolfy_session_open_strict(library: ByteArray?, settings: ByteArray?): Long
    fun wolfy_session_open_with_practice(library: ByteArray?, settings: ByteArray?, practice: ByteArray?): Long
    fun wolfy_session_open_strict_with_practice(library: ByteArray?, settings: ByteArray?, practice: ByteArray?): Long
    fun wolfy_session_run(handle: Long, command: ByteArray): Pointer?
    fun wolfy_session_library(handle: Long): Pointer?
    fun wolfy_session_settings(handle: Long): Pointer?
    fun wolfy_session_practice(handle: Long): Pointer?
    fun wolfy_session_dirty(handle: Long): Pointer?
    fun wolfy_session_generations(handle: Long): Pointer?
    fun wolfy_session_saved(handle: Long, library: Boolean, settings: Boolean)
    fun wolfy_session_saved_with_practice(handle: Long, library: Boolean, settings: Boolean, practice: Boolean)
    fun wolfy_session_ack_saved(handle: Long, libraryGen: Long, settingsGen: Long, practiceGen: Long)
    fun wolfy_session_close(handle: Long)
}

/**
 * Разбор ответов ядра.
 *
 * `ignoreUnknownKeys` включён намеренно: ядро обновляется вместе с
 * приложением, но пользователь может остаться на старой версии клиента с
 * новым ядром внутри — новое поле не должно ронять разбор.
 */
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/** Реализация поверх загруженной библиотеки. */
internal class JnaWolfyCore(private val library: CoreLibrary) : WolfyCore {

    override fun version(): String = library.wolfy_version().takeString("версия ядра")

    override fun analyzeWord(word: String): WordAnalysis {
        val raw = library.wolfy_analyze_word(word.toUtf8()).takeString("разбор слова «$word»")
        return json.decodeFromString(raw)
    }

    override fun tokenize(text: String): ParsedText {
        val raw = library.wolfy_tokenize(text.toUtf8()).takeString("разбор текста")
        return json.decodeFromString(raw)
    }

    override fun explain(sentence: String): GrammarResult {
        val raw = library.wolfy_explain(sentence.toUtf8()).takeString("разбор грамматики")
        return json.decodeFromString(raw)
    }

    override fun reference(): List<Article> {
        val raw = library.wolfy_grammar_reference().takeString("справочник грамматики")
        return json.decodeFromString<ReferenceResult>(raw).articles
    }

    override fun exercises(): List<Exercise> {
        val raw = library.wolfy_grammar_exercises().takeString("упражнения по грамматике")
        return json.decodeFromString<ExercisesResult>(raw).exercises
    }

    override fun openBook(path: String): OpenBook {
        val handle = library.wolfy_book_open(path.toUtf8())
        if (handle == 0L) {
            throw CoreException(lastError() ?: "книга не открылась")
        }
        // Если метаданные не прочитались, книга останется открытой и займёт
        // файловый дескриптор до конца работы приложения — закрываем сами.
        val info = try {
            val raw = library.wolfy_book_metadata(handle).takeString("описание книги")
            json.decodeFromString<BookInfo>(raw)
        } catch (e: Throwable) {
            library.wolfy_book_close(handle)
            throw e
        }
        return OpenBook(handle = handle, info = info)
    }

    override fun readChapter(handle: Long, index: Int): Chapter {
        val raw = library.wolfy_book_chapter(handle, index.toLong())
            .takeString("глава $index")
        return json.decodeFromString(raw)
    }

    override fun bookResource(handle: Long, path: String): ByteArray? {
        if (path.isBlank()) return null
        val length = LongByReference(0)
        val pointer = try {
            library.wolfy_book_resource(handle, path.toUtf8(), length)
        } catch (_: UnsatisfiedLinkError) {
            // Старое ядро рядом со свежим клиентом: картинка остаётся
            // подписью, а чтение книги не ломается.
            return null
        } ?: return null
        val size = length.value
        if (size < 0 || size > Int.MAX_VALUE) {
            library.wolfy_bytes_free(pointer, size.coerceAtLeast(0))
            return null
        }
        return try {
            pointer.getByteArray(0, size.toInt())
        } finally {
            library.wolfy_bytes_free(pointer, size)
        }
    }

    override fun preparedChapter(handle: Long, index: Int): PreparedChapter {
        val raw = library.wolfy_book_prepared_chapter(handle, index.toLong())
            .takeString("подготовленная глава $index")
        return json.decodeFromString(raw)
    }

    /*
     * Новые функции ядра рядом со старой нативной библиотекой.
     *
     * JNA сообщает об отсутствующем символе `UnsatisfiedLinkError` — это
     * `Error`, а не `Exception`, и поймать его надо явно. Оба вызова —
     * украшение чтения, поэтому отсутствие символа значит «выключено», а не
     * «книга не открылась».
     */
    override fun chapterAnchors(handle: Long, index: Int): IntArray {
        val raw = try {
            library.wolfy_book_chapter_anchors(handle, index.toLong())
        } catch (error: UnsatisfiedLinkError) {
            return IntArray(0)
        }
        val payload = raw.takeStringOrNull() ?: return IntArray(0)
        return runCatching { json.decodeFromString<IntArray>(payload) }.getOrDefault(IntArray(0))
    }

    override fun textAnchors(text: String): IntArray {
        if (text.isBlank()) return IntArray(0)
        val raw = try {
            library.wolfy_text_anchors(text.toUtf8())
        } catch (error: UnsatisfiedLinkError) {
            return IntArray(0)
        }
        val payload = raw.takeStringOrNull() ?: return IntArray(0)
        return runCatching { json.decodeFromString<IntArray>(payload) }.getOrDefault(IntArray(0))
    }

    override fun chapterSegment(
        handle: Long,
        index: Int,
        from: Int,
        targetWords: Int,
    ): ReadingSegment? {
        val raw = try {
            library.wolfy_book_chapter_segment(
                handle,
                index.toLong(),
                from.toLong(),
                targetWords.toLong(),
            )
        } catch (error: UnsatisfiedLinkError) {
            return null
        }
        val payload = raw.takeStringOrNull() ?: return null
        return runCatching { json.decodeFromString<ReadingSegment>(payload) }.getOrNull()
    }

    override fun inspectWord(word: String, sentence: String): InspectResult {
        val raw = library.wolfy_inspect_word(word.toUtf8(), sentence.toUtf8())
            .takeString("inspectWord $word")
        return json.decodeFromString(raw)
    }

    override fun closeBook(handle: Long) {
        library.wolfy_book_close(handle)
    }

    override fun openSession(library: String?, settings: String?): Long {
        // Пустой указатель здесь не ошибка, а «записи ещё нет».
        val handle = this.library.wolfy_session_open(library?.toUtf8(), settings?.toUtf8())
        if (handle == 0L) {
            throw CoreException(lastError() ?: "сессия не открылась")
        }
        return handle
    }

    override fun openSessionStrict(library: String?, settings: String?): Long {
        val handle = this.library.wolfy_session_open_strict(library?.toUtf8(), settings?.toUtf8())
        if (handle == 0L) {
            throw CoreException(lastError() ?: "сессия не открылась (strict): повреждённый JSON")
        }
        return handle
    }

    override fun runCommand(handle: Long, command: String): String =
        library.wolfy_session_run(handle, command.toUtf8()).takeString("команда ядру")

    override fun sessionLibrary(handle: Long): String =
        library.wolfy_session_library(handle).takeString("библиотека")

    override fun sessionSettings(handle: Long): String =
        library.wolfy_session_settings(handle).takeString("настройки")

    override fun sessionPractice(handle: Long): String =
        (library.wolfy_session_practice(handle) ?: throw CoreException(lastError() ?: "практика не прочиталась")).takeString("практика")

    override fun sessionDirty(handle: Long): String =
        library.wolfy_session_dirty(handle).takeString("dirty")

    override fun sessionGenerations(handle: Long): String =
        library.wolfy_session_generations(handle).takeString("поколения")

    override fun sessionSaved(handle: Long, library: Boolean, settings: Boolean) {
        this.library.wolfy_session_saved(handle, library, settings)
    }

    override fun sessionSavedWithPractice(handle: Long, library: Boolean, settings: Boolean, practice: Boolean) {
        this.library.wolfy_session_saved_with_practice(handle, library, settings, practice)
    }

    override fun sessionAckSaved(handle: Long, libraryGen: Long, settingsGen: Long, practiceGen: Long) {
        library.wolfy_session_ack_saved(handle, libraryGen, settingsGen, practiceGen)
    }

    override fun openSessionWithPractice(library: String?, settings: String?, practice: String?): Long {
        val handle = this.library.wolfy_session_open_with_practice(library?.toUtf8(), settings?.toUtf8(), practice?.toUtf8())
        if (handle == 0L) throw CoreException(lastError() ?: "сессия не открылась (with practice)")
        return handle
    }

    override fun openSessionStrictWithPractice(library: String?, settings: String?, practice: String?): Long {
        val handle = this.library.wolfy_session_open_strict_with_practice(library?.toUtf8(), settings?.toUtf8(), practice?.toUtf8())
        if (handle == 0L) throw CoreException(lastError() ?: "сессия не открылась (strict with practice)")
        return handle
    }

    override fun closeSession(handle: Long) {
        library.wolfy_session_close(handle)
    }

    /**
     * Забирает строку у ядра и сразу её освобождает.
     *
     * Освобождение обязательно и обязательно здесь: строку выделил аллокатор
     * Rust, и отдать её сборщику мусора JVM нельзя — это утечка на каждый тап
     * по слову.
     */
    private fun Pointer?.takeString(what: String): String {
        if (this == null) {
            throw CoreException(lastError() ?: "ядро не смогло выполнить: $what")
        }
        return try {
            getString(0, "UTF-8")
        } finally {
            library.wolfy_string_free(this)
        }
    }

    /**
     * Строка ядра там, где её отсутствие — не ошибка.
     *
     * Отличается от [takeString] только этим: `null` возвращается как `null`,
     * а не превращается в исключение. Нужно тем вызовам, у которых «ядро
     * этого не умеет» — обычный ответ, а не сбой.
     */
    private fun Pointer?.takeStringOrNull(): String? {
        if (this == null) return null
        return try {
            getString(0, "UTF-8")
        } finally {
            library.wolfy_string_free(this)
        }
    }

    /** Описание последней ошибки. Эту строку освобождать не нужно — она
     *  принадлежит ядру и живёт до следующего вызова из этого потока. */
    private fun lastError(): String? =
        library.wolfy_last_error()?.getString(0, "UTF-8")
}

/** Строка в виде UTF-8 с завершающим нулём — то, что ждёт C. */
private fun String.toUtf8(): ByteArray = toByteArray(Charsets.UTF_8) + 0

/**
 * Загружает ядро по имени библиотеки.
 *
 * Имя одно на обе платформы: JNA сама превратит его в `libwolfy_core.so` на
 * Android и `wolfy_core.dll` на Windows.
 */
internal fun loadCore(libraryName: String = "wolfy_core"): WolfyCore {
    val library = try {
        Native.load(libraryName, CoreLibrary::class.java)
    } catch (e: UnsatisfiedLinkError) {
        throw CoreException(
            "ядро не загрузилось ($libraryName): ${e.message}. " +
                "Переустановите Wolfy из полного установщика",
        )
    }
    return JnaWolfyCore(library)
}
