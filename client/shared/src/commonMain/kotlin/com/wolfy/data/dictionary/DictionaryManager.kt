package com.wolfy.data.dictionary

import com.wolfy.data.DefineResult
import com.wolfy.data.DictionaryDownloadResult
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.CoreSession
import com.wolfy.data.library.LibraryStore
import com.wolfy.data.library.readBundledDictionary
import com.wolfy.ffi.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Скачивание и использование необязательного офлайн-словаря. */
class DictionaryManager(
    private val session: CoreSession,
    private val store: LibraryStore,
    private val api: WolfyApi,
) {
    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<DictionaryStatus> = _status.asStateFlow()

    /** Пользователь может вернуться к предложению из настроек. */
    fun offer() {
        if (_status.value !is DictionaryStatus.Downloading) {
            _status.value = DictionaryStatus.Offer
        }
    }

    fun dismissOffer() {
        store.save(CHOICE, DECLINED)
        _status.value = DictionaryStatus.Declined
    }

    suspend fun download() {
        if (_status.value is DictionaryStatus.Downloading) return
        _status.value = DictionaryStatus.Downloading(null)

        // Релизный APK и Windows-установщик уже несут проверенный архив.
        // Установка из него делает первый запуск независимым от сети и от
        // того, какой процесс случайно занял localhost:8080.
        val bundled = withContext(Dispatchers.Default) { readBundledDictionary() }
        if (bundled != null && install(bundled)) return

        when (val result = api.downloadDictionary { progress ->
            _status.value = DictionaryStatus.Downloading(progress?.coerceIn(0f, 1f))
        }) {
            is DictionaryDownloadResult.Ready -> {
                if (!install(result.bytes)) {
                    _status.value = DictionaryStatus.Failed("Файл словаря повреждён.")
                }
            }

            is DictionaryDownloadResult.Failed -> {
                _status.value = DictionaryStatus.Failed(result.message)
            }
        }
    }

    private suspend fun install(compressed: ByteArray): Boolean {
        val installed = runCatching {
            withContext(Dispatchers.Default) {
                val path = store.installDictionary(compressed)
                val check = session.define("library", path)
                check.dictionaryAvailable == true && check.definition != null
            }
        }.getOrDefault(false)

        if (installed) {
            store.save(CHOICE, INSTALLED)
            _status.value = DictionaryStatus.Ready
        }
        return installed
    }

    /**
     * Сначала ищет локально. Сервер вызывается только если словарь не
     * установлен или перестал читаться; неизвестное локальному словарю слово
     * не создаёт лишний сетевой запрос к точно такой же базе.
     */
    suspend fun define(word: String): DictionaryEntry? {
        val path = store.dictionaryPath()
        if (path.isNotEmpty()) {
            val local = withContext(Dispatchers.Default) { session.define(word, path) }
            if (local.dictionaryAvailable == true) return local.definition
        }

        return when (val remote = api.define(word)) {
            is DefineResult.Ready -> remote.entry
            DefineResult.Missing, DefineResult.Failed -> null
        }
    }

    private fun initialStatus(): DictionaryStatus = when {
        store.dictionaryPath().isNotEmpty() -> DictionaryStatus.Ready
        store.load(CHOICE) == DECLINED -> DictionaryStatus.Declined
        else -> DictionaryStatus.Offer
    }

    private companion object {
        const val CHOICE = "dictionary_choice"
        const val DECLINED = "declined"
        const val INSTALLED = "installed"
    }
}

sealed interface DictionaryStatus {
    data object Offer : DictionaryStatus
    data object Ready : DictionaryStatus
    data object Declined : DictionaryStatus
    data class Downloading(val progress: Float?) : DictionaryStatus
    data class Failed(val message: String) : DictionaryStatus
}
