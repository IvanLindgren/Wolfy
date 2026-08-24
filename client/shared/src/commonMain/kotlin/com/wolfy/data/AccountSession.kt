package com.wolfy.data

import com.wolfy.data.library.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
data class AccountProfile(
    val email: String = "",
    val name: String = "",
    val skipped: Boolean = false,
)

/** Локальная сессия общего аккаунта Читавука. Токен не входит в sync payload. */
class AccountSession(
    private val store: LibraryStore,
    initialToken: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val initial = read()
    private val _token = MutableStateFlow(initialToken?.takeIf { it.isNotBlank() } ?: initial.token.takeIf { it.isNotBlank() })
    val token: StateFlow<String?> = _token.asStateFlow()
    private val _profile = MutableStateFlow(AccountProfile(initial.email, initial.name, initial.skipped))
    val profile: StateFlow<AccountProfile> = _profile.asStateFlow()

    init {
        initialToken?.takeIf { it.isNotBlank() }?.let(::save)
    }

    fun save(token: String, email: String = _profile.value.email, name: String = _profile.value.name) {
        val clean = token.trim()
        if (clean.isEmpty()) return
        val record = read().copy(token = clean, email = email.trim(), name = name.trim(), skipped = false)
        store.save(FILE, json.encodeToString(record))
        _token.value = clean
        _profile.value = AccountProfile(record.email, record.name, false)
    }

    fun clear() {
        val record = read().copy(token = "", email = "", name = "", skipped = true)
        store.save(FILE, json.encodeToString(record))
        _token.value = null
        _profile.value = AccountProfile(skipped = true)
    }

    fun skip() {
        val record = read().copy(skipped = true)
        store.save(FILE, json.encodeToString(record))
        _profile.value = _profile.value.copy(skipped = true)
    }

    fun requestSignIn() {
        val record = read().copy(skipped = false)
        store.save(FILE, json.encodeToString(record))
        _profile.value = _profile.value.copy(skipped = false)
    }

    /** Случайный постоянный номер установки, а не отпечаток устройства. */
    fun deviceId(): String {
        val record = read()
        if (record.deviceId.isNotBlank()) return record.deviceId
        val bytes = Random.Default.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val id = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        store.save(FILE, json.encodeToString(record.copy(deviceId = id)))
        return id
    }

    private fun read(): SessionRecord = runCatching {
        store.load(FILE)?.let { json.decodeFromString<SessionRecord>(it) }
    }.getOrNull() ?: SessionRecord()

    @Serializable
    private data class SessionRecord(
        val token: String = "",
        val email: String = "",
        val name: String = "",
        val skipped: Boolean = false,
        val deviceId: String = "",
    )

    private companion object {
        const val FILE = "account_session"
    }
}
