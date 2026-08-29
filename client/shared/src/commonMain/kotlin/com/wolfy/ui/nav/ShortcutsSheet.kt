package com.wolfy.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.widgets.SectionLabel

/**
 * Список сочетаний клавиш.
 *
 * Сочетание, о котором никто не знает, не существует. Открывается по «?» —
 * тем же знаком, что и в почте, в редакторах и в браузере, потому что
 * читатель, у которого есть эта привычка, попробует именно его.
 *
 * Показывается только там, где есть клавиатура: на телефоне это был бы список
 * недоступных возможностей.
 */
@Composable
fun ShortcutsSheet(visible: Boolean, onDismiss: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val motion = WolfyTheme.motion

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.paced(motion.quick)),
        exit = fadeOut(motion.paced(motion.instant)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.ink.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 460.dp)
                    .background(colors.surface, RoundedCornerShape(spacing.large))
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.large),
                verticalArrangement = Arrangement.spacedBy(spacing.large),
            ) {
                Text(
                    text = "Клавиши",
                    style = WolfyTheme.typography.screenTitle,
                    color = colors.ink,
                )
                Group("В книге", readerShortcuts)
                Group("В карточке слова", cardShortcuts)
                Group("В тренировке", trainingShortcuts)
                Group("Везде", globalShortcutList)
                Text(
                    text = "«?» ещё раз или щелчок мимо закроет подсказку",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, shortcuts: List<Shortcut>) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        SectionLabel(title)
        shortcuts.forEach { shortcut ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Text(
                    text = shortcut.keys,
                    style = WolfyTheme.typography.caption,
                    color = colors.accent,
                    modifier = Modifier.widthIn(min = 150.dp),
                )
                Text(
                    text = shortcut.title,
                    style = WolfyTheme.typography.body,
                    color = colors.ink,
                )
            }
        }
    }
}
