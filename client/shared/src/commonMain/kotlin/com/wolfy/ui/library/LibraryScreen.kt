package com.wolfy.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wolfy.data.library.LibraryBook
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.BookCover
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker

/**
 * Главный экран: книга, к которой стоит вернуться, и вся библиотека сеткой.
 *
 * Порядок на экране повторяет порядок вопросов читателя. Первый — «на чём я
 * остановился», и ответ занимает верх экрана целиком. Второй — «что у меня
 * вообще есть», и это сетка обложек. Списка строчками здесь нет намеренно:
 * книгу узнают в лицо, а не по строке в таблице.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpen: (LibraryBook) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    LazyVerticalGrid(
        // Ширина плитки, а не число столбцов: на телефоне помещается три
        // обложки, на окне Windows — семь, и подбирать это руками под каждый
        // размер значит промахнуться на всех остальных.
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper),
        contentPadding = PaddingValues(spacing.pageMargin),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
                state.continueReading?.let { current ->
                    ContinueCard(current, onOpen = { onOpen(current) })
                }
                LibraryHeader(count = state.books.size, onImport = onImport)
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = WolfyTheme.typography.caption,
                        color = colors.accent,
                    )
                }
            }
        }

        if (state.books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(onImport) }
        }

        items(state.books, key = { it.id }) { book ->
            BookTile(book, onOpen = { onOpen(book) })
        }
    }
}

/**
 * «Книга дня» — та, к которой читатель вернётся.
 *
 * Это не рекомендация и не подборка: приложение не советует, что читать, оно
 * помогает продолжить. Поэтому карточка одна и всегда про уже открытую книгу,
 * а если открытых нет — её нет вовсе.
 */
@Composable
private fun ContinueCard(book: LibraryBook, onOpen: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.small))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .clickable(onClick = onOpen)
            .padding(spacing.large),
        horizontalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            SectionLabel("Книга дня")
            Text(
                text = book.title,
                style = typography.bookTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            book.author?.let {
                Text(text = it, style = typography.caption, color = colors.inkMuted)
            }
            ProgressLine(book.fraction)
            Text(
                text = percent(book.fraction) + " · продолжить чтение →",
                style = typography.caption,
                color = colors.accent,
            )
        }
        BookCover(
            title = book.title,
            author = book.author,
            modifier = Modifier.height(96.dp),
        )
    }
}

@Composable
private fun LibraryHeader(count: Int, onImport: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Rule(thick = true)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Моя библиотека",
                style = WolfyTheme.typography.screenTitle,
                color = colors.ink,
            )
            Text(
                text = "+ добавить",
                style = WolfyTheme.typography.button,
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onImport),
            )
        }
        if (count > 0) {
            Text(
                text = plural(count, "книга", "книги", "книг"),
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }
    }
}

/** Обложка с процентом и счётчиком слов — плитка сетки. */
@Composable
private fun BookTile(book: LibraryBook, onOpen: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        Modifier.clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BookCover(title = book.title, author = book.author, modifier = Modifier.fillMaxWidth())

        Text(
            text = when {
                book.finished -> "прочитана ✓"
                !book.started -> "новая"
                else -> percent(book.fraction)
            },
            style = WolfyTheme.typography.button,
            color = if (book.finished) colors.partsOfSpeech.adjective else colors.ink,
        )
        Text(
            text = if (book.savedWords > 0) {
                "★ " + plural(book.savedWords, "слово", "слова", "слов")
            } else {
                "☆ 0 слов"
            },
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    val colors = WolfyTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(WolfyTheme.spacing.tight)
            .background(colors.rule, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(WolfyTheme.spacing.tight)
                .background(colors.accent, CircleShape),
        )
    }
}

/**
 * Пустая библиотека.
 *
 * Тут появляется Вульфи. Пустой экран — единственное место, где иллюстрация не
 * мешает: читать всё равно нечего, а объяснить, что делать дальше, картинкой
 * быстрее, чем абзацем.
 */
@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        WolfySticker(Sticker.Wave, size = 140.dp)
        Text(
            text = "Пока пусто",
            style = WolfyTheme.typography.screenTitle,
            color = colors.ink,
        )
        Text(
            text = "Добавьте книгу в epub, txt или pdf — и можно читать.",
            style = WolfyTheme.typography.body,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier
                .background(colors.ink, RoundedCornerShape(spacing.huge))
                .clickable(onClick = onImport)
                .padding(horizontal = spacing.xlarge, vertical = spacing.medium),
        ) {
            Text(
                text = "+ Выбрать файл",
                style = WolfyTheme.typography.button,
                color = colors.paper,
            )
        }
    }
}

private fun percent(fraction: Float): String = (fraction * 100).toInt().toString() + "%"

/**
 * «12 книг», «1 книга», «3 книги».
 *
 * Без согласования числительного счётчик читается как опечатка, а опечатка на
 * главном экране стоит дороже пятнадцати строк кода.
 */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val tens = count % 100
    val word = if (tens in 11..14) {
        many
    } else {
        when (count % 10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }
    return "$count $word"
}
