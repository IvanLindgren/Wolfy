package com.wolfy.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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
