package com.wolfy.ui.discovery

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.NewsArticle
import com.wolfy.data.NewsIssue
import com.wolfy.data.NewspaperArticleResult
import com.wolfy.data.NewspaperResult
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class NewspaperUiState(
    val loading: Boolean = false,
    val issue: NewsIssue? = null,
    val opening: String? = null,
    val message: String? = null,
)

class NewspaperViewModel(
    private val api: WolfyApi,
    private val library: Library,
) : ViewModel() {
    private val _state = MutableStateFlow(NewspaperUiState())
    val state: StateFlow<NewspaperUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            change { it.copy(loading = true, message = null) }
            when (val result = api.newspaper()) {
                is NewspaperResult.Ready -> change {
                    it.copy(loading = false, issue = result.issue, message = null)
                }
                is NewspaperResult.Failed -> change {
                    it.copy(loading = false, message = result.message)
                }
            }
        }
    }

    fun open(article: NewsArticle, onReady: (LibraryBook) -> Unit) {
        if (article.link.isBlank() || _state.value.opening != null) return
        viewModelScope.launch {
            change { it.copy(opening = article.id, message = null) }
            when (val result = api.newspaperArticle(article.link)) {
                is NewspaperArticleResult.Ready -> {
                    val reading = result.reading
                    val title = reading.title.ifBlank { article.title }
                    val author = reading.author.ifBlank {
                        article.author.ifBlank { article.source.ifBlank { reading.source } }
                    }
                    val text = buildString {
                        appendLine(title)
                        reading.paragraphs.forEach { paragraph ->
                            appendLine()
                            appendLine(paragraph)
                        }
                    }.trimEnd().encodeToByteArray()
                    val book = runCatching {
                        withContext(Dispatchers.IO) {
                            library.addDownloaded(
                                bytes = text,
                                fileName = safeFileName(title) + ".txt",
                                title = title,
                                author = author.takeIf(String::isNotBlank),
                                sourceKey = "newspaper:${article.link}",
                            )
                        }
                    }.getOrElse {
                        change { it.copy(opening = null, message = "Не удалось добавить заметку в библиотеку.") }
                        return@launch
                    }
                    onReady(book)
                    change { it.copy(opening = null) }
                }
                is NewspaperArticleResult.Failed -> change {
                    it.copy(opening = null, message = result.message)
                }
            }
        }
    }

    private fun change(transform: (NewspaperUiState) -> NewspaperUiState) {
        _state.value = transform(_state.value)
    }

    private fun safeFileName(title: String): String = title
        .replace(Regex("[^A-Za-z0-9А-Яа-яЁё ._-]+"), "")
        .trim()
        .take(80)
        .ifBlank { "news" }
}
