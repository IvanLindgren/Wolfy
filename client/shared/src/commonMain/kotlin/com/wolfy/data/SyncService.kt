package com.wolfy.data

import com.wolfy.data.library.Card
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        return books.isNotEmpty() || cards.isNotEmpty()
    }

    /**
     * Обменивается с сервером.
     *
     * Возвращает `true`, если обмен состоялся. Одновременный второй вызов
     * ничего не делает и отвечает `false` — не ошибка, просто уже идёт.
     */
    suspend fun sync(): Boolean {
        if (!gate.tryLock()) return false
        try {
            _status.value = _status.value.copy(running = true, error = null)

            val sent = library.snapshot()
            val (books, cards) = library.pending()
            val payload = SyncPayload(
                cursor = library.state.value.cursor,
                books = books.map { it.toSync() },
                cards = cards.map { it.toSync() },
                reading = readingSettings(),
            )

            return when (val result = api.sync(payload)) {
                is SyncResult.Failed -> {
                    _status.value = _status.value.copy(running = false, error = result.message)
                    false
                }

                is SyncResult.Ready -> {
                    apply(result.payload, sent)
                    _status.value = SyncStatus(
                        running = false,
                        lastSuccess = currentTimeMillis(),
                        pending = library.pending().let { (b, c) -> b.size + c.size },
                        error = null,
                    )
                    true
                }
            }
        } finally {
            gate.unlock()
        }
    }

    private fun apply(payload: SyncPayload, sent: Library.Sent) {
        val state = library.state.value
        val knownBooks = state.books.associateBy { it.id }
        val knownCards = state.cards.associateBy { it.id }

        val books: List<LibraryBook> = payload.books.map { it.toLibrary(knownBooks[it.id]) }
        val cards: List<Card> = payload.cards.map { it.toLibrary(knownCards[it.id]) }

        library.applyServer(cursor = payload.cursor, books = books, cards = cards, sent = sent)

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
}
