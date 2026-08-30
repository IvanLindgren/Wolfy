package com.wolfy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.data.SyncStatus
import com.wolfy.data.dictionary.DictionaryStatus
import com.wolfy.data.companion.CompanionMemory
import com.wolfy.data.companion.CompanionMemoryStats
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.PrimaryButton
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.data.FocusMode
import com.wolfy.platform.RadioState
import com.wolfy.platform.RadioStation
import com.wolfy.ui.radio.RadioPanel
import com.wolfy.widgets.pressable

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
    /**
     * Интервал строк.
     *
     * Раньше его здесь не было вовсе: настройка жила только в панели читалки.
     * Читатель, открывший «Настройки» и не нашедший её, делал единственный
     * разумный вывод — что настроек две системы и они не совпадают.
     */
    lineScale: Float,
    onLineScaleChange: (Float) -> Unit,
    sync: SyncStatus,
    onSyncNow: () -> Unit,
    appVersion: String,
    signedIn: Boolean,
    accountEmail: String,
    reduceMotion: Boolean,
    onReduceMotion: (Boolean) -> Unit,
    companionSounds: Boolean,
    onCompanionSounds: (Boolean) -> Unit,
    companionMemory: CompanionMemory,
    companionMemoryStats: CompanionMemoryStats,
    onCompanionMemoryEnabled: (Boolean) -> Unit,
    onCompanionMemoryShared: (Boolean) -> Unit,
    onCompanionMemorySize: (String) -> Unit,
    onClearCompanionMemory: () -> Unit,
    /*
     * Помощь вниманию: якорь слова, окно чтения, ведущая строка, отрезок.
     * Всё выключено по умолчанию — навязанная помощь мешает тем, кому она не
     * нужна, — и всё синхронизируется: включив окно на телефоне, читатель
     * ждёт его и в браузере.
     */
    emphasizeStems: Boolean,
    onEmphasizeStems: (Boolean) -> Unit,
    focusMode: FocusMode,
    onFocusMode: (FocusMode) -> Unit,
    pacerWpm: Int,
    onPacer: (Int) -> Unit,
    segmentWords: Int,
    onSegmentWords: (Int) -> Unit,
    /*
     * Радио. Живёт в «Ещё» рядом с остальным, что настраивают редко, но
     * управляется отсюда же: отдельный экран ради выключателя — это ещё один
     * экран, который надо найти.
     */
    radio: RadioState,
    radioOwnUrl: String,
    onRadioStation: (RadioStation) -> Unit,
    onRadioStop: () -> Unit,
    onRadioVolume: (Float) -> Unit,
    onRadioOwnUrl: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenReference: () -> Unit,
    onOpenCompanion: () -> Unit,
    dictionary: DictionaryStatus,
    onDownloadDictionary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    var confirmMemoryClear by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(spacing.pageMargin)),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Text(
            text = "Настройки",
            style = WolfyTheme.typography.screenTitle,
            color = colors.ink,
        )

        SettingsCard("Чтение") {
            // То же определение, что и в панели читалки: см.
            // ReadingSettingsPanel. Раздельные копии расходились составом и
            // подписями, и обе выглядели неполными.
            ReadingSettingsPanel(
                theme = theme,
                onThemeChange = onThemeChange,
                fontScale = fontScale,
                onFontScaleChange = onFontScaleChange,
                lineScale = lineScale,
                onLineScaleChange = onLineScaleChange,
                emphasizeStems = emphasizeStems,
                onEmphasizeStems = onEmphasizeStems,
                focusMode = focusMode,
                onFocusModeChange = onFocusMode,
                pacerWpm = pacerWpm,
                onPacerChange = onPacer,
                segmentWords = segmentWords,
                onSegmentWordsChange = onSegmentWords,
            )
            Rule()
            MotionToggle(reduceMotion, onReduceMotion)
        }

        // Компаньон живёт рядом с настройками чтения, но не внутри длинного
        // текстового блока: это отдельный раздел со своим экраном.
        SettingsCard("Компаньон") {
            Text(
                "Персонаж, которого вы создаёте и наряжаете. Необязательный.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
            SwitchRow(
                title = "Звуки компаньона",
                hint = "Тихие сигналы при появлении и готовом ответе.",
                on = companionSounds,
                onChange = onCompanionSounds,
            )
            Rule()
            SwitchRow(
                title = "Память компаньона",
                hint = "Хранит на этом устройстве ответы, вопросы и краткие пересказы.",
                on = companionMemory.settings.enabled,
                onChange = onCompanionMemoryEnabled,
            )
            if (companionMemory.settings.enabled) {
                SwitchRow(
                    title = "Учитывать память в новых ответах",
                    hint = "ИИ получает только короткую выжимку, без полного текста книги.",
                    on = companionMemory.settings.shareWithAi,
                    onChange = onCompanionMemoryShared,
                )
                val sizes = listOf("compact", "balanced", "deep")
                ChoiceRow(
                    title = "Объём памяти",
                    hint = "Сейчас: ${companionMemoryStats.answers} ответов, ${companionMemoryStats.books} книг.",
                    options = listOf("короткая", "обычная", "большая"),
                    selected = sizes.indexOf(companionMemory.settings.size).coerceAtLeast(1),
                    onSelect = { onCompanionMemorySize(sizes[it]) },
                )
                if (confirmMemoryClear) {
                    Text(
                        "Стереть все сохранённые ответы и пересказы? Книги и компаньон останутся.",
                        style = WolfyTheme.typography.caption,
                        color = colors.accent,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                        Text(
                            "стереть",
                            style = WolfyTheme.typography.button,
                            color = colors.accent,
                            modifier = Modifier.pressable {
                                onClearCompanionMemory()
                                confirmMemoryClear = false
                            },
                        )
                        Text(
                            "отмена",
                            style = WolfyTheme.typography.button,
                            color = colors.inkMuted,
                            modifier = Modifier.pressable { confirmMemoryClear = false },
                        )
                    }
                } else if (companionMemoryStats.answers + companionMemoryStats.books + companionMemoryStats.questions > 0) {
                    Text(
                        "Очистить память",
                        style = WolfyTheme.typography.button,
                        color = colors.accent,
                        modifier = Modifier.pressable { confirmMemoryClear = true },
                    )
                }
            }
            PrimaryButton("Открыть раздел", onOpenCompanion)
        }


        SettingsCard("Радио") {
            RadioPanel(
                state = radio,
                ownUrl = radioOwnUrl,
                onStation = onRadioStation,
                onStop = onRadioStop,
                onVolume = onRadioVolume,
                onOwnUrl = onRadioOwnUrl,
            )
        }

        SettingsCard("Аккаунт и синхронизация") {
            Fact(
                label = "Аккаунт",
                value = if (signedIn) accountEmail.ifBlank { "Читавук" } else "вход не выполнен",
            )
            Text(
                text = if (signedIn) "выйти" else "войти",
                style = WolfyTheme.typography.button,
                color = colors.accent,
                modifier = Modifier
                    .pressable(onClick = if (signedIn) onSignOut else onSignIn)
                    .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                    .padding(horizontal = spacing.large, vertical = spacing.small),
            )
            Rule()
            SyncBlock(status = sync, signedIn = signedIn, onSyncNow = onSyncNow)
            Text(
                if (signedIn) "Перевод из сети включён." else "Войдите, чтобы включить перевод из сети.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }

        SettingsCard("Офлайн-словарь") {
            Fact(
                label = "Состояние",
                value = when (dictionary) {
                    DictionaryStatus.Ready -> "установлен"
                    DictionaryStatus.Offer, DictionaryStatus.Declined -> "не установлен"
                    is DictionaryStatus.Downloading -> dictionary.progress?.let {
                        "установка ${(it * 100).toInt()}%"
                    } ?: "установка"
                    is DictionaryStatus.Failed -> "ошибка установки"
                },
            )
            Text(
                "Перевод и толкования без интернета. Занимает около 9 МБ.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
            if (dictionary !is DictionaryStatus.Ready) {
                val downloading = dictionary is DictionaryStatus.Downloading
                Text(
                    text = when {
                        downloading -> "словарь устанавливается"
                        dictionary is DictionaryStatus.Failed -> "повторить установку"
                        else -> "установить словарь"
                    },
                    style = WolfyTheme.typography.button,
                    color = if (downloading) colors.inkMuted else colors.accent,
                    modifier = Modifier
                        .pressable(enabled = !downloading, onClick = onDownloadDictionary)
                        .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                        .padding(horizontal = spacing.large, vertical = spacing.small),
                )
            }
        }

        SettingsCard("Справочник") {
            Text(
                "Правила английского с короткими примерами.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
            Text(
                text = "открыть справочник →",
                style = WolfyTheme.typography.button,
                color = colors.accent,
                modifier = Modifier.pressable(onClick = onOpenReference).padding(vertical = spacing.small),
            )
        }

        SettingsCard("О приложении") {
            Fact(label = "Версия приложения", value = appVersion)
        }

    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.large))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.large))
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(title, style = WolfyTheme.typography.bookTitle, color = colors.ink)
        content()
    }
}

/** Переключатель «включено/выключено» той же пластики, что и убавленное движение. */
@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .pressable { onChange(!on) }
            .padding(vertical = spacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
            Text(title, style = WolfyTheme.typography.body, color = colors.ink)
            Text(hint, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        }
        Text(
            if (on) "включено" else "выключено",
            style = WolfyTheme.typography.button,
            color = if (on) colors.accent else colors.inkMuted,
        )
    }
}

/** Выбор одного из нескольких: подписями в строку, без выпадающих списков. */
@Composable
private fun ChoiceRow(
    title: String,
    hint: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.tight),
    ) {
        Text(title, style = WolfyTheme.typography.body, color = colors.ink)
        Text(hint, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
            options.forEachIndexed { index, option ->
                Text(
                    text = option,
                    style = WolfyTheme.typography.button,
                    color = if (index == selected) colors.accent else colors.inkMuted,
                    modifier = Modifier.pressable { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun MotionToggle(reduced: Boolean, onChange: (Boolean) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .pressable { onChange(!reduced) }
            .padding(vertical = spacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
            Text("Убавить движение", style = WolfyTheme.typography.body, color = colors.ink)
            Text(
                "Без плавных переходов.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }
        Text(
            if (reduced) "включено" else "выключено",
            style = WolfyTheme.typography.button,
            color = if (reduced) colors.accent else colors.inkMuted,
        )
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
 * Книги, которые уже добавлены на другом устройстве, тоже будут скачаны.
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
            text = "Книги, прогресс и карточки будут на всех ваших устройствах.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )
        if (signedIn) {
            Text(
                text = if (status.running) "обмен идёт" else "синхронизировать сейчас",
                style = WolfyTheme.typography.button,
                color = if (status.running) colors.inkMuted else colors.accent,
                modifier = Modifier
                    .pressable(enabled = !status.running, onClick = onSyncNow)
                    .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                    .padding(horizontal = spacing.large, vertical = spacing.small),
            )
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
