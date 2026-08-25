package com.wolfy.ui.discovery

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.AccountSession
import com.wolfy.data.ActionResult
import com.wolfy.data.DiscoveryFeedResult
import com.wolfy.data.DiscoveryItem
import com.wolfy.data.DiscoveryProfile
import com.wolfy.data.DiscoveryProfileResult
import com.wolfy.data.DownloadResult
import com.wolfy.data.LoginResult
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class DiscoveryUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val needsOnboarding: Boolean = false,
    val email: String = "",
    val password: String = "",
    val level: String = "B2",
    val genres: Set<String> = emptySet(),
    val items: List<DiscoveryItem> = emptyList(),
    val message: String? = null,
    val adding: Set<String> = emptySet(),
)

class DiscoveryViewModel(
    private val api: WolfyApi,
    private val session: AccountSession,
    private val library: Library,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoveryUiState(signedIn = session.token.value != null))
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()

    init {
        if (session.token.value != null) refresh()
    }

    fun setEmail(value: String) = change { it.copy(email = value, message = null) }
    fun setPassword(value: String) = change { it.copy(password = value, message = null) }
    fun setLevel(value: String) = change { it.copy(level = value) }

    fun toggleGenre(value: String) = change { current ->
        val genres = if (value in current.genres) current.genres - value else current.genres + value
        current.copy(genres = genres, message = null)
    }

    fun login() {
        val current = _state.value
        if (current.email.isBlank() || current.password.isBlank()) {
            change { it.copy(message = "Введите почту и пароль от Читавука.") }
            return
        }
        viewModelScope.launch {
            change { it.copy(loading = true, message = null) }
            when (val result = api.login(current.email, current.password)) {
                is LoginResult.Failed -> change { it.copy(loading = false, message = result.message) }
                is LoginResult.Ready -> {
                    session.save(result.token)
                    change { it.copy(signedIn = true, loading = false, password = "") }
                    refresh()
                }
            }
        }
    }

    fun logout() {
        session.clear()
        _state.value = DiscoveryUiState()
    }

    fun saveOnboarding() {
        val current = _state.value
        if (current.genres.isEmpty()) {
            change { it.copy(message = "Выберите хотя бы один жанр.") }
            return
        }
        viewModelScope.launch {
            change { it.copy(loading = true, message = null) }
            when (
                val result = api.saveDiscoveryProfile(
                    DiscoveryProfile(
                        englishLevel = current.level,
                        genres = current.genres.sorted(),
                        onboardingComplete = true,
                    ),
                )
            ) {
                is DiscoveryProfileResult.Ready -> {
                    change { it.copy(loading = false, needsOnboarding = false) }
                    loadFeed()
                }
                is DiscoveryProfileResult.Failed -> change {
                    it.copy(loading = false, message = result.message)
                }
                DiscoveryProfileResult.SignedOut -> signedOut()
            }
        }
    }

    fun like(item: DiscoveryItem) {
        if (item.liked) return
        change { state ->
            state.copy(items = state.items.map { if (it.id == item.id) it.copy(liked = true) else it })
        }
        viewModelScope.launch {
            when (val result = api.likeDiscoveryItem(item.id)) {
                ActionResult.Ready -> Unit
                ActionResult.SignedOut -> signedOut()
                is ActionResult.Failed -> change { state ->
                    state.copy(
                        items = state.items.map { if (it.id == item.id) it.copy(liked = false) else it },
                        message = result.message,
                    )
                }
            }
        }
    }

    fun add(item: DiscoveryItem) {
        if (item.id in _state.value.adding || item.added) return
        viewModelScope.launch {
            change { it.copy(adding = it.adding + item.id, message = null) }
            when (val result = api.downloadDiscoveryItem(item)) {
                is DownloadResult.Ready -> {
                    withContext(Dispatchers.IO) {
                        library.addDownloaded(
                            bytes = result.bytes,
                            fileName = result.fileName,
                            title = item.title,
                            author = item.author.takeIf(String::isNotBlank),
                            sourceKey = result.sourceKey,
                        )
                    }
                    change { state ->
                        state.copy(
                            adding = state.adding - item.id,
                            items = state.items.map {
                                if (it.id == item.id) it.copy(liked = true, added = true) else it
                            },
                            message = "Книга добавлена в библиотеку.",
                        )
                    }
                }
                is DownloadResult.Failed -> change {
                    it.copy(adding = it.adding - item.id, message = result.message)
                }
                DownloadResult.SignedOut -> signedOut()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            change { it.copy(loading = true, message = null, signedIn = session.token.value != null) }
            when (val result = api.discoveryProfile()) {
                is DiscoveryProfileResult.Ready -> {
                    val profile = result.profile
                    change {
                        it.copy(
                            loading = false,
                            needsOnboarding = !profile.onboardingComplete,
                            level = profile.englishLevel.ifBlank { "B2" },
                            genres = profile.genres.toSet(),
                        )
                    }
                    if (profile.onboardingComplete) loadFeed()
                }
                is DiscoveryProfileResult.Failed -> change {
                    it.copy(loading = false, message = result.message)
                }
                DiscoveryProfileResult.SignedOut -> signedOut()
            }
        }
    }

    private suspend fun loadFeed() {
        change { it.copy(loading = true) }
        when (val result = api.discoveryFeed()) {
            is DiscoveryFeedResult.Ready -> change {
                it.copy(loading = false, items = result.page.items, needsOnboarding = false)
            }
            is DiscoveryFeedResult.Failed -> change { it.copy(loading = false, message = result.message) }
            DiscoveryFeedResult.NeedsOnboarding -> change {
                it.copy(loading = false, needsOnboarding = true)
            }
            DiscoveryFeedResult.SignedOut -> signedOut()
        }
    }

    private fun signedOut() {
        session.clear()
        _state.value = DiscoveryUiState(message = "Сессия закончилась. Войдите снова.")
    }

    private fun change(transform: (DiscoveryUiState) -> DiscoveryUiState) {
        _state.value = transform(_state.value)
    }
}
