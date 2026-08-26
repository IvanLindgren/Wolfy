package com.wolfy.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
import com.wolfy.widgets.pressable

/**
 * Главный экран: книга, к которой стоит вернуться, и вся библиотека сеткой.
 *
 * Порядок на экране повторяет порядок вопросов читателя. Первый — «на чём я
 * остановился», и ответ занимает верх экрана целиком. Второй — «что у меня
 * вообще есть», и это сетка обложек. Списка строчками здесь нет намеренно:
 * книгу узнают в лицо, а не по строке в таблице.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpen: (LibraryBook) -> Unit,
    onImport: () -> Unit,
    onShoot: () -> Unit,
    onRemove: (LibraryBook) -> Unit,
    /** Поставить книге свою обложку из галереи. */
    onRequestCover: (LibraryBook) -> Unit = {},
    /** Убрать свою обложку, вернув набранную. */
    onClearCover: (LibraryBook) -> Unit = {},
    /** Открыть каталог Открытой библиотеки. */
    onCatalog: () -> Unit = {},
    /** Своя обложка книги, готовая к показу; `null` — обложки нет. */
    coverOf: (String) -> ImageBitmap? = { null },
    /** Фоновая загрузка обложки только для видимой плитки. */
    onCoverVisible: (String) -> Unit = {},
    /** Обмен с облаком и проверка обновления по жесту пользователя. */
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
    LazyVerticalGrid(
        // Ширина плитки, а не число столбцов: на телефоне помещается три
        // обложки, на окне Windows — семь, и подбирать это руками под каждый
        // размер значит промахнуться на всех остальных.
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper),
        contentPadding = PaddingValues(spacing.pageMargin),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
                state.continueReading?.let { current ->
                    LaunchedEffect(current.id, state.coversVersion) { onCoverVisible(current.id) }
                    ContinueCard(
                        book = current,
                        savedWords = state.deckSize(current.id),
                        cover = coverOf(current.id),
                        onOpen = { onOpen(current) },
                    )
                }
                LibraryHeader(
                    count = state.books.size,
                    recognizing = state.recognizing,
                    onImport = onImport,
                    onShoot = onShoot,
                    onCatalog = onCatalog,
                )
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
            val key = "${book.id}:${state.coversVersion}"
            val cover = remember(key) { coverOf(book.id) }
            LaunchedEffect(book.id, state.coversVersion) { onCoverVisible(book.id) }
            BookTile(
                book = book,
                savedWords = state.deckSize(book.id),
                cover = cover,
                onOpen = { onOpen(book) },
                onRemove = { onRemove(book) },
                onRequestCover = { onRequestCover(book) },
                onClearCover = { onClearCover(book) },
            )
        }
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
private fun ContinueCard(
    book: LibraryBook,
    savedWords: Int,
    cover: ImageBitmap?,
    onOpen: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.small))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .pressable(onClick = onOpen)
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
            cover = cover,
            modifier = Modifier.height(96.dp),
        )
    }
}

@Composable
private fun LibraryHeader(
    count: Int,
    recognizing: Boolean,
    onImport: () -> Unit,
    onShoot: () -> Unit,
    onCatalog: () -> Unit,
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.large)) {
                Text(
                    text = "+ добавить",
                    style = WolfyTheme.typography.button,
                    color = colors.accent,
                    modifier = Modifier.pressable(onClick = onImport),
                )
                // Съёмка страницы бумажной книги. Стоит рядом с добавлением
                // файла, а не в отдельном разделе: и то, и другое отвечает на
                // вопрос «как сюда попадает книга».
                Text(
                    text = if (recognizing) "распознаётся…" else "снять страницу",
                    style = WolfyTheme.typography.button,
                    color = if (recognizing) colors.inkMuted else colors.accent,
                    modifier = Modifier.pressable(enabled = !recognizing, onClick = onShoot),
                )
            }
        }
        // Каталог — третий способ пополнить библиотеку: без файла на руках и
        // без выхода из приложения.
        Text(
            text = "из Открытой библиотеки →",
            style = WolfyTheme.typography.button,
            color = colors.accent,
            modifier = Modifier.pressable(onClick = onCatalog),
        )
        if (recognizing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WolfySticker(Sticker.Thinking, size = 44.dp)
                Text(
                    text = "Вульфи читает снимок. Это занимает несколько секунд.",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
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

/**
 * Обложка с процентом и счётчиком слов — плитка сетки.
 *
 * Долгое нажатие открывает действия на месте плитки: своя обложка и удаление.
 * Книга — это часы чтения и накопленная колода, и кнопка «удалить», по
 * которой можно попасть пальцем случайно, здесь стоит дороже, чем
 * неудобство долгого нажатия.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: LibraryBook,
    savedWords: Int,
    cover: ImageBitmap?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onRequestCover: () -> Unit,
    onClearCover: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    var menu by remember { mutableStateOf(false) }

    if (menu) {
        TileMenu(
            bookTitle = book.title,
            savedWords = savedWords,
            hasCustomCover = cover != null,
            onSetCover = {
                menu = false
                onRequestCover()
            },
            onClearCover = {
                menu = false
                onClearCover()
            },
            onRemove = {
                menu = false
                onRemove()
            },
            onCancel = { menu = false },
        )
        return
    }

    Column(
        Modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = { menu = true },
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BookCover(title = book.title, author = book.author, cover = cover, modifier = Modifier.fillMaxWidth())

        Text(
            text = when {
                // Книга без файла приехала по синхронизации: прогресс у неё
                // есть, а открыть её здесь нечем, и молчать об этом нельзя.
                !book.readable -> "нет файла"
                book.finished -> "прочитана"
                !book.started -> "новая"
                else -> percent(book.fraction)
            },
            style = WolfyTheme.typography.button,
            color = when {
                !book.readable -> colors.inkMuted
                book.finished -> colors.partsOfSpeech.adjective
                else -> colors.ink
            },
        )
        Text(
            text = plural(savedWords, "слово", "слова", "слов"),
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Действия над книгой — на месте самой плитки, без диалогов поверх. */
@Composable
private fun TileMenu(
    bookTitle: String,
    savedWords: Int,
    hasCustomCover: Boolean,
    onSetCover: () -> Unit,
    onClearCover: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        Modifier
            .fillMaxWidth()
            .border(spacing.rule, colors.accent, RoundedCornerShape(spacing.tight))
            .padding(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = bookTitle,
            style = WolfyTheme.typography.button,
            color = colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "поставить обложку",
            style = WolfyTheme.typography.button,
            color = colors.accent,
            modifier = Modifier.pressable(onClick = onSetCover),
        )
        if (hasCustomCover) {
            Text(
                text = "убрать свою обложку",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
                modifier = Modifier.pressable(onClick = onClearCover),
            )
        }
        Rule()
        Text(
            text = "удалить книгу",
            style = WolfyTheme.typography.button,
            color = colors.accent,
            modifier = Modifier.pressable(onClick = onRemove),
        )
        if (savedWords > 0) {
            // Колода уходит вместе с книгой, и узнать об этом после — хуже
            // всего, что может случиться на этом экране.
            Text(
                text = "Вместе с ней пропадёт колода: " +
                    plural(savedWords, "слово", "слова", "слов"),
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = "отмена",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
            modifier = Modifier.pressable(onClick = onCancel),
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
            text = "Добавьте книгу в epub, txt или pdf. Можно также выбрать бесплатную книгу из каталога.",
            style = WolfyTheme.typography.body,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier
                .background(colors.inverse, RoundedCornerShape(spacing.huge))
                .pressable(onClick = onImport)
                .padding(horizontal = spacing.xlarge, vertical = spacing.medium),
        ) {
            Text(
                text = "+ Выбрать файл",
                style = WolfyTheme.typography.button,
                color = colors.onInverse,
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
