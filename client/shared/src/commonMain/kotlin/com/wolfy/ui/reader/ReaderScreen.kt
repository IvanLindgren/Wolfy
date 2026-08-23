package com.wolfy.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.wolfy.widgets.pressable
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.ui.card.WordCardSheet
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.ChapterHeading
import com.wolfy.widgets.DropCapParagraph
import com.wolfy.widgets.ReaderParagraph
import com.wolfy.widgets.ReaderQuote
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel

/**
 * Экран чтения.
 *
 * Без состояния: всё, что он умеет, — нарисовать переданное и сообщить о
 * нажатии. Так его можно показать в превью и проверить тестом, не поднимая ни
 * ядра, ни сети.
 */
@Composable
fun ReaderScreen(
    state: ReaderState,
    onWordTap: (Int, com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
    onDismissCard: () -> Unit,
    onSaveWord: () -> Unit,
    onSavePhrase: () -> Unit,
    onPronounce: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClose: () -> Unit,
    onScrolled: (Float) -> Unit,
    onChapter: (Int) -> Unit,
    onOpenRule: (String) -> Unit,
    theme: ReadingTheme,
    fontScale: Float,
    lineScale: Float,
    onThemeChange: (ReadingTheme) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLineScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    var contentsOpen by remember { mutableStateOf(false) }
    var readingSettingsOpen by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize()) {
            ReaderTopBar(
                state = state,
                onClose = onClose,
                onOpenContents = { contentsOpen = true },
                onOpenSettings = { readingSettingsOpen = !readingSettingsOpen },
            )
            AnimatedVisibility(
                visible = readingSettingsOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ReaderQuickSettings(
                    theme = theme,
                    fontScale = fontScale,
                    lineScale = lineScale,
                    onThemeChange = onThemeChange,
                    onFontScaleChange = onFontScaleChange,
                    onLineScaleChange = onLineScaleChange,
                )
            }

            when {
                state.error != null -> Message(state.error)
                state.loading -> Message("Книга открывается…")
                else -> ChapterBody(
                    state = state,
                    onWordTap = onWordTap,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onScrolled = onScrolled,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ContentsSheet(
            visible = contentsOpen,
            chapters = state.chapters,
            current = state.chapterIndex,
            onSelect = {
                contentsOpen = false
                onChapter(it)
            },
            onDismiss = { contentsOpen = false },
        )

        WordCardSheet(
            state = state.card,
            onDismiss = onDismissCard,
            onSave = onSaveWord,
            onSavePhrase = onSavePhrase,
            onPronounce = onPronounce,
            onOpenRule = onOpenRule,
        )
    }
}

/** Шапка: глава и полоса прогресса чтения. */
@Composable
private fun ReaderTopBar(
    state: ReaderState,
    onClose: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val progress = if (state.chapterCount > 0) {
        (state.chapterIndex + 1).toFloat() / state.chapterCount
    } else {
        0f
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin, vertical = spacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Возврат в библиотеку — слева и текстом, а не значком: значок
            // «назад» в шапке читалки читатели путают с переходом на
            // предыдущую страницу, и промах стоит потерянного места в книге.
            SectionLabel(
                text = "‹ библиотека",
                modifier = Modifier.pressable(onClick = onClose),
            )
            // Название главы — вход в оглавление. Отдельный значок для этого
            // не нужен: читатель и так смотрит сюда, чтобы понять, где он.
            SectionLabel(
                text = state.chapterTitle.ifBlank { state.bookTitle },
                modifier = Modifier.pressable(onClick = onOpenContents),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                SectionLabel("Текст", Modifier.pressable(onClick = onOpenSettings))
                SectionLabel("${(progress * 100).toInt()}%")
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(spacing.hair)
                .background(colors.rule),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(spacing.hair)
                    .background(colors.accent),
            )
        }
    }
}

/** Настройки, доступные без выхода со страницы книги. */
@Composable
private fun ReaderQuickSettings(
    theme: ReadingTheme,
    fontScale: Float,
    lineScale: Float,
    onThemeChange: (ReadingTheme) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLineScaleChange: (Float) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = spacing.pageMargin, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        SectionLabel("Тема страницы")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            ReadingTheme.entries.forEach { option ->
                Text(
                    text = option.title,
                    style = WolfyTheme.typography.caption,
                    color = if (theme == option) colors.onInverse else colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (theme == option) colors.inverse else colors.paper,
                            androidx.compose.foundation.shape.RoundedCornerShape(spacing.huge),
                        )
                        .pressable(onClick = { onThemeChange(option) })
                        .padding(vertical = spacing.small),
                )
            }
        }
        SettingStepper(
            title = "Размер текста",
            value = "${(fontScale * 100).toInt()}%",
            onLess = { onFontScaleChange(fontScale - 0.1f) },
            onMore = { onFontScaleChange(fontScale + 0.1f) },
        )
        SettingStepper(
            title = "Интервал строк",
            value = "${(lineScale * 100).toInt()}%",
            onLess = { onLineScaleChange(lineScale - 0.1f) },
            onMore = { onLineScaleChange(lineScale + 0.1f) },
        )
    }
}

@Composable
private fun SettingStepper(
    title: String,
    value: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = WolfyTheme.typography.body, color = WolfyTheme.colors.ink)
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Меньше", style = WolfyTheme.typography.caption, modifier = Modifier.pressable(onClick = onLess))
            Text(value, style = WolfyTheme.typography.body, color = WolfyTheme.colors.ink)
            Text("Больше", style = WolfyTheme.typography.caption, modifier = Modifier.pressable(onClick = onMore))
        }
    }
}

@Composable
private fun ChapterBody(
    state: ReaderState,
    onWordTap: (Int, com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrolled: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WolfyTheme.spacing
    // Состояние прокрутки заводится на каждую главу заново: иначе, перейдя к
    // следующей, читатель оказался бы в её середине.
    val scroll = rememberLazyListState()

    // Возвращение на место: один раз на открытие книги. Ждём, пока список
    // сообщит, сколько в нём блоков, — до первой раскладки их ноль, и
    // прокручивать некуда.
    LaunchedEffect(scroll, state.chapterIndex, state.startAt) {
        if (state.startAt <= 0f) return@LaunchedEffect
        snapshotFlow { scroll.layoutInfo.totalItemsCount }
            .first { it > 1 }
            .let { total ->
                scroll.scrollToItem((state.startAt * (total - 1)).toInt().coerceIn(0, total - 1))
            }
    }

    LaunchedEffect(scroll, state.chapterIndex) {
        snapshotFlow { scroll.firstVisibleItemIndex }
            .collect { first ->
                val total = scroll.layoutInfo.totalItemsCount
                // Доля считается по номеру первого видимого блока, а не по
                // пикселям: блоки разной высоты, и точность в пикселях всё
                // равно была бы обманчивой. Для «вернуться туда же» хватает
                // и такой.
                if (total > 1) onScrolled(first.toFloat() / (total - 1))
            }
    }

    // Ленивый список, а не прокручиваемая колонка: глава романа — это сотни
    // абзацев, и рисовать их все разом значит держать кадр несколько секунд.
    // Буквица открывает главу, а не каждый абзац: ищем первый текстовый блок
    // один раз на главу. Внутри itemsIndexed этот поиск шёл бы заново для
    // каждого блока — сотня линейных проходов по сотне блоков на каждый кадр
    // прокрутки.
    val dropCapAt = remember(state.blocks) { state.blocks.indexOfFirst { it.kind == "paragraph" } }

    LazyColumn(
        modifier.fillMaxSize(),
        state = scroll,
        contentPadding = PaddingValues(
            start = spacing.pageMargin,
            end = spacing.pageMargin,
            top = spacing.large,
            bottom = spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        itemsIndexed(state.blocks) { index, block ->
            BlockView(
                index = index,
                block = block,
                withDropCap = index == dropCapAt,
                savedLemmas = state.savedLemmas,
                // Подсвечивается только тот блок, в котором нашлось слово.
                // Иначе абзац с тем же смещением подсветит своё слово заодно —
                // смещения у блоков считаются каждое от своего начала.
                selected = state.card?.token?.takeIf { index == state.selectedBlock },
                onWordTap = onWordTap,
            )
        }

        item {
            ChapterNavigation(
                state = state,
                onPrevious = onPreviousChapter,
                onNext = onNextChapter,
            )
        }
    }
}


@Composable
private fun BlockView(
    index: Int,
    block: ReaderBlock,
    withDropCap: Boolean,
    savedLemmas: Set<String>,
    selected: com.wolfy.ffi.Token?,
    onWordTap: (Int, com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
) {
    val parsed = block.parsed

    when (block.kind) {
        "heading" -> ChapterHeading(block.text)

        "paragraph" -> if (parsed != null) {
            if (withDropCap) {
                DropCapParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    savedLemmaOf = { it.text.lowercase() },
                    selected = selected,
                    onWordTap = { onWordTap(index, it, parsed) },
                )
            } else {
                ReaderParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    savedLemmaOf = { it.text.lowercase() },
                    selected = selected,
                    onWordTap = { onWordTap(index, it, parsed) },
                )
            }
        }

        "quote" -> ReaderQuote(block.text)

        "listItem" -> if (parsed != null) {
            Row {
                Text("— ", style = WolfyTheme.typography.reader, color = WolfyTheme.colors.inkMuted)
                ReaderParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    selected = selected,
                    onWordTap = { onWordTap(index, it, parsed) },
                )
            }
        }

        "divider" -> Box(
            Modifier.fillMaxWidth().padding(vertical = WolfyTheme.spacing.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "* * *",
                style = WolfyTheme.typography.reader,
                color = WolfyTheme.colors.inkMuted,
            )
        }

        // Иллюстрации появятся вместе с загрузкой ресурсов из книги; пока
        // показываем подпись, чтобы место картинки не пропадало молча.
        "image" -> block.alt?.let { SectionLabel(it) }
    }
}

@Composable
private fun ChapterNavigation(
    state: ReaderState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val spacing = WolfyTheme.spacing

    Column(Modifier.fillMaxWidth().padding(top = spacing.xlarge)) {
        Rule()
        Row(
            Modifier.fillMaxWidth().padding(top = spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (state.hasPrevious) {
                SectionLabel("← предыдущая", Modifier.pressable(onClick = onPrevious))
            } else {
                SectionLabel(" ")
            }
            SectionLabel("${state.chapterIndex + 1} / ${state.chapterCount}")
            if (state.hasNext) {
                SectionLabel("следующая →", Modifier.pressable(onClick = onNext))
            } else {
                SectionLabel(" ")
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(WolfyTheme.spacing.pageMargin),
        )
    }
}
