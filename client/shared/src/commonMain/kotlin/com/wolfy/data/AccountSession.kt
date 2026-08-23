package com.wolfy.data

import com.wolfy.data.library.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Локальная сессия общего аккаунта Читавука. Токен не входит в sync payload. */
class AccountSession(
    private val store: LibraryStore,
    initialToken: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _token = MutableStateFlow(initialToken?.takeIf { it.isNotBlank() } ?: read())
    val token: StateFlow<String?> = _token.asStateFlow()

    init {
        initialToken?.takeIf { it.isNotBlank() }?.let(::save)
    }

    fun save(token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) return
        store.save(FILE, json.encodeToString(SessionRecord(clean)))
        _token.value = clean
    }

    fun clear() {
        store.save(FILE, json.encodeToString(SessionRecord()))
        _token.value = null
    }

    private fun read(): String? = runCatching {
        store.load(FILE)?.let { json.decodeFromString<SessionRecord>(it).token }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    @Serializable
    private data class SessionRecord(val token: String = "")

    private companion object {
        const val FILE = "account_session"
    }
}
