package com.wolfy.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.ffi.WordAnalysis
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme

/**
 * Разделительная линейка газетной вёрстки.
 *
 * @param thick толстая линейка отбивает крупные разделы — под названием
 *   издания и над колонтитулом; тонкая делит соседние блоки.
 */
@Composable
fun Rule(thick: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(if (thick) 3.dp else WolfyTheme.spacing.rule)
            .background(if (thick) WolfyTheme.colors.ink else WolfyTheme.colors.rule),
    )
}

/** Мелкий капслок над разделом: «РАЗБОР СЛОВА», «КОЛЛОКАЦИИ». */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = WolfyTheme.typography.sectionLabel,
        color = WolfyTheme.colors.inkMuted,
        modifier = modifier,
    )
}

/** Бейдж уровня CEFR — тот самый кружок «B2» в углу карточки. */
@Composable
fun CefrBadge(level: String, modifier: Modifier = Modifier) {
    val colors = WolfyTheme.colors
    // Цвет по группе уровней: начальные зелёные, средние золотые, высокие
    // красные. Читателю важен не сам код, а «трудное ли это слово».
    val background = when (level.firstOrNull()) {
        'A' -> colors.partsOfSpeech.adjective
        'B' -> colors.gold
        else -> colors.accent
    }
    Box(
        modifier
            .background(background, CircleShape)
            .padding(horizontal = WolfyTheme.spacing.medium, vertical = WolfyTheme.spacing.tight),
    ) {
        Text(
            text = level,
            style = WolfyTheme.typography.sectionLabel,
            color = colors.onAccent,
        )
    }
}

/** Подпись мелким шрифтом. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = WolfyTheme.typography.caption,
        color = WolfyTheme.colors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Название приложения в газетной шапке. */
@Composable
fun Masthead(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Wolfy",
            style = WolfyTheme.typography.screenTitle,
            color = WolfyTheme.colors.ink,
        )
        Caption("Чтение книг · английский язык")
    }
}

/**
 * Предварительный вид карточки слова.
 *
 * Здесь ровно то, что ядро посчитало на устройстве: начальная форма, части
 * речи, объяснение окончания, частотность и уровень. Перевод сюда приедет из
 * сети отдельно — карточка не ждёт его, чтобы открыться.
 */
@Composable
fun WordCardPreview(analysis: WordAnalysis, modifier: Modifier = Modifier) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.medium))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.medium))
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = analysis.surface,
                style = WolfyTheme.typography.screenTitle,
                color = colors.ink,
            )
            CefrBadge(analysis.cefr)
        }

        val pos = analysis.primaryPos?.let { posTitle(it) } ?: "неизвестная часть речи"
        Text(
            text = if (analysis.lemma == analysis.surface) pos else "$pos · от «${analysis.lemma}»",
            style = WolfyTheme.typography.body,
            color = colors.inkMuted,
        )

        if (analysis.facts.isNotEmpty()) {
            Rule()
            SectionLabel("Разбор слова")
            analysis.facts.forEach { fact ->
                Text(
                    text = "${fact.label}: ${fact.value}",
                    style = WolfyTheme.typography.body,
                    color = colors.ink,
                )
            }
        }

        Rule()
        SectionLabel("Частотность в живой речи")
        FrequencyScale(analysis.zipf)
    }
}

/**
 * Шкала частотности — тот самый ползунок «редко ↔ часто».
 *
 * Значение приходит по шкале Zipf, где 7 — это «the», а 0 — слово, которого в
 * корпусе нет вовсе. Делим на семь и получаем долю шкалы напрямую: шкала Zipf
 * логарифмическая и уже линейна для человеческого восприятия.
 */
@Composable
private fun FrequencyScale(zipf: Float, modifier: Modifier = Modifier) {
    val colors = WolfyTheme.colors
    val fraction = (zipf / 7f).coerceIn(0f, 1f)

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(WolfyTheme.spacing.tight)
                .background(colors.rule, CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(WolfyTheme.spacing.tight)
                    .background(colors.accent, CircleShape),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = WolfyTheme.spacing.tight),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Caption("редко")
            Caption("часто")
        }
    }
}

/** Название части речи по тегу из ядра. */
private fun posTitle(tag: String): String = when (tag) {
    "NOUN" -> "существительное"
    "VERB" -> "глагол"
    "ADJ" -> "прилагательное"
    "ADV" -> "наречие"
    "PRON" -> "местоимение"
    "DET" -> "определитель"
    "ADP" -> "предлог"
    "CONJ" -> "союз"
    "PART" -> "частица"
    "NUM" -> "числительное"
    else -> tag
}

/** Выбор темы оформления. */
@Composable
fun ThemePicker(
    selected: ReadingTheme,
    onSelect: (ReadingTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
    ) {
        ReadingTheme.entries.forEach { theme ->
            val active = theme == selected
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(theme) }
                    .background(theme.colors.paper, RoundedCornerShape(WolfyTheme.spacing.small))
                    .border(
                        width = if (active) 2.dp else WolfyTheme.spacing.rule,
                        color = if (active) WolfyTheme.colors.accent else WolfyTheme.colors.rule,
                        shape = RoundedCornerShape(WolfyTheme.spacing.small),
                    )
                    .padding(WolfyTheme.spacing.medium),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.tight),
            ) {
                Text(
                    text = "Aa",
                    style = WolfyTheme.typography.bookTitle,
                    color = theme.colors.ink,
                )
                Text(
                    text = theme.title,
                    style = WolfyTheme.typography.caption,
                    color = theme.colors.inkMuted,
                )
            }
        }
    }
}
