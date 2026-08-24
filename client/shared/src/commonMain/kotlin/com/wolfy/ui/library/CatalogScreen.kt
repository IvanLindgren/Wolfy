package com.wolfy.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wolfy.data.CatalogueBook
import com.wolfy.data.library.LibraryBook
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Appear
import com.wolfy.widgets.BookCover
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.widgets.pressable

/**
 * Каталог Открытой библиотеки.
 *
 * Миллионы свободных книг без выхода из приложения: набрал «Шерлок Холмс» —
 * скачал — читаешь. Поиск и скачивание идут через свой сервер, как и всё
 * остальное, что ходит наружу; файл книги после скачивания живёт на
 * устройстве, как и всякая книга библиотеки.
 */
@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onDownload: (CatalogueBook) -> Unit,
    onOpen: (LibraryBook) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .padding(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "← в библиотеку",
                style = WolfyTheme.typography.button,
                color = colors.accent,
                modifier = Modifier.pressable(onClick = onBack),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
            Text(
                text = "Каталог книг",
                style = WolfyTheme.typography.screenTitle,
                color = colors.ink,
            )
            Text(
                text = "Открытая библиотека: свободные книги на английском. " +
                    "Найдите классику — от Конан Дойла до Уэллса.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }

        SearchRow(
            query = state.query,
            searching = state.searching,
            onQuery = onQuery,
            onSearch = onSearch,
        )

        state.message?.let { message ->
            Appear(0) {
                Text(
                    text = message,
                    style = WolfyTheme.typography.caption,
                    color = if (state.downloaded.isEmpty()) colors.accent else colors.partsOfSpeech.adjective,
                )
            }
        }

        Rule()

        val motion = WolfyTheme.motion
        AnimatedContent(
            targetState = state.searching,
            transitionSpec = {
                fadeIn(tween(motion.quick, easing = Curves.Paper)) togetherWith
                    fadeOut(tween(motion.instant, easing = Curves.Paper))
            },
            label = "catalog results",
        ) { searching ->
            when {
                searching -> Searching()
                state.results.isEmpty() -> NothingFound(searched = state.searched)
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    items(state.results, key = { it.id }) { book ->
                        FoundBook(
                            book = book,
                            downloading = book.id in state.downloading,
                            added = state.downloaded[book.id],
                            onDownload = { onDownload(book) },
                            onOpen = onOpen,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier
            .fillMaxWidth()
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = WolfyTheme.typography.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f).padding(vertical = spacing.small),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Что почитать? Например, «Sherlock Holmes»",
                            style = WolfyTheme.typography.body,
                            color = colors.inkMuted,
                        )
                    }
                    field()
                }
            },
        )
        if (searching) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(vertical = spacing.small),
            )
        } else {
            Text(
                text = "искать",
                style = WolfyTheme.typography.button,
                color = if (query.isBlank()) colors.inkMuted else colors.accent,
                modifier = Modifier.pressable(enabled = query.isNotBlank(), onClick = onSearch),
            )
        }
    }
}

/** Одна находка: обложка-набросок, название, автор и кнопка скачивания. */
@Composable
private fun FoundBook(
    book: CatalogueBook,
    downloading: Boolean,
    added: LibraryBook?,
    onDownload: () -> Unit,
    onOpen: (LibraryBook) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.small))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .padding(spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            title = book.title,
            author = book.author.takeIf(String::isNotBlank),
            modifier = Modifier.padding(vertical = spacing.hair).width(72.dp),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.hair),
        ) {
            Text(
                text = book.title,
                style = WolfyTheme.typography.bookTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                book.author.takeIf(String::isNotBlank)?.let { append(it) }
                if (book.year > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(book.year)
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
            val label = when {
                added != null -> "в библиотеке · открыть"
                downloading -> "скачивается…"
                else -> "скачать"
            }
            Text(
                text = label,
                style = WolfyTheme.typography.button,
                color = when {
                    added != null -> colors.partsOfSpeech.adjective
                    downloading -> colors.inkMuted
                    else -> colors.accent
                },
                modifier = Modifier.pressable(
                    enabled = !downloading,
                    onClick = { if (added != null) onOpen(added) else onDownload() },
                ),
            )
        }
    }
}

@Composable
private fun Searching() {
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        WolfySticker(Sticker.Scroll, size = 96.dp)
        SectionLabel("Листаем каталог…")
    }
}

@Composable
private fun NothingFound(searched: Boolean) {
    if (!searched) return
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        WolfySticker(Sticker.Thinking, size = 96.dp)
        Text(
            text = "Ничего не нашлось. Попробуйте другое имя автора или название на английском.",
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.inkMuted,
        )
    }
}
