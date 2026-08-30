package com.wolfy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.wolfy.data.FocusMode
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.pressable

/**
 * Настройки чтения — один компонент на всё приложение.
 *
 * ## Почему один
 *
 * Их было два: панель в читалке и карточки на экране «Ещё». Наборы не
 * совпадали (интервала строк на экране настроек не было вовсе), подписи
 * расходились («Фокус» против «Окно чтения», «выкл.» против «нет»), порядок
 * был разный. Читатель, настроивший чтение в одном месте, не находил тех же
 * настроек в другом и справедливо считал, что их две системы.
 *
 * Свести их согласованием текстов нельзя: согласованные тексты в двух файлах
 * расходятся на первой же правке. Поэтому определение одно, а точек монтажа
 * две.
 *
 * ## Почему с образцом
 *
 * «Ведущая строка», «отрезок», «окно чтения» — это термины, придуманные здесь,
 * и угадать их значение неоткуда. Объяснение словами помогает наполовину:
 * читатель узнаёт, что обещано, но не видит, случилось ли это. Образец сверху
 * набран настоящим шрифтом читалки и живёт по тем же настройкам, поэтому кегль,
 * интервал, основа слова и приглушение проверяются, не выходя к книге.
 *
 * Темп и отрезок образцом не показать: первый — движение во времени, второй —
 * величина в сотни слов. Для них подпись говорит, что именно произойдёт в
 * книге и где это будет видно.
 */
@Composable
fun ReadingSettingsPanel(
    theme: ReadingTheme,
    onThemeChange: (ReadingTheme) -> Unit,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    lineScale: Float,
    onLineScaleChange: (Float) -> Unit,
    emphasizeStems: Boolean,
    onEmphasizeStems: (Boolean) -> Unit,
    focusMode: FocusMode,
    onFocusModeChange: (FocusMode) -> Unit,
    pacerWpm: Int,
    onPacerChange: (Int) -> Unit,
    segmentWords: Int,
    onSegmentWordsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WolfyTheme.spacing
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        ReadingSample(emphasizeStems = emphasizeStems, focusMode = focusMode)

        SettingBlock(
            title = "Тема страницы",
            hint = "Цвет бумаги и чернил. OLED гасит пиксели полностью и экономит батарею на тёмном экране.",
        ) {
            ThemeRow(theme = theme, onThemeChange = onThemeChange)
        }

        SettingStepper(
            title = "Размер текста",
            hint = "Кегль шрифта книги. На интерфейс не влияет.",
            value = "${(fontScale * 100).toInt()}%",
            canLess = fontScale > FONT_SCALE.first,
            canMore = fontScale < FONT_SCALE.second,
            onLess = { onFontScaleChange(fontScale - STEP) },
            onMore = { onFontScaleChange(fontScale + STEP) },
        )

        SettingStepper(
            title = "Интервал строк",
            hint = "Воздух между строками. Помогает, когда взгляд соскакивает на соседнюю строку.",
            value = "${(lineScale * 100).toInt()}%",
            canLess = lineScale > LINE_SCALE.first,
            canMore = lineScale < LINE_SCALE.second,
            onLess = { onLineScaleChange(lineScale - STEP) },
            onMore = { onLineScaleChange(lineScale + STEP) },
        )

        SectionLabel("Помощь вниманию")
        Text(
            "Четыре приёма для чтения на неродном языке. Всё выключено по умолчанию: " +
                "навязанная помощь мешает тем, кому она не нужна.",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )

        SettingSwitch(
            title = "Выделять основу слова",
            hint = "Начало каждого слова набирается жирнее. Взгляд цепляется за него, а окончание " +
                "достраивает сам, и глаз перестаёт возвращаться к прочитанному.",
            on = emphasizeStems,
            onChange = onEmphasizeStems,
        )

        SettingChoice(
            title = "Окно чтения",
            hint = "Приглушает всё, кроме места, где вы сейчас. Видно в образце выше.",
            choices = listOf(
                FocusMode.Off to "нет",
                FocusMode.Sentence to "фраза",
                FocusMode.Paragraph to "абзац",
            ),
            selected = focusMode,
            onChange = onFocusModeChange,
        )

        SettingChoice(
            title = "Ведущая строка",
            hint = "Ведёт по книге сама, по одной фразе за раз, и подсвечивает ту, что читается " +
                "сейчас. Включается в книге кнопкой «вести» над страницей: настройка задаёт " +
                "только темп, а не запускает ход.",
            choices = PACES,
            selected = pacerWpm,
            onChange = onPacerChange,
        )

        SettingChoice(
            title = "Отрезок",
            hint = "Делит главу на подходы и говорит, где закончить: над страницей появляется " +
                "полоса «сколько слов до конца подхода». Слова, а не минуты — скорость чтения " +
                "у каждого своя, и обещать минуты значило бы обмануть половину читателей.",
            choices = SEGMENTS,
            selected = segmentWords,
            onChange = onSegmentWordsChange,
        )
    }
}

/** Шаг обоих множителей набора. */
private const val STEP = 0.1f

/*
 * Пределы множителей — копия того, что зажимает ядро (`core/src/settings.rs`,
 * FONT_SCALE и LINE_SCALE). Ядро остаётся источником истины и всё равно
 * зажмёт значение; здесь они нужны только для того, чтобы кнопка на пределе
 * гасла. Кнопка, которая нажимается и ничего не меняет, читается как
 * сломанная — а именно этого читатель и не должен думать про настройки.
 */
private val FONT_SCALE = 0.8f to 1.6f
private val LINE_SCALE = 0.9f to 1.5f

/*
 * Подписи к темпу и отрезку.
 *
 * Числа темпа не круглые и не произвольные: 160 — спокойное чтение вслух,
 * 220 — обычное про себя на неродном языке, 300 — быстро, но ещё с
 * пониманием. Само число тоже показано: «обычно» ничего не говорит тому, кто
 * знает свою скорость.
 */
private val PACES = listOf(
    0 to "нет",
    160 to "160 сл/мин",
    220 to "220 сл/мин",
    300 to "300 сл/мин",
)

private val SEGMENTS = listOf(
    0 to "нет",
    150 to "150 слов",
    400 to "400 слов",
    900 to "900 слов",
)

/**
 * Живой образец страницы.
 *
 * Настоящий шрифт книги и настоящая палитра темы: и то и другое приезжает из
 * `WolfyTheme`, который уже знает выбранные кегль, интервал и тему. Поэтому
 * образец не изображает настройки, а показывает их.
 *
 * Основа слова в образце размечена приблизительно — по длине слова, а не
 * разбором. В книге границу считает ядро, и повторять его правило здесь ради
 * одной фразы было бы копией, которая однажды разойдётся с оригиналом.
 */
@Composable
private fun ReadingSample(emphasizeStems: Boolean, focusMode: FocusMode) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val dim = colors.ink.copy(alpha = 0.3f)

    val sample = remember(emphasizeStems, focusMode, colors) {
        buildAnnotatedString {
            appendSample(SAMPLE_FIRST, emphasizeStems)
            val firstEnd = length
            append(' ')
            appendSample(SAMPLE_SECOND, emphasizeStems)
            when (focusMode) {
                // «Фраза» оставляет светлым одно предложение, «абзац» — весь
                // абзац: в образце из одного абзаца это и значит «ничего не
                // притушено», и притворяться иначе нельзя.
                FocusMode.Sentence -> addStyle(SpanStyle(color = dim), firstEnd, length)
                FocusMode.Off, FocusMode.Paragraph -> Unit
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(spacing.medium))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.medium))
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.tight),
    ) {
        SectionLabel("Так будет выглядеть страница")
        Text(sample, style = WolfyTheme.typography.reader, color = colors.ink)
    }
}

private const val SAMPLE_FIRST = "The fox found a quiet place to read."
private const val SAMPLE_SECOND = "Nobody had opened this book for years."

/** Слова с приблизительной основой: жирнее набирается начало длинных слов. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendSample(
    sentence: String,
    emphasizeStems: Boolean,
) {
    var index = 0
    while (index < sentence.length) {
        val next = sentence.indexOf(' ', index).takeIf { it >= 0 } ?: sentence.length
        val word = sentence.substring(index, next)
        val start = length
        append(word)
        if (emphasizeStems) {
            val letters = word.count { it.isLetter() }
            val stem = when {
                letters >= 6 -> 3
                letters >= 4 -> 2
                letters >= 3 -> 1
                else -> 0
            }
            if (stem > 0) addStyle(SpanStyle(fontWeight = FontWeight.W600), start, start + stem)
        }
        if (next < sentence.length) append(' ')
        index = next + 1
    }
}

/** Заголовок, пояснение и содержимое настройки одной пластики. */
@Composable
private fun SettingBlock(title: String, hint: String, content: @Composable () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
        Text(title, style = WolfyTheme.typography.body, color = colors.ink)
        Text(hint, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        content()
    }
}

@Composable
private fun ThemeRow(theme: ReadingTheme, onThemeChange: (ReadingTheme) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(top = spacing.tight),
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
                    .pressable(onClick = { onThemeChange(option) })
                    .background(
                        if (theme == option) colors.inverse else colors.paper,
                        RoundedCornerShape(spacing.huge),
                    )
                    .padding(vertical = spacing.small),
            )
        }
    }
}

/**
 * Множитель со ступенями.
 *
 * Значение показано числом рядом с кнопками, а не только образцом: читатель,
 * увёзший кегль в край, должен видеть, что дальше некуда, и уметь вернуться к
 * ста процентам, не подбирая их на глаз.
 */
@Composable
private fun SettingStepper(
    title: String,
    hint: String,
    value: String,
    canLess: Boolean,
    canMore: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = WolfyTheme.typography.body, color = colors.ink)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperAction("Меньше", canLess, onLess)
                Text(value, style = WolfyTheme.typography.button, color = colors.ink)
                StepperAction("Больше", canMore, onMore)
            }
        }
        Text(hint, style = WolfyTheme.typography.caption, color = colors.inkMuted)
    }
}

/**
 * Подпись-действие шага.
 *
 * Своя цель нажатия, а не одна строка кегля caption: в строку высотой в кегль
 * палец не попадает. Цвет задаётся явно — без него material3 берёт
 * `LocalContentColor`, а тот по умолчанию чёрный, и обе подписи пропадали на
 * тёмной и на OLED-бумаге.
 */
@Composable
private fun StepperAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = WolfyTheme.typography.button,
        color = if (enabled) WolfyTheme.colors.accent else WolfyTheme.colors.rule,
        modifier = Modifier
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = WolfyTheme.spacing.small, vertical = WolfyTheme.spacing.medium),
    )
}

@Composable
private fun SettingSwitch(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().pressable { onChange(!on) },
        verticalArrangement = Arrangement.spacedBy(spacing.tight),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = WolfyTheme.typography.body, color = colors.ink)
            Text(
                if (on) "включено" else "выключено",
                style = WolfyTheme.typography.button,
                color = if (on) colors.accent else colors.inkMuted,
            )
        }
        Text(hint, style = WolfyTheme.typography.caption, color = colors.inkMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingChoice(
    title: String,
    hint: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onChange: (T) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
        Text(title, style = WolfyTheme.typography.body, color = colors.ink)
        Text(hint, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        // Четыре подписи в обычной Row на узком экране с крупным кеглем не
        // умещаются: переносятся на вторую строку.
        FlowRow(
            modifier = Modifier.padding(top = spacing.tight),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.tight),
        ) {
            choices.forEach { (value, label) ->
                Text(
                    text = label,
                    style = WolfyTheme.typography.button,
                    color = if (value == selected) colors.accent else colors.inkMuted,
                    modifier = Modifier.pressable { onChange(value) },
                )
            }
        }
    }
}
