package com.wolfy.ui.decks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Колоды: слова, сохранённые при чтении.
 *
 * Колода привязана к книге, а не общая. Так её и заводят: слова из «Гэтсби»
 * вспоминаются вместе с «Гэтсби», и повторять их вперемешку со словами из
 * учебника — значит лишить их единственной зацепки, которая у них есть.
 *
 * Механика повторений появится здесь же. Пока экран отвечает на вопрос
 * «сколько я набрал и из чего» — и этого достаточно, чтобы захотеть набрать
 * ещё.
 */
@Composable
fun DecksScreen(
    books: List<LibraryBook>,
    onOpenBook: (LibraryBook) -> Unit,
    onRemoveWord: (bookId: String, lemma: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    val decks = books.filter { it.savedWords > 0 }.sortedByDescending { it.savedWords }
    val total = decks.sumOf { it.savedWords }
    var opened by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.paper),
        contentPadding = PaddingValues(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    text = "Повторения",
                    style = WolfyTheme.typography.screenTitle,
                    color = colors.ink,
                )
                Text(
                    text = if (total > 0) {
                        plural(total, "слово", "слова", "слов") + " из " +
                            plural(decks.size, "книги", "книг", "книг")
                    } else {
                        "Слова попадают сюда из читалки"
                    },
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
                Rule(thick = true)
            }
        }

        if (decks.isEmpty()) {
            item { EmptyDecks() }
        }

        items(decks, key = { it.id }) { book ->
            DeckCard(
                book = book,
                expanded = opened == book.id,
                onToggle = { opened = if (opened == book.id) null else book.id },
                onOpenBook = { onOpenBook(book) },
                onRemoveWord = { onRemoveWord(book.id, it) },
            )
        }
    }
}

@Composable
private fun DeckCard(
    book: LibraryBook,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenBook: () -> Unit,
    onRemoveWord: (String) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.small))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                title = book.title,
                author = book.author,
                modifier = Modifier.width(44.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
                Text(
                    text = book.title,
                    style = WolfyTheme.typography.bookTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "★ " + plural(book.savedWords, "слово", "слова", "слов"),
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
            Text(
                text = if (expanded) "×" else "›",
                style = WolfyTheme.typography.screenTitle,
                color = colors.inkMuted,
            )
        }

        if (expanded) {
            SectionLabel("Слова колоды")
            // Слова стоят вплотную, как выписка из тетради, а не списком по
            // строке на слово: сорок строк превращают колоду в бесконечную
            // ленту, в которой не видно, сколько всего набрано.
            WordChips(words = book.deck.sorted(), onRemove = onRemoveWord)
            Text(
                text = "Долгое нажатие убирает слово из колоды",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
            Text(
                text = "Открыть книгу →",
                style = WolfyTheme.typography.caption,
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onOpenBook),
            )
        }
    }
}

/**
 * Слова колоды.
 *
 * Убрать слово можно долгим нажатием — тем же жестом, что удаляет книгу в
 * библиотеке. Одинаковый жест для всего необратимого читатель запоминает один
 * раз; разные жесты для одного и того же приходится вспоминать каждый.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun WordChips(words: List<String>, onRemove: (String) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        words.forEach { word ->
            Text(
                text = word,
                style = WolfyTheme.typography.body,
                color = colors.ink,
                modifier = Modifier
                    .background(colors.highlight, RoundedCornerShape(spacing.tight))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onRemove(word) },
                    )
                    .padding(horizontal = spacing.small, vertical = spacing.tight),
            )
        }
    }
}

@Composable
private fun EmptyDecks() {
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        WolfySticker(Sticker.Sleep, size = 130.dp)
        Text(
            text = "Повторять пока нечего",
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.ink,
        )
        Text(
            text = "Нажмите по слову в книге и добавьте его в колоду — оно появится здесь.",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
    }
}

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
