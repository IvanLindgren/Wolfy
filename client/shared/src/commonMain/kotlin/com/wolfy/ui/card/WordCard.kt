package com.wolfy.ui.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wolfy.ffi.Finding
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.CefrBadge
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel

/**
 * Карточка слова, всплывающая снизу.
 *
 * Главное свойство — она открывается мгновенно. Всё, что ядро посчитало на
 * устройстве (начальная форма, часть речи, разбор окончания, частотность,
 * уровень), рисуется сразу; перевод приезжает из сети и занимает своё место
 * позже. Карточка, которая ждёт сеть, чтобы показаться, ломает само ощущение
 * чтения — ради этого и держится локальное ядро.
 */
@Composable
fun WordCardSheet(
    state: WordCardState?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Карточка перекрывает нижнюю часть читалки, но не всю страницу: видеть
        // фразу, из которой пришло слово, важнее, чем показать все разделы
        // разбора сразу. Доля от окна, а не фиксированная высота: на телефоне
        // и на большом экране «две трети» выглядят одинаково уместно, а
        // 520 точек на телефоне закрыли бы почти всё.
        val maxCardHeight = maxHeight * 0.66f
        // Затемнение фона: страница остаётся видна, но уходит на второй план.
        AnimatedVisibility(
            visible = state != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.ink.copy(alpha = 0.25f))
                    .clickable(onClick = onDismiss),
            )
        }

        AnimatedVisibility(
            visible = state != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            // Пружина без отскока: карточка должна ощущаться как лист бумаги,
            // который положили на страницу, а не как выпрыгнувший элемент.
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            if (state != null) {
                CardBody(state, onSave, onDismiss, maxHeight = maxCardHeight)
            }
        }
    }
}

@Composable
private fun CardBody(
    state: WordCardState,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Dp,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(
                colors.surface,
                RoundedCornerShape(topStart = spacing.large, topEnd = spacing.large),
            )
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        // Ручка для перетаскивания: показывает, что панель можно закрыть.
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(spacing.tight)
                .background(colors.rule, CircleShape)
                .clickable(onClick = onDismiss),
        )

        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Header(state)
            Translation(state)

            if (state.analysis.facts.isNotEmpty()) {
                Rule()
                SectionLabel("Разбор слова")
                state.analysis.facts.forEach { fact ->
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        Text(
                            text = fact.label,
                            style = typography.caption,
                            color = colors.inkMuted,
                        )
                        Text(
                            text = fact.value,
                            style = typography.body,
                            color = colors.ink,
                        )
                    }
                }
            }

            if (state.grammar.isNotEmpty()) {
                Rule()
                SectionLabel("Грамматика предложения")
                state.grammar.forEach { GrammarNote(it) }
            }

            Rule()
            SectionLabel("Частотность в живой речи")
            FrequencyBar(state.analysis.zipf)

            SaveButton(saved = state.saved, onSave = onSave)
        }
    }
}

@Composable
private fun Header(state: WordCardState) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = state.analysis.surface,
                style = typography.screenTitle,
                color = colors.ink,
            )
            val tag = state.analysis.primaryPos
            val pos = tag?.let(::posTitle)
            val subtitle = when {
                pos == null -> "нет в словаре"
                state.analysis.lemma == state.analysis.surface.lowercase() -> pos
                else -> "$pos · от «${state.analysis.lemma}»"
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Метка цвета части речи — та же, что красит грамматику на
                // странице. Читатель запоминает цвет раньше, чем название:
                // «синее — существительное» усваивается с третьего раза.
                tag?.let { PosMark(it) }
                Text(text = subtitle, style = typography.body, color = colors.inkMuted)
            }
        }
        CefrBadge(state.analysis.cefr)
    }
}

/** Квадратик цвета части речи. */
@Composable
private fun PosMark(tag: String) {
    val palette = WolfyTheme.colors.partsOfSpeech
    val color = when (tag) {
        "NOUN" -> palette.noun
        "VERB" -> palette.verb
        "ADJ" -> palette.adjective
        "ADV" -> palette.adverb
        "PRON" -> palette.pronoun
        // Служебным частям речи своего цвета не досталось намеренно: их пять
        // видов, они встречаются в каждой строке, и раскрашенная страница
        // перестала бы читаться. Серый — это «служебное слово».
        else -> WolfyTheme.colors.inkMuted
    }
    Box(
        Modifier
            .size(WolfyTheme.spacing.small)
            .background(color, RoundedCornerShape(WolfyTheme.spacing.hair)),
    )
}

/**
 * Перевод — единственная часть карточки, которая приходит из сети.
 *
 * Пока он едет, на его месте стоит подпись, а не пустота: иначе карточка
 * дёргается, когда перевод приезжает и раздвигает содержимое.
 */
@Composable
private fun Translation(state: WordCardState) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography

    when (val translation = state.translation) {
        is TranslationState.Loading -> Text(
            text = "Перевод загружается…",
            style = typography.body,
            color = colors.inkMuted,
        )

        is TranslationState.Ready -> Column(
            verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.tight),
        ) {
            Text(
                text = "«${translation.text}»",
                style = typography.translation,
                color = colors.ink,
            )
            if (translation.context.isNotBlank()) {
                Text(
                    text = translation.context,
                    style = typography.caption,
                    color = colors.inkMuted,
                )
            }
        }

        is TranslationState.Failed -> Text(
            text = translation.message,
            style = typography.caption,
            color = colors.inkMuted,
        )

        TranslationState.Idle -> Unit
    }
}

/**
 * Одно грамматическое правило, найденное в предложении.
 *
 * Формула стоит рядом с названием, а не под объяснением, и это не украшение:
 * правило запоминается схемой, а объяснение только помогает её понять. Читатель,
 * который уже знает «have/has + V3», по одной формуле узнаёт время быстрее, чем
 * прочитает заголовок.
 */
@Composable
private fun GrammarNote(finding: Finding) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(spacing.small))
            .padding(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.tight),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = finding.title, style = typography.body, color = colors.ink)
            Text(text = finding.formula, style = typography.caption, color = colors.accent)
        }
        Text(text = finding.explanation, style = typography.caption, color = colors.inkMuted)
    }
}

@Composable
private fun FrequencyBar(zipf: Float) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    // Шкала Zipf уже логарифмическая и потому линейна для восприятия:
    // 7 — «the», 0 — слово, которого в корпусе нет вовсе.
    val fraction = (zipf / 7f).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(spacing.tight)
                .background(colors.rule, CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(spacing.tight)
                    .background(colors.accent, CircleShape),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = spacing.tight),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("редко", style = WolfyTheme.typography.caption, color = colors.inkMuted)
            Text("часто", style = WolfyTheme.typography.caption, color = colors.inkMuted)
        }
    }
}

@Composable
private fun SaveButton(saved: Boolean, onSave: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (saved) colors.surface else colors.ink,
                RoundedCornerShape(spacing.huge),
            )
            .border(
                spacing.rule,
                if (saved) colors.rule else colors.ink,
                RoundedCornerShape(spacing.huge),
            )
            .clickable(onClick = onSave)
            .padding(vertical = spacing.medium),
    ) {
        Text(
            text = if (saved) "В колоде книги ✓ · убрать" else "+ В колоду книги",
            style = WolfyTheme.typography.button,
            color = if (saved) colors.inkMuted else colors.paper,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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
