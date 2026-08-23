package com.wolfy.ffi

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
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
    fun wolfy_book_close(handle: Long)

    fun wolfy_session_open(library: ByteArray?, settings: ByteArray?): Long
    fun wolfy_session_run(handle: Long, command: ByteArray): Pointer?
    fun wolfy_session_library(handle: Long): Pointer?
    fun wolfy_session_settings(handle: Long): Pointer?
    fun wolfy_session_saved(handle: Long, library: Boolean, settings: Boolean)
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

    override fun explain(sentence: String): List<Finding> {
        val raw = library.wolfy_explain(sentence.toUtf8()).takeString("разбор грамматики")
        return json.decodeFromString<GrammarResult>(raw).findings
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

    override fun runCommand(handle: Long, command: String): String =
        library.wolfy_session_run(handle, command.toUtf8()).takeString("команда ядру")

    override fun sessionLibrary(handle: Long): String =
        library.wolfy_session_library(handle).takeString("библиотека")

    override fun sessionSettings(handle: Long): String =
        library.wolfy_session_settings(handle).takeString("настройки")

    override fun sessionSaved(handle: Long, library: Boolean, settings: Boolean) {
        this.library.wolfy_session_saved(handle, library, settings)
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
