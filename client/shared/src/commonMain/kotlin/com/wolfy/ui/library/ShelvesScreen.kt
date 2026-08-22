package com.wolfy.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.Shelf
import com.wolfy.theme.WolfyTheme
import androidx.compose.ui.unit.IntOffset
import com.wolfy.widgets.BookCover
import com.wolfy.widgets.DragBoard
import com.wolfy.widgets.dragSource
import com.wolfy.widgets.dropTarget
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.widgets.pressable
import kotlin.math.roundToInt

/**
 * Полки: как читатель разложил свои книги.
 *
 * Книга стоит ровно на одной полке — полка это место, а не ярлык. Несколько
 * ярлыков на книгу выглядят гибче, но полка, на которой книга «частично
 * стоит», перестаёт быть полкой, и разложить библиотеку уже не получается.
 *
 * Разложить книгу можно двумя способами, и оба нужны. Перетаскиванием — как на
 * макете и как это делают руками с настоящей книгой. И касанием по названию
 * полки под обложкой — потому что тащить через список из сорока книг на
 * телефоне работа, а не жест, и одно касание там быстрее прицеливания.
 */
@Composable
fun ShelvesScreen(
    state: LibraryUiState,
    onOpen: (LibraryBook) -> Unit,
    onCreateShelf: (String) -> Unit,
    onRemoveShelf: (String) -> Unit,
    onMove: (bookId: String, shelf: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    var expanded by remember { mutableStateOf<String?>(null) }
    val unshelved = state.books.filter { it.shelf == null }
    val board = remember { DragBoard() }

    Box(modifier.fillMaxSize().background(colors.paper)) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    text = "Полки",
                    style = WolfyTheme.typography.screenTitle,
                    color = colors.ink,
                )
                Text(
                    text = "Перетащите книгу на полку или коснитесь названия под ней",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
                Rule(thick = true)
            }
        }

        item { NewShelfRow(onCreate = onCreateShelf) }

        if (state.shelves.isEmpty()) {
            item { NoShelves() }
        }

        items(state.shelves, key = { it.name }) { shelf ->
            val books = state.books.filter { it.shelf == shelf.name }
            ShelfCard(
                shelf = shelf,
                books = books,
                expanded = expanded == shelf.name,
                board = board,
                hovered = board.hovered == shelf.name,
                onToggle = { expanded = if (expanded == shelf.name) null else shelf.name },
                onOpen = onOpen,
                onRemove = { onRemoveShelf(shelf.name) },
                onTakeOff = { onMove(it.id, null) },
                onDropped = { bookId -> onMove(bookId, shelf.name) },
            )
        }

        if (unshelved.isNotEmpty()) {
            item {
                Column(
                    Modifier.dropTarget(board, UNSHELVED),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    Rule()
                    SectionLabel(
                        if (board.hovered == UNSHELVED) {
                            "Не разобрано — отпустите здесь"
                        } else {
                            "Не разобрано"
                        },
                    )
                }
            }
            items(unshelved, key = { it.id }) { book ->
                UnshelvedBook(
                    book = book,
                    shelves = state.shelves,
                    board = board,
                    onOpen = { onOpen(book) },
                    onMove = { shelf -> onMove(book.id, shelf) },
                    onDropped = { target -> onMove(book.id, target.takeIf { it != UNSHELVED }) },
                )
            }
        }
    }

        DragGhost(board)
    }
}

/**
 * Подпись под пальцем.
 *
 * Без неё перетаскивание превращается в угадывание: палец закрывает обложку, и
 * понять, тащишь ты книгу или просто ведёшь по экрану, невозможно.
 */
@Composable
private fun BoxScope.DragGhost(board: DragBoard) {
    val dragged = board.dragged ?: return
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Text(
        text = dragged.label,
        style = WolfyTheme.typography.caption,
        color = colors.onInverse,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .offset {
                // Подпись ставится над пальцем, а не под ним: то, что ровно
                // под пальцем, палец и закрывает.
                IntOffset(
                    x = dragged.position.x.roundToInt() - 60,
                    y = dragged.position.y.roundToInt() - 80,
                )
            }
            .background(colors.inverse, RoundedCornerShape(spacing.tight))
            .padding(horizontal = spacing.small, vertical = spacing.tight),
    )
}

/**
 * Карточка полки.
 *
 * Корешки слева — не украшение: по ним полку узнают быстрее, чем по названию,
 * ровно как на настоящей полке. Цвета берутся из тех же обложек, что лежат в
 * сетке библиотеки, поэтому полка и её содержимое выглядят одним целым.
 */
@Composable
private fun ShelfCard(
    shelf: Shelf,
    books: List<LibraryBook>,
    expanded: Boolean,
    board: DragBoard,
    hovered: Boolean,
    onToggle: () -> Unit,
    onOpen: (LibraryBook) -> Unit,
    onRemove: () -> Unit,
    onTakeOff: (LibraryBook) -> Unit,
    onDropped: (String) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        Modifier
            .fillMaxWidth()
            .dropTarget(board, shelf.name)
            .background(colors.surface, RoundedCornerShape(spacing.small))
            .border(
                if (hovered) spacing.hair else spacing.rule,
                if (hovered) colors.accent else colors.rule,
                RoundedCornerShape(spacing.small),
            )
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Row(
            Modifier.fillMaxWidth().pressable(onClick = onToggle),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spines(books)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
                Text(
                    text = shelf.name,
                    style = WolfyTheme.typography.bookTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (hovered) "отпустите здесь" else shelfSummary(books),
                    style = WolfyTheme.typography.caption,
                    color = if (hovered) colors.accent else colors.inkMuted,
                )
            }
            Text(
                text = if (expanded) "×" else "›",
                style = WolfyTheme.typography.screenTitle,
                color = colors.inkMuted,
            )
        }

        if (expanded) {
            if (books.isEmpty()) {
                Text(
                    text = "Полка пуста. Книги для неё — ниже, в «Не разобрано».",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    items(books, key = { it.id }) { book ->
                        Column(
                            Modifier.width(88.dp),
                            verticalArrangement = Arrangement.spacedBy(spacing.tight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BookCover(
                                title = book.title,
                                author = book.author,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dragSource(board, book.id, book.title) { target ->
                                        // Книга, положенная обратно на свою же
                                        // полку, никуда не переезжает.
                                        if (target != shelf.name) onDropped(book.id)
                                    }
                                    .pressable { onOpen(book) },
                            )
                            Text(
                                text = "снять с полки",
                                style = WolfyTheme.typography.caption,
                                color = colors.inkMuted,
                                modifier = Modifier.pressable { onTakeOff(book) },
                            )
                        }
                    }
                }
            }
            Text(
                text = "Удалить полку",
                style = WolfyTheme.typography.caption,
                color = colors.accent,
                modifier = Modifier.pressable(onClick = onRemove),
            )
        }
    }
}

/** Корешки книг полки — четыре первых, как и на настоящей полке видно немного. */
@Composable
private fun Spines(books: List<LibraryBook>) {
    val colors = WolfyTheme.colors
    val heights = listOf(0.86f, 1f, 0.72f, 0.94f)
    val palette = listOf(colors.ink, colors.accent, colors.inkMuted, colors.gold)

    Row(
        Modifier.height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (books.isEmpty()) {
            // Пустая полка — пустая рамка, а не отсутствие места: иначе
            // карточки полок разъезжаются по высоте и список рвётся.
            Box(
                Modifier
                    .width(38.dp)
                    .height(44.dp)
                    .border(WolfyTheme.spacing.rule, colors.rule),
            )
            return@Row
        }
        books.take(4).forEachIndexed { index, book ->
            Box(
                Modifier
                    .width(9.dp)
                    .height(44.dp * heights[index % heights.size])
                    .background(palette[fingerprint(book.title) % palette.size]),
            )
        }
    }
}

/** Книга, которую ещё никуда не поставили. */
@Composable
private fun UnshelvedBook(
    book: LibraryBook,
    shelves: List<Shelf>,
    board: DragBoard,
    onOpen: () -> Unit,
    onMove: (String) -> Unit,
    onDropped: (String) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        BookCover(
            title = book.title,
            author = book.author,
            modifier = Modifier
                .width(48.dp)
                .dragSource(board, book.id, book.title, onDropped = onDropped)
                .pressable(onClick = onOpen),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(
                text = book.title,
                style = WolfyTheme.typography.body,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (shelves.isEmpty()) {
                Text(
                    text = "Сначала заведите полку",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    shelves.take(4).forEach { shelf ->
                        Text(
                            text = "на «${shelf.name}»",
                            style = WolfyTheme.typography.caption,
                            color = colors.accent,
                            maxLines = 1,
                            modifier = Modifier
                                .border(
                                    spacing.rule,
                                    colors.rule,
                                    RoundedCornerShape(spacing.huge),
                                )
                                .pressable { onMove(shelf.name) }
                                .padding(horizontal = spacing.small, vertical = spacing.tight),
                        )
                    }
                }
            }
        }
    }
}

/** Поле для новой полки. */
@Composable
private fun NewShelfRow(onCreate: (String) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    var name by remember { mutableStateOf("") }

    Row(
        Modifier
            .fillMaxWidth()
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = WolfyTheme.typography.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f).padding(vertical = spacing.small),
            decorationBox = { field ->
                if (name.isEmpty()) {
                    Text(
                        text = "Новая полка — например, «Классика»",
                        style = WolfyTheme.typography.body,
                        color = colors.inkMuted,
                    )
                }
                field()
            },
        )
        Text(
            text = "+ создать",
            style = WolfyTheme.typography.button,
            color = if (name.isBlank()) colors.inkMuted else colors.accent,
            modifier = Modifier.pressable(enabled = name.isNotBlank()) {
                onCreate(name)
                name = ""
            },
        )
    }
}

@Composable
private fun NoShelves() {
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        WolfySticker(Sticker.Scroll, size = 110.dp)
        Text(
            text = "Полок пока нет",
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.ink,
        )
        Text(
            text = "Полка нужна, когда книг становится больше десятка.",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
    }
}

/** Ключ области «не разобрано»: пустого имени у настоящей полки не бывает. */
private const val UNSHELVED = ""

private fun shelfSummary(books: List<LibraryBook>): String {
    val reading = books.count { it.started && !it.finished }
    val head = plural(books.size, "книга", "книги", "книг")
    return if (reading > 0) "$head · $reading читаю сейчас" else head
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

/** То же устойчивое число, что красит обложки, — чтобы корешок совпал с ней. */
private fun fingerprint(text: String): Int {
    var value = 7
    for (character in text) {
        value = (value * 31 + character.code) and 0x7FFFFFF
    }
    return value
}
