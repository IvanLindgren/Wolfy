package com.wolfy.ui.srs

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.srs.Deck
import com.wolfy.srs.DeckStatus
import com.wolfy.srs.Intensity
import com.wolfy.srs.SrsUiState
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Flame
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.widgets.pressable

/**
 * Хаб повторений.
 *
 * Экран отвечает на три вопроса и ни на один больше: сколько дней подряд,
 * что сегодня повторять и как часто вообще. Всё остальное — списки слов,
 * разбивка по книгам, история ответов — живёт этажом ниже, потому что человек
 * заходит сюда не изучать статистику, а позаниматься пять минут.
 *
 * Серия стоит первой и на выворотной плашке: она единственное, что здесь не
 * про работу, а про награду, и увидеть её надо до того, как увидишь, сколько
 * накопилось.
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

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                state.decks.forEach { status ->
                    DeckCard(
                        status = status,
                        onTrain = { onTrain(status.deck) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
                    modifier = Modifier.pressable(onClick = onOpenDecks),
                )
            }
        }
    }
}

/**
 * Баннер серии.
 *
 * Тёмная полоса во всю ширину — единственное тёмное пятно на светлом экране,
 * и оно здесь работает как первая полоса газеты: взгляд идёт туда раньше, чем
 * читатель успевает решить, куда смотреть.
 *
 * Огонь нарисован, а не набран эмодзи, и стоит прямо на плашке без золотого
 * кружка под ним. Кружок был нужен, чтобы отделить цветную картинку от фона;
 * своя фигура красится золотом сама, и подложка ей только мешала.
 */
@Composable
private fun StreakBanner(days: Int, best: Int) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val alive = days > 0

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.inverse, RoundedCornerShape(spacing.small))
            // Кромка нужна тёмным темам: там плашка почти сливается с бумагой,
            // и без неё баннер перестаёт быть предметом.
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .padding(spacing.large),
        horizontalArrangement = Arrangement.spacedBy(spacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Flame(
            color = if (alive) colors.gold else colors.onInverse.copy(alpha = 0.45f),
            size = 34.dp,
            alive = alive,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.hair)) {
            Text(
                text = if (alive) plural(days, "день", "дня", "дней") + " подряд" else "Начните серию",
                style = WolfyTheme.typography.bookTitle,
                color = colors.onInverse,
            )
            Text(
                text = if (best > 0) {
                    "Лучшая серия — " + plural(best, "день", "дня", "дней")
                } else {
                    "Один ответ в день — и серия идёт"
                },
                style = WolfyTheme.typography.caption,
                // От цвета плашки, а не от `rule`: линейка в тёмных темах
                // почти чёрная и на плашке была бы нечитаемой.
                color = colors.onInverse.copy(alpha = 0.65f),
            )
        }
    }
}

/**
 * Колода — карта, а не строка списка.
 *
 * Три одинаковых горизонтальных панели друг под другом честно перечисляли
 * содержимое и ничего больше. Но колода — это стопка карт, и выглядеть она
 * обязана стопкой: тогда «двадцать три» под словом «Слова» читается как
 * толщина пачки, которую предстоит пройти, а не как число в таблице.
 *
 * Стопка позади настоящая: карт в ней столько же, сколько в колоде, — до
 * двух, дальше глазу всё равно. Пустая колода стоит одна и без тени.
 */
@Composable
private fun DeckCard(
    status: DeckStatus,
    onTrain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val empty = status.due == 0
    val shape = RoundedCornerShape(spacing.medium)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Карту не сжимают, а вытягивают: при нажатии она поднимается над стопкой
    // и чуть заваливается — так её берут со стола. Пружина мягкая, чтобы
    // движение читалось как вес карты, а не как рывок.
    val motion = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    val lift by animateDpAsState(
        targetValue = if (pressed && !empty) (-6).dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "lift",
    )
    val tilt by animateFloatAsState(
        targetValue = if (pressed && !empty) -2.5f else 0f,
        animationSpec = motion,
        label = "tilt",
    )
    val grow by animateFloatAsState(
        targetValue = if (pressed && !empty) 1.03f else 1f,
        animationSpec = motion,
        label = "grow",
    )

    Box(modifier.aspectRatio(CARD_RATIO)) {
        // Остаток стопки: две карты со сдвигом. Они не кликаются и живут
        // только затем, чтобы у передней была толщина.
        val behind = minOf(status.total - status.due, 2).coerceAtLeast(if (empty) 0 else 1)
        repeat(behind) { layer ->
            val step = (behind - layer) * 3
            Box(
                Modifier
                    .fillMaxSize()
                    .offset(x = step.dp, y = step.dp)
                    .background(colors.surface, shape)
                    .border(spacing.rule, colors.rule, shape),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .offset(y = lift)
                .scale(grow)
                .rotate(tilt)
                .background(colors.surface, shape)
                .border(
                    width = if (empty) spacing.rule else 2.dp,
                    color = if (empty) colors.rule else colors.ink,
                    shape = shape,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !empty,
                    onClick = onTrain,
                )
                .padding(spacing.medium),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = when (status.deck) {
                    Deck.Words -> "Aa"
                    Deck.Phrases -> "«»"
                    Deck.Rules -> "§"
                },
                style = WolfyTheme.typography.bookTitle,
                color = if (empty) colors.inkMuted else colors.accent,
            )

            Text(
                text = if (empty) "✓" else status.due.toString(),
                style = WolfyTheme.typography.screenTitle,
                color = if (empty) colors.inkMuted else colors.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
                Text(
                    text = status.deck.title,
                    style = WolfyTheme.typography.body,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (empty) "на сегодня всё" else status.deck.subtitle,
                    style = WolfyTheme.typography.sectionLabel,
                    color = colors.inkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProgressLine(status.progress)
            }
        }
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
 * Четыре режима в один ряд, выбранный — тёмной таблеткой. Не выпадающий
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
                        if (active) colors.inverse else colors.rule,
                        RoundedCornerShape(spacing.xlarge),
                    )
                    .pressable { onSelect(intensity) }
                    .padding(vertical = spacing.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = intensity.title,
                    style = WolfyTheme.typography.caption,
                    color = if (active) colors.onInverse else colors.inkMuted,
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

/** Пропорции игральной карты — по ним фигура и узнаётся как карта. */
private const val CARD_RATIO = 0.66f

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
