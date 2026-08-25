package com.wolfy.data.library

import kotlinx.serialization.json.Json

/**
 * Книга, к которой читатель вернётся.
 *
 * Нужна там, где приложения нет: виджету на рабочем столе Android и панели на
 * рабочем столе Windows. Оба живут вне композиции и вне сессии ядра — виджет
 * система вообще будит в своём процессе, где ни ядра, ни настроек нет, —
 * поэтому список читается прямо из записи на диске.
 *
 * Читать состояние в обход ядра нормально ровно потому, что здесь ничего не
 * решается: выбирается книга для показа, а не судьба данных. Ни одна запись
 * при этом не делается.
 */
private val lastReadJson = Json { ignoreUnknownKeys = true }

/**
 * Имя записи с библиотекой.
 *
 * Повторяет приватную константу `CoreSession`, а не заимствует её: та должна
 * остаться приватной — читать состояние в обход ядра позволено ровно здесь и
 * ровно для показа. Что имена не разошлись, проверяет `LastReadTest`.
 */
private const val LIBRARY_RECORD = "library"

/**
 * Последняя открытая книга или `null`, если звать некуда.
 *
 * Выбор — по времени последнего открытия, а не по порядку в списке: полку
 * читатель раскладывает под себя, а «где я был» — это про время.
 *
 * Книга без файла на этом устройстве не годится: она приехала
 * синхронизацией, открыть её нечем, и приглашение вело бы в тупик.
 */
fun LibraryStore.lastReadBook(): LibraryBook? {
    val raw = runCatching { load(LIBRARY_RECORD) }.getOrNull()
    if (raw.isNullOrBlank()) return null

    val state = runCatching { lastReadJson.decodeFromString<LibraryState>(raw) }.getOrNull()
        ?: return null

    return state.books
        .filter { !it.deleted && it.path.isNotBlank() }
        .maxByOrNull { it.progress.openedAt }
        ?.takeIf { it.progress.openedAt > 0 }
}
