package com.wolfy.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.settling
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.pressable

/**
 * Оглавление книги.
 *
 * Появляется снизу, как и карточка слова: два выдвижных листа с разным
 * содержимым, но с одной механикой — читатель учится ей один раз.
 *
 * Список открывается на текущей главе, а не с начала. В книге на сорок глав
 * тридцатая находится прокруткой, и заставлять читателя искать место, где он
 * стоит, в списке, который ему же и показали, — работа на пустом месте.
 */
@Composable
fun ContentsSheet(
    visible: Boolean,
    chapters: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxSheetHeight = maxHeight * 0.7f

        // Темп листа берётся из темы, а не из пружины по умолчанию: пружина
        // не знает про «уменьшить движение» и выезжает всегда, а обещали
        // читателю обратное.
        val motion = WolfyTheme.motion
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.paced(motion.quick)),
            exit = fadeOut(motion.paced(motion.quick)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.ink.copy(alpha = 0.25f))
                    .clickable(onClick = onDismiss),
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = motion.settling(),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = motion.paced(motion.quick),
                targetOffsetY = { it },
            ),
        ) {
            val list = rememberLazyListState()
            LaunchedEffect(visible, current) {
                if (visible && current > 0) {
                    list.scrollToItem(current)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .background(
                        colors.surface,
                        RoundedCornerShape(topStart = spacing.large, topEnd = spacing.large),
                    )
                    .padding(spacing.large),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(spacing.tight)
                        .background(colors.rule, CircleShape)
                        .pressable(onClick = onDismiss),
                )
                SectionLabel("Оглавление")
                Rule()

                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(vertical = spacing.small),
                ) {
                    itemsIndexed(chapters) { index, title ->
                        ChapterRow(
                            number = index + 1,
                            title = title,
                            current = index == current,
                            onClick = { onSelect(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(number: Int, title: String, current: Boolean, onClick: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Номер набран цифрой в углу — как в книжном оглавлении, где номер
        // главы держит левый край, а не тонет в её названии.
        Text(
            text = number.toString(),
            style = WolfyTheme.typography.caption,
            color = if (current) colors.accent else colors.inkMuted,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = title,
            style = WolfyTheme.typography.body,
            color = if (current) colors.accent else colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Text(
                text = "здесь",
                style = WolfyTheme.typography.caption,
                color = colors.accent,
            )
        }
    }
}
