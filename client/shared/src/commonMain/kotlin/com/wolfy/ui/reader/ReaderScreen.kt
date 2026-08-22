package com.wolfy.ui.reader

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.wolfy.ui.card.WordCardSheet
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
    onWordTap: (com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
    onDismissCard: () -> Unit,
    onSaveWord: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Box(modifier.fillMaxSize().background(colors.paper)) {
        Column(Modifier.fillMaxSize()) {
            ReaderTopBar(state)

            when {
                state.error != null -> Message(state.error)
                state.loading -> Message("Книга открывается…")
                else -> ChapterBody(
                    state = state,
                    onWordTap = onWordTap,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        WordCardSheet(
            state = state.card,
            onDismiss = onDismissCard,
            onSave = onSaveWord,
        )
    }
}

/** Шапка: глава и полоса прогресса чтения. */
@Composable
private fun ReaderTopBar(state: ReaderState) {
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
            SectionLabel(state.chapterTitle.ifBlank { state.bookTitle })
            SectionLabel("${(progress * 100).toInt()}%")
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

@Composable
private fun ChapterBody(
    state: ReaderState,
    onWordTap: (com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WolfyTheme.spacing

    // Ленивый список, а не прокручиваемая колонка: глава романа — это сотни
    // абзацев, и рисовать их все разом значит держать кадр несколько секунд.
    LazyColumn(
        modifier.fillMaxSize(),
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
                block = block,
                // Буквица ставится только у первого текстового абзаца главы:
                // в печати она открывает главу, а не каждый абзац подряд.
                withDropCap = index == state.blocks.indexOfFirst { it.kind == "paragraph" },
                savedLemmas = state.savedLemmas,
                selected = state.card?.token,
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
    block: ReaderBlock,
    withDropCap: Boolean,
    savedLemmas: Set<String>,
    selected: com.wolfy.ffi.Token?,
    onWordTap: (com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
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
                    onWordTap = { onWordTap(it, parsed) },
                )
            } else {
                ReaderParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    savedLemmaOf = { it.text.lowercase() },
                    selected = selected,
                    onWordTap = { onWordTap(it, parsed) },
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
                    onWordTap = { onWordTap(it, parsed) },
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
                SectionLabel("← предыдущая", Modifier.clickable(onClick = onPrevious))
            } else {
                SectionLabel(" ")
            }
            SectionLabel("${state.chapterIndex + 1} / ${state.chapterCount}")
            if (state.hasNext) {
                SectionLabel("следующая →", Modifier.clickable(onClick = onNext))
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
