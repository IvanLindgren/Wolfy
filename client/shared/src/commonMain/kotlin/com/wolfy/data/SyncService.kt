package com.wolfy.data

import com.wolfy.data.library.Card
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Что сейчас с синхронизацией. */
data class SyncStatus(
    val running: Boolean = false,
    /** Когда последний раз сошлись с сервером. Ноль — ни разу. */
    val lastSuccess: Long = 0,
    /** Сколько записей ждёт отправки. */
    val pending: Int = 0,
    /** Почему не вышло. `null` — всё в порядке или ещё не пробовали. */
    val error: String? = null,
)

/**
 * Синхронизация библиотеки между устройствами.
 *
 * Работает по принципу «отправить и забрать одним запросом»: устройство шлёт
 * то, что изменило, вместе со своим курсором, и получает всё, что новее.
 * Разрешение конфликтов целиком на сервере — здесь нет ни голосования, ни
 * векторных часов, потому что сливать нечего: книга и карточка это набор
 * полей, и две их версии различаются лишь тем, какая записана позже.
 *
 * Отсутствие сети — не ошибка. Библиотека живёт на устройстве и работает
 * полностью без сервера; синхронизация только доносит её до второго
 * устройства. Поэтому неудача записывается в состояние и не бросается
 * исключением: чтение из-за неё прерываться не должно.
 */
class SyncService(
    private val library: Library,
    private val settings: Settings,
    private val api: WolfyApi,
    private val companion: com.wolfy.data.companion.CompanionRepository? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Один обмен за раз. Два одновременных отправили бы одни и те же записи
    // дважды и разошлись бы курсорами.
    private val gate = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Есть ли что отправлять. */
    fun hasPending(): Boolean {
        val (books, cards) = library.pending()
        return books.isNotEmpty() || cards.isNotEmpty() || companion?.state?.value?.outgoing != null
    }

    /**
     * Обменивается с сервером.
     *
     * Возвращает `true`, если обмен состоялся. Одновременный второй вызов
     * ничего не делает и отвечает `false` — не ошибка, просто уже идёт.
     */
    suspend fun sync(waitForRunning: Boolean = true): Boolean {
        // Фоновый опрос не выстраивается в очередь за уже идущим обменом:
        // через минуту он проверит снова. Нажатая человеком кнопка, напротив,
        // честно ждёт тот же обмен и показывает его итог, а не отвечает
        // ложным «ничего не сделано».
        if (waitForRunning) {
            gate.lock()
        } else if (!gate.tryLock()) {
            return false
        }
        try {
            _status.value = _status.value.copy(running = true, error = null)

            val sent = library.snapshot()
            val (books, cards) = library.pending()
            val sentCompanion = companion?.state?.value?.outgoing
            val payload = SyncPayload(
                cursor = library.state.value.cursor,
                books = books.map { it.toSync() },
                cards = cards.map { it.toSync() },
                reading = readingSettings(),
                // Профиль едет, только если он есть и изменился с последней
                // ревизии: сверка по серверной ревизии делает отправку пустой
                // для неизменённого профиля.
                companion = sentCompanion?.toSyncCompanion(),
            )

            return when (val result = api.sync(payload)) {
                is SyncResult.Failed -> {
                    _status.value = _status.value.copy(running = false, error = result.message)
                    false
                }

                is SyncResult.Ready -> {
                    apply(result.payload, sent, sentCompanion)
                    // Файлы идут отдельно от JSON-обмена. По одному мегабайту
                    // за запрос: даже большая книга не превращается в копию в
                    // памяти телефона, а обычный sync остаётся быстрым.
                    syncFiles(result.payload)
                    _status.value = SyncStatus(
                        running = false,
                        lastSuccess = currentTimeMillis(),
                        pending = library.pending().let { (b, c) ->
                            b.size + c.size + if (companion?.state?.value?.outgoing != null) 1 else 0
                        },
                        error = null,
                    )
                    true
                }
            }
        } finally {
            gate.unlock()
        }
    }

    private fun apply(
        payload: SyncPayload,
        sent: Library.Sent,
        sentCompanion: com.wolfy.data.companion.CompanionProfile?,
    ) {
        val state = library.state.value
        val knownBooks = state.books.associateBy { it.id }
        val knownCards = state.cards.associateBy { it.id }

        val books: List<LibraryBook> = payload.books.map { it.toLibrary(knownBooks[it.id]) }
        val cards: List<Card> = payload.cards.map { it.toLibrary(knownCards[it.id]) }

        library.applyServer(cursor = payload.cursor, books = books, cards = cards, sent = sent)

        // Профиль компаньона: серверная ревизия выигрывает у местной, tombstone
        // подтверждается и гаснет внутри репозитория. Черновик редактора здесь
        // не участвует.
        payload.companion?.let { remote -> companion?.applyServer(remote.toCompanionProfile(), sentCompanion) }

        // Настройки чтения с сервера применяются только если местных ещё не
        // было. Иначе читатель, поменявший тему на телефоне, увидел бы, как
        // она перескакивает обратно после первой же синхронизации.
        payload.reading?.let { remote ->
            if (settings.current == AppSettings()) {
                runCatching { json.decodeFromJsonElement(AppSettings.serializer(), remote) }
                    .onSuccess(settings::replace)
            }
        }
    }

    private fun readingSettings(): JsonElement =
        json.encodeToJsonElement(AppSettings.serializer(), settings.current)

    private suspend fun syncFiles(payload: SyncPayload) {
        // Чтение, запись и fsync идут по одному мегабайту: на Main они
        // регулярно замирали бы кадрами. Диску всё равно, из какого
        // диспетчера его зовут, а сетевым вызовам (Ktor) — тем более.
        withContext(Dispatchers.IO) {
            val remote = payload.files.associateBy { it.bookId }
            val local = library.state.value.books.filter { !it.deleted }

            // Сначала отправляем отсутствующие в облаке файлы. Их хеш уже посчитан
            // при импорте; сервер сверит его ещё раз после потоковой записи.
            local.filter { it.readable && it.id !in remote }.forEach { book ->
                val total = library.localFileSize(book.id) ?: return@forEach
                if (total !in 1..MAX_BOOK_BYTES || book.sourceKey.isBlank()) return@forEach
                var offset = 0L
                while (offset < total) {
                    val part = library.readLocalFileChunk(book.id, offset, CHUNK_BYTES) ?: break
                    val accepted = api.uploadBookChunk(
                        book.id,
                        "${book.title.ifBlank { "book" }}.${book.format.ifBlank { "epub" }}",
                        book.sourceKey,
                        offset,
                        total,
                        part,
                    )
                    if (!accepted) break
                    offset += part.size
                }
            }

            // На втором устройстве книга появляется сразу после sync, а файл
            // докачивается следом. Пока его нет, экран честно показывает прогресс.
            remote.values.filter { file ->
                library.book(file.bookId)?.readable == false && file.size in 1..MAX_BOOK_BYTES
            }.forEach { file ->
                downloadIntoLibrary(file)
            }
        }
    }

    /** Качает файл целиком в .part, сверяет SHA-256 и только потом фиксирует. */
    private suspend fun downloadIntoLibrary(file: SyncBookFile) {
        val part = library.createDownloadedFile(file.fileName)
        if (part.isBlank()) return

        var offset = 0L
        var complete = true
        try {
            while (offset < file.size) {
                val chunk = api.downloadBookChunk(file.bookId, offset, CHUNK_BYTES) ?: run {
                    complete = false
                    break
                }
                if (chunk.bytes.isEmpty() || !library.appendDownloadedChunk(part, chunk.bytes)) {
                    complete = false
                    break
                }
                offset += chunk.bytes.size
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена синхронизации: .part сюда не докатился, оставлять нечего.
            library.discardDownloadedFile(part)
            throw e
        } catch (_: Exception) {
            // Порыв сети или битая порция — файл начнём качать заново в следующий раз.
            complete = false
        }
        if (complete) {
            // Неверный отпечаток означает побитую по дороге копию: под финальным
            // именем она жить не должна.
            if (!library.finalizeDownloadedFile(file.bookId, part, file.sha256)) {
                library.discardDownloadedFile(part)
            }
        } else {
            library.discardDownloadedFile(part)
        }
    }

    private companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val MAX_BOOK_BYTES = 256L * 1024 * 1024
    }
}
