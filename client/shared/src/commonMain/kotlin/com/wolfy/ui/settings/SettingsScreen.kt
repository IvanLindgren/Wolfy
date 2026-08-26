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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.data.SyncStatus
import com.wolfy.data.dictionary.DictionaryStatus
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.ThemePicker
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
    sync: SyncStatus,
    onSyncNow: () -> Unit,
    coreVersion: String,
    serverUrl: String,
    signedIn: Boolean,
    accountEmail: String,
    reduceMotion: Boolean,
    onReduceMotion: (Boolean) -> Unit,
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
    dictionary: DictionaryStatus,
    onDownloadDictionary: () -> Unit,
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
            text = "Настройки",
            style = WolfyTheme.typography.screenTitle,
            color = colors.ink,
        )
        Text(
            "Всё важное собрано по темам. Нужный пункт видно сразу.",
            style = WolfyTheme.typography.body,
            color = colors.inkMuted,
        )

        SettingsCard("Вид и текст") {
            SectionLabel("Тема")
            ThemePicker(selected = theme, onSelect = onThemeChange)
            Rule()
            SectionLabel("Размер текста книги")
            FontScale(scale = fontScale, onChange = onFontScaleChange)
            MotionToggle(reduceMotion, onReduceMotion)
        }

        SettingsCard("Помощь при чтении") {
            SwitchRow(
                title = "Полужирная основа",
                hint = "Начало слова заметнее, поэтому строку легче удерживать взглядом.",
                on = emphasizeStems,
                onChange = onEmphasizeStems,
            )
            ChoiceRow(
                title = "Окно чтения",
                hint = "Текст вокруг текущего места становится тише.",
                options = FOCUS_TITLES.map { it.second },
                selected = FOCUS_TITLES.indexOfFirst { it.first == focusMode }.coerceAtLeast(0),
                onSelect = { onFocusMode(FOCUS_TITLES[it].first) },
            )
            ChoiceRow(
                title = "Ведущая строка",
                hint = "Строка движется сама с выбранной скоростью.",
                options = PACES.map { it.second },
                selected = PACES.indexOfFirst { it.first == pacerWpm }.coerceAtLeast(0),
                onSelect = { onPacer(PACES[it].first) },
            )
            ChoiceRow(
                title = "Отрезок чтения",
                hint = "Показывает удобную границу одного подхода.",
                options = SEGMENTS.map { it.second },
                selected = SEGMENTS.indexOfFirst { it.first == segmentWords }.coerceAtLeast(0),
                onSelect = { onSegmentWords(SEGMENTS[it].first) },
            )
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
                    .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                    .pressable(onClick = if (signedIn) onSignOut else onSignIn)
                    .padding(horizontal = spacing.large, vertical = spacing.small),
            )
            Rule()
            SyncBlock(status = sync, signedIn = signedIn, onSyncNow = onSyncNow)
            Text(
                if (signedIn) "Перевод из сети доступен." else "Войдите, чтобы пользоваться переводом из сети.",
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
                "Переводы, произношение и английские толкования работают без сети. Словарь занимает около 9 МБ.",
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
                        .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                        .pressable(enabled = !downloading, onClick = onDownloadDictionary)
                        .padding(horizontal = spacing.large, vertical = spacing.small),
                )
            }
        }

        SettingsCard("Справочник") {
            Text(
                "Времена, залог, модальные и условные формы с короткими примерами.",
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
            Fact(label = "Версия ядра", value = coreVersion)
            Fact(label = "Сервис", value = serverUrl)
        }

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

/*
 * Подписи к режимам чтения.
 *
 * Числа темпа не круглые и не произвольные: 160 — спокойное чтение вслух,
 * 220 — обычное про себя на неродном языке, 300 — быстро, но ещё с
 * пониманием. Отрезок задан в словах, а не в минутах: минуты пришлось бы
 * переводить в слова по скорости, которой мы не знаем, и обещание «пять
 * минут» оказалось бы неверным ровно для того, кто читает медленнее.
 */
private val FOCUS_TITLES = listOf(
    FocusMode.Off to "без окна",
    FocusMode.Sentence to "предложение",
    FocusMode.Paragraph to "абзац",
)

private val PACES = listOf(0 to "выключена", 160 to "спокойно", 220 to "обычно", 300 to "быстро")

private val SEGMENTS =
    listOf(0 to "без отрезков", 150 to "короткий", 400 to "средний", 900 to "длинный")

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
                "Переходы и полёт слова станут мгновенными.",
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
            text = "Прогресс, полки и карточки появятся на ваших устройствах. " +
                "Сами файлы книг останутся только у вас.",
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
                    .pressable(enabled = !status.running, onClick = onSyncNow)
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
            .pressable(enabled = enabled, onClick = onClick)
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
