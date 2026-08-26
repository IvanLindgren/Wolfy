package com.wolfy.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.wolfy.data.dictionary.DictionaryStatus
import com.wolfy.ffi.Exercise
import com.wolfy.ffi.WordAnalysis
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.CefrBadge
import com.wolfy.widgets.PrimaryButton
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.widgets.pressable

@Composable
fun WelcomeScreen(
    analysis: WordAnalysis,
    exercise: Exercise?,
    dictionary: DictionaryStatus,
    onDownloadDictionary: () -> Unit,
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val motion = WolfyTheme.motion
    var step by remember { mutableIntStateOf(0) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .padding(PaddingValues(spacing.pageMargin)),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "Пропустить",
                style = WolfyTheme.typography.button,
                color = colors.inkMuted,
                modifier = Modifier.pressable(onClick = onSkip).padding(spacing.small),
            )
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally(tween(motion.calm, easing = Curves.Paper)) { it / 6 } +
                    fadeIn(tween(motion.calm))) togetherWith
                    (slideOutHorizontally(tween(motion.quick)) { -it / 6 } + fadeOut(tween(motion.quick)))
            },
            label = "welcome",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.large),
            ) {
                when (current) {
                    0 -> ReadingStep { step = 1 }
                    1 -> WordStep(analysis)
                    2 -> TrainingStep(exercise)
                    else -> DictionaryStep(dictionary, onDownloadDictionary)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(STEPS) { index ->
                val width by animateDpAsState(
                    if (index == step) spacing.xlarge else spacing.small,
                    tween(motion.quick, easing = Curves.Paper),
                    label = "welcome dot",
                )
                Box(
                    Modifier
                        .padding(horizontal = spacing.tight)
                        .width(width)
                        .background(if (index == step) colors.accent else colors.rule, RoundedCornerShape(spacing.small))
                        .padding(vertical = spacing.hair),
                )
            }
        }
        PrimaryButton(
            text = if (step == STEPS - 1) "Начать читать" else "Дальше",
            onClick = { if (step == STEPS - 1) onFinish() else step += 1 },
        )
    }
}

@Composable
private fun ReadingStep(onWord: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Text("Читайте как читали", style = WolfyTheme.typography.screenTitle, color = colors.ink)
    Text(
        "Коснитесь незнакомого слова прямо в книге. Страница останется на месте.",
        style = WolfyTheme.typography.body,
        color = colors.inkMuted,
    )
    val paragraph = remember(colors.accent) {
        buildAnnotatedString {
            append("The old library held a curious ")
            withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) { append("serendipity") }
            append(" between its quiet shelves.")
        }
    }
    val wordStart = paragraph.text.indexOf("serendipity")
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        paragraph,
        style = WolfyTheme.typography.reader,
        color = colors.ink,
        onTextLayout = { layout = it },
        modifier = Modifier
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .pointerInput(wordStart) {
                detectTapGestures { point ->
                    val offset = layout?.getOffsetForPosition(point) ?: return@detectTapGestures
                    if (offset in wordStart until (wordStart + "serendipity".length)) onWord()
                }
            }
            .padding(spacing.large),
    )
}

@Composable
private fun WordStep(analysis: WordAnalysis) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Text("Слово объясняет себя", style = WolfyTheme.typography.screenTitle, color = colors.ink)
    Column(
        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(spacing.large)).padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(analysis.surface, style = WolfyTheme.typography.screenTitle, color = colors.ink)
            CefrBadge(analysis.cefr)
        }
        Text("начальная форма: ${analysis.lemma}", style = WolfyTheme.typography.body, color = colors.inkMuted)
        Text(
            analysis.facts.joinToString(" · ") { "${it.label}: ${it.value}" }.ifBlank { "форма и часть речи разобраны локально" },
            style = WolfyTheme.typography.caption,
            color = colors.ink,
        )
    }
}

@Composable
private fun TrainingStep(exercise: Exercise?) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Text("Тренируйте слова из своих книг", style = WolfyTheme.typography.screenTitle, color = colors.ink)
    Column(
        Modifier.fillMaxWidth().border(spacing.rule, colors.rule, RoundedCornerShape(spacing.large)).padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        SectionLabel(exercise?.topic ?: "Грамматика")
        Text(exercise?.sentence ?: "She ___ reading since morning.", style = WolfyTheme.typography.bookTitle, color = colors.ink)
        Text(exercise?.translation ?: "Она читает с самого утра.", style = WolfyTheme.typography.translation, color = colors.inkMuted)
    }
}

@Composable
private fun DictionaryStep(status: DictionaryStatus, onDownload: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically) {
        WolfySticker(Sticker.Scroll, size = spacing.huge * 3)
        Text("Словарь на 77 тысяч слов", style = WolfyTheme.typography.screenTitle, color = colors.ink)
    }
    Text(
        "Русские переводы, МФА и английские толкования работают без сети. Архив уже лежит в приложении.",
        style = WolfyTheme.typography.body,
        color = colors.inkMuted,
    )
    if (status !is DictionaryStatus.Ready) {
        Text(
            if (status is DictionaryStatus.Downloading) "Словарь устанавливается" else "Установить словарь",
            style = WolfyTheme.typography.button,
            color = colors.accent,
            modifier = Modifier
                .pressable(enabled = status !is DictionaryStatus.Downloading, onClick = onDownload)
                .padding(spacing.small),
        )
    } else {
        Text("Словарь установлен", style = WolfyTheme.typography.button, color = colors.ink)
    }
}

private const val STEPS = 4
