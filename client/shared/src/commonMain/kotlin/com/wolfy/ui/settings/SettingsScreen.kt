package com.wolfy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.data.SyncStatus
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.ThemePicker
import com.wolfy.widgets.WolfySticker

/**
 * Настройки и всё, чему не нашлось своего раздела.
 *
 * Тема чтения стоит первой и с большим отрывом: её меняют каждый вечер, а
 * остальное — раз в жизни. Раздел, где часто используемое лежит внизу, читатель
 * пролистывает каждый раз заново.
 */
@Composable
fun SettingsScreen(
    theme: ReadingTheme,
    onThemeChange: (ReadingTheme) -> Unit,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    sync: SyncStatus,
    onSyncNow: () -> Unit,
    coreVersion: String,
    serverUrl: String,
    signedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(spacing.pageMargin)),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Text(
            text = "Ещё",
            style = WolfyTheme.typography.screenTitle,
            color = colors.ink,
        )
        Rule(thick = true)

        SectionLabel("Тема чтения")
        ThemePicker(selected = theme, onSelect = onThemeChange)
        Text(
            text = "Тема меняет и страницу, и весь интерфейс: читалка не должна " +
                "выглядеть гостем в собственном приложении.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )

        Rule()
        SectionLabel("Размер текста")
        FontScale(scale = fontScale, onChange = onFontScaleChange)
        Text(
            text = "Меняется только текст книги. Подписи интерфейса остаются как " +
                "были: растянутые, они полезли бы друг на друга.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )

        Rule()
        SectionLabel("Синхронизация")
        SyncBlock(status = sync, signedIn = signedIn, onSyncNow = onSyncNow)

        Rule()
        SectionLabel("Перевод")
        Fact(
            label = "Аккаунт Читавука",
            value = if (signedIn) "вход выполнен" else "не выполнен",
        )
        Text(
            text = if (signedIn) {
                "Контекстный перевод работает."
            } else {
                "Без входа читалка и разбор слов работают полностью — не " +
                    "приходит только перевод из сети."
            },
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )

        Rule()
        SectionLabel("О приложении")
        Fact(label = "Версия ядра", value = coreVersion)
        Fact(label = "Сервис", value = serverUrl)

        Row(
            Modifier.fillMaxWidth().padding(top = spacing.xlarge),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WolfySticker(Sticker.HappyWave, size = 88.dp)
            Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
                Text(
                    text = "Вульфи",
                    style = WolfyTheme.typography.bookTitle,
                    color = colors.ink,
                )
                Text(
                    text = "Хранитель библиотеки",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

/**
 * Состояние синхронизации.
 *
 * Отдельным блоком и с честной формулировкой: синхронизация — единственная
 * часть приложения, которая может не работать по причинам вне читателя, и
 * прятать это за молчаливым значком значит оставить его гадать, доехали ли
 * его книги.
 *
 * Сами книги при этом не ездят и ездить не будут: сервер хранит, что вы
 * читаете, а не сами файлы.
 */
@Composable
private fun SyncBlock(status: SyncStatus, signedIn: Boolean, onSyncNow: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Fact(
            label = "Состояние",
            value = when {
                !signedIn -> "нужен вход"
                status.running -> "идёт обмен…"
                status.error != null -> "не вышло"
                status.lastSuccess > 0 -> "всё сошлось"
                else -> "ещё не было"
            },
        )
        if (status.pending > 0) {
            Fact(label = "Ждёт отправки", value = status.pending.toString())
        }
        status.error?.let {
            Text(text = it, style = WolfyTheme.typography.caption, color = colors.accent)
        }
        Text(
            text = "Между устройствами едут прогресс, полки и колоды. Файлы книг " +
                "остаются на устройстве: книга — это ваш файл, и держать его у себя " +
                "сервер не должен.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )
        if (signedIn) {
            Text(
                text = if (status.running) "обмен идёт" else "синхронизировать сейчас",
                style = WolfyTheme.typography.button,
                color = if (status.running) colors.inkMuted else colors.accent,
                modifier = Modifier
                    .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                    .clickable(enabled = !status.running, onClick = onSyncNow)
                    .padding(horizontal = spacing.large, vertical = spacing.small),
            )
        }
    }
}

/**
 * Кегль читалки — тремя кнопками, а не ползунком.
 *
 * Ползунок даёт бесконечно много промежуточных значений, из которых читателю
 * нужно одно из пяти. Шаг в десять процентов заметен глазом и попадается с
 * первого раза, а «чуть-чуть больше» ползунком приходится ловить.
 */
@Composable
private fun FontScale(scale: Float, onChange: (Float) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScaleStep(label = "Aa −", enabled = scale > 0.8f) { onChange(scale - 0.1f) }
        Text(
            text = "${(scale * 100).toInt()}%",
            style = WolfyTheme.typography.body,
            color = colors.ink,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        ScaleStep(label = "Aa +", enabled = scale < 1.6f) { onChange(scale + 0.1f) }
    }
}

@Composable
private fun ScaleStep(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Text(
        text = label,
        style = WolfyTheme.typography.button,
        color = if (enabled) colors.ink else colors.rule,
        modifier = Modifier
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = spacing.large, vertical = spacing.small),
    )
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.inkMuted,
        )
        Text(
            text = value,
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.ink,
        )
    }
}
