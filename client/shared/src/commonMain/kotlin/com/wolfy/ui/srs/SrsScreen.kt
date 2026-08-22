package com.wolfy.ui.srs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.srs.Deck
import com.wolfy.srs.DeckStatus
import com.wolfy.srs.Intensity
import com.wolfy.srs.SrsUiState
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker

/**
 * Хаб повторений.
 *
 * Экран отвечает на три вопроса и ни на один больше: сколько дней подряд,
 * что сегодня повторять и как часто вообще. Всё остальное — списки слов,
 * разбивка по книгам, история ответов — живёт этажом ниже, потому что человек
 * заходит сюда не изучать статистику, а позаниматься пять минут.
 *
 * Серия стоит первой и на чёрном: она единственное, что здесь не про работу, а
 * про награду, и увидеть её надо до того, как увидишь, сколько накопилось.
 */
@Composable
fun SrsScreen(
    state: SrsUiState,
    onTrain: (Deck) -> Unit,
    onIntensity: (Intensity) -> Unit,
    onOpenDecks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.paper),
        contentPadding = PaddingValues(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        item { StreakBanner(days = state.streakDays, best = state.bestStreak) }

        item {
            Text(
                text = "Колоды на сегодня",
                style = WolfyTheme.typography.bookTitle,
                color = colors.ink,
            )
        }

        items(state.decks.size) { index ->
            val status = state.decks[index]
            DeckRow(status = status, onTrain = { onTrain(status.deck) })
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                SectionLabel("Интенсивность повторений")
                IntensityPicker(selected = state.intensity, onSelect = onIntensity)
                Text(
                    text = state.intensity.hint,
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
        }

        item { WolfyLine(due = state.due, streak = state.streakDays) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Rule()
                Text(
                    text = "Слова по книгам →",
                    style = WolfyTheme.typography.caption,
                    color = colors.accent,
                    modifier = Modifier.clickable(onClick = onOpenDecks),
                )
            }
        }
    }
}

/**
 * Баннер серии.
 *
 * Чёрный прямоугольник во всю ширину — единственное тёмное пятно на светлом
 * экране, и оно здесь работает как первая полоса газеты: взгляд идёт туда
 * раньше, чем читатель успевает решить, куда смотреть.
 */
@Composable
private fun StreakBanner(days: Int, best: Int) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.ink, RoundedCornerShape(spacing.small))
            .padding(spacing.large),
        horizontalArrangement = Arrangement.spacedBy(spacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(colors.gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🔥", style = WolfyTheme.typography.body)
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.hair)) {
            Text(
                text = if (days > 0) plural(days, "день", "дня", "дней") + " подряд" else "Начните серию",
                style = WolfyTheme.typography.bookTitle,
                color = colors.paper,
            )
            Text(
                text = if (best > 0) {
                    "Лучшая серия — " + plural(best, "день", "дня", "дней")
                } else {
                    "Один ответ в день — и серия идёт"
                },
                style = WolfyTheme.typography.caption,
                color = colors.rule,
            )
        }
    }
}

/**
 * Колода: значок, название, счётчик и полоска.
 *
 * Число справа крупное и красное — оно и есть содержание строки. Полоска под
 * названием показывает не «сколько осталось сегодня», а сколько от колоды
 * выучено совсем: сегодняшнее число меняется каждый день, а полоска растёт и
 * не убывает, и смотреть на неё приятно.
 */
@Composable
private fun DeckRow(status: DeckStatus, onTrain: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val empty = status.due == 0

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.small))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .clickable(enabled = !empty, onClick = onTrain)
            .padding(spacing.large),
        horizontalArrangement = Arrangement.spacedBy(spacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (status.deck) {
                Deck.Words -> "Aa"
                Deck.Phrases -> "«»"
                Deck.Rules -> "§"
            },
            style = WolfyTheme.typography.bookTitle,
            color = colors.accent,
        )

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
            Text(
                text = status.deck.title,
                style = WolfyTheme.typography.bookTitle,
                color = colors.ink,
            )
            Text(
                text = if (empty) "на сегодня всё" else status.deck.subtitle,
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
            ProgressLine(status.progress)
        }

        Text(
            text = if (empty) "✓" else status.due.toString(),
            style = WolfyTheme.typography.screenTitle,
            color = if (empty) colors.inkMuted else colors.accent,
        )
    }
}

/** Тонкая полоска прогресса — та же, что под словом в карточке. */
@Composable
private fun ProgressLine(fraction: Float) {
    val colors = WolfyTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(WolfyTheme.spacing.hair)
            .background(colors.rule, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(WolfyTheme.spacing.hair)
                .background(colors.accent, CircleShape),
        )
    }
}

/**
 * Переключатель интенсивности.
 *
 * Четыре режима в один ряд, выбранный — чёрной таблеткой. Не выпадающий
 * список: выбор из четырёх, который читатель меняет раз в месяц, обязан быть
 * виден целиком — иначе он не узнает, что у него вообще есть выбор.
 */
@Composable
private fun IntensityPicker(selected: Intensity, onSelect: (Intensity) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.rule, RoundedCornerShape(spacing.xlarge))
            .padding(spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(spacing.hair),
    ) {
        Intensity.entries.forEach { intensity ->
            val active = intensity == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) colors.ink else colors.rule,
                        RoundedCornerShape(spacing.xlarge),
                    )
                    .clickable { onSelect(intensity) }
                    .padding(vertical = spacing.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = intensity.title,
                    style = WolfyTheme.typography.caption,
                    color = if (active) colors.paper else colors.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Вульфи с репликой.
 *
 * Реплика зависит от того, что на экране: пустая колода и колода из сорока
 * карточек — разные новости, и одна и та же фраза на оба случая читалась бы
 * как отписка.
 */
@Composable
private fun WolfyLine(due: Int, streak: Int) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    val line = when {
        due == 0 && streak > 0 -> "«На сегодня всё. Серия цела.»"
        due == 0 -> "«Пусто. Почитайте — слова наберутся сами.»"
        due > 30 -> "«Накопилось. Начнём с двадцати.»"
        streak > 0 -> "«Кофе готов. Слова — тоже.»"
        else -> "«Пять минут — и день не пропал.»"
    }
    val sticker = when {
        due == 0 && streak > 0 -> Sticker.Celebrate
        due == 0 -> Sticker.Sleep
        due > 30 -> Sticker.Sword
        else -> Sticker.HappyWave
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
                .padding(spacing.large),
        ) {
            Text(
                text = line,
                style = WolfyTheme.typography.translation,
                fontStyle = FontStyle.Italic,
                color = colors.ink,
            )
        }
        WolfySticker(sticker, size = 96.dp)
    }
}

/** Русское склонение по числу. */
internal fun plural(count: Int, one: String, few: String, many: String): String {
    val tens = count % 100
    val word = if (tens in 11..14) {
        many
    } else {
        when (count % 10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }
    return "$count $word"
}
