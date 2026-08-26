package com.wolfy.data

import com.wolfy.data.library.Card
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.Progress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Формат обмена с сервером.
 *
 * Отдельные типы, а не сериализованная модель библиотеки, и по той же причине,
 * по какой у ядра отдельные DTO: модель внутри приложения меняется свободно, а
 * то, что уходит в сеть, — контракт с сервером, который обновляется отдельно.
 * Пользователь со старой версией приложения обязан продолжать
 * синхронизироваться.
 *
 * Имена полей повторяют серверные, а не местные: переименовать поле здесь
 * дешевле, чем объяснять серверу, что у клиента оно называется иначе.
 */
@Serializable
data class SyncPayload(
    val cursor: Long = 0,
    val books: List<SyncBook> = emptyList(),
    val cards: List<SyncCard> = emptyList(),
    /** Файлы, которые можно забрать с личного хранилища аккаунта. */
    val files: List<SyncBookFile> = emptyList(),
    /**
     * Настройки чтения целиком.
     *
     * Целиком, а не по полю: настроек полтора десятка, они меняются вместе, и
     * слияние по полям дало бы половину темы с одного устройства и половину
     * с другого.
     */
    val reading: JsonElement? = null,
)

@Serializable
data class SyncBookFile(
    val bookId: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class SyncBook(
    val id: String,
    val title: String = "",
    val author: String = "",
    val format: String = "",
    val sourceKey: String = "",
    val chapterCount: Int = 0,
    val lastChapter: Int = 0,
    /**
     * Место внутри главы.
     *
     * На сервере это целое — там колонка под смещение в символах. Доля главы,
     * которой оперирует читалка, умножается на десять тысяч: точности хватает
     * с запасом, а целое переживает любую смену представления.
     */
    val lastOffset: Int = 0,
    val shelf: String = "",
    val position: Int = 0,
    val rev: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
data class SyncCard(
    val id: String,
    val bookId: String = "",
    val kind: String = "word",
    val surface: String = "",
    val lemma: String = "",
    val translation: String = "",
    val context: String = "",
    val pos: String = "",
    val cefr: String = "",
    val hp: Int = 100,
    val streak: Int = 0,
    val intervalDays: Int = 0,
    /** RFC 3339 — так время выглядит и в базе, и в логах. */
    val dueAt: String = "",
    val reviewedAt: String? = null,
    val rev: Long = 0,
    val deleted: Boolean = false,
)

/** Точность доли главы при передаче на сервер. */
private const val OFFSET_SCALE = 10_000

fun LibraryBook.toSync(): SyncBook = SyncBook(
    id = id,
    title = title,
    author = author.orEmpty(),
    format = format,
    sourceKey = sourceKey,
    chapterCount = chapters,
    lastChapter = progress.chapter,
    lastOffset = (progress.withinChapter * OFFSET_SCALE).toInt(),
    shelf = shelf.orEmpty(),
    rev = rev,
    deleted = deleted,
)

/**
 * Книга с сервера.
 *
 * Путь к файлу и время последнего открытия сюда не приезжают и приехать не
 * могут: путь у каждого устройства свой, а времени открытия на сервере нет.
 * Поэтому местные значения передаются отдельно — [previous].
 */
fun SyncBook.toLibrary(previous: LibraryBook?): LibraryBook = LibraryBook(
    id = id,
    path = previous?.path.orEmpty(),
    title = title,
    author = author.takeIf { it.isNotBlank() },
    format = format,
    sourceKey = sourceKey,
    addedAt = previous?.addedAt ?: 0,
    chapters = chapterCount,
    progress = Progress(
        chapter = lastChapter,
        withinChapter = lastOffset.toFloat() / OFFSET_SCALE,
        // Время открытия — местное дело: на сервере его нет, а обнулять его
        // значило бы каждый раз терять «книгу дня» после синхронизации.
        openedAt = previous?.progress?.openedAt ?: 0,
    ),
    shelf = shelf.takeIf { it.isNotBlank() },
    rev = rev,
    dirty = false,
    deleted = deleted,
)

fun Card.toSync(): SyncCard = SyncCard(
    id = id,
    bookId = bookId,
    kind = kind,
    surface = surface,
    lemma = lemma,
    translation = translation,
    context = context,
    pos = pos,
    cefr = cefr,
    hp = hp,
    streak = streak,
    intervalDays = intervalDays,
    dueAt = formatInstant(dueAt),
    reviewedAt = formatInstant(reviewedAt).takeIf { it.isNotEmpty() },
    rev = rev,
    deleted = deleted,
)

fun SyncCard.toLibrary(previous: Card?): Card = Card(
    id = id,
    bookId = bookId,
    kind = kind,
    surface = surface,
    lemma = lemma,
    translation = translation,
    context = context,
    pos = pos,
    cefr = cefr,
    hp = hp,
    streak = streak,
    intervalDays = intervalDays,
    dueAt = parseInstant(dueAt),
    reviewedAt = reviewedAt?.let(::parseInstant) ?: 0,
    addedAt = previous?.addedAt ?: parseInstant(dueAt),
    rev = rev,
    dirty = false,
    deleted = deleted,
)
