package com.wolfy.ui.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wolfy.ffi.Finding
import com.wolfy.platform.rememberClipboard
import com.wolfy.resources.Res
import com.wolfy.resources.copy_quote
import com.wolfy.resources.quote_clipboard_label
import com.wolfy.resources.quote_copied
import com.wolfy.resources.quote_copy_failed
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.settling
import com.wolfy.ui.nav.FLIGHT_CARDS
import com.wolfy.widgets.Appear
import com.wolfy.widgets.CefrBadge
import com.wolfy.widgets.Disclosure
import com.wolfy.widgets.LocalFlight
import com.wolfy.widgets.PhraseBlocks
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.WolfyCompanion
import com.wolfy.widgets.pressable
import com.wolfy.widgets.rememberLaunchPad
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Карточка слова, всплывающая снизу.
 *
 * Главное свойство — она открывается мгновенно. Второе — она отвечает на один
 * вопрос за раз. Верх карточки занимает ровно то, зачем карточку открывают:
 * перевод во фразе, толкование того же значения и грамматические признаки
 * этой формы. Строение слова, частотность, найденные правила и подсказки
 * Вульфи никуда не делись — они лежат в раскрытии «Подробнее», до которого
 * рука доходит, когда главное уже прочитано.
 *
 * Всё локальное рисуется сразу; перевод приезжает из сети позже и занимает
 * своё место без перестановки остального.
 */
@Composable
fun WordCardSheet(
    state: WordCardState?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSavePhrase: () -> Unit,
    onPronounce: () -> Unit,
    onOpenRule: (String) -> Unit,
    onExplainPhrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Карточка перекрывает нижнюю часть читалки, но не всю страницу: видеть
        // фразу, из которой пришло слово, важнее, чем показать все разделы
        // разбора сразу. Доля от окна, а не фиксированная высота: на телефоне
        // и на большом экране «две трети» выглядят одинаково уместно, а
        // 520 точек на телефоне закрыли бы почти всё.
        val maxCardHeight = maxHeight * 0.90f
        // Затемнение фона: страница остаётся видна, но уходит на второй план.
        //
        // Темп — из темы. Значения по умолчанию у `fadeIn` и у пружины ниже
        // своих собственных, и настройка «уменьшить движение» до них не
        // доходила: карточка выезжала снизу ровно так же, как и без неё.
        val motion = WolfyTheme.motion
        AnimatedVisibility(
            visible = state != null,
            enter = fadeIn(motion.paced(motion.quick)),
            exit = fadeOut(motion.paced(motion.quick)),
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
                animationSpec = motion.settling(),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = motion.paced(motion.quick),
                targetOffsetY = { it },
            ),
        ) {
            if (state != null) {
                CardBody(
                    state = state,
                    onSave = onSave,
                    onSavePhrase = onSavePhrase,
                    onPronounce = onPronounce,
                    onDismiss = onDismiss,
                    onOpenRule = onOpenRule,
                    onExplainPhrase = onExplainPhrase,
                    maxHeight = maxCardHeight,
                )
            }
        }
    }
}

private enum class CardMode(val title: String) {
    Word("Слово"),
    Phrase("Фраза"),
}

@Composable
private fun CardBody(
    state: WordCardState,
    onSave: () -> Unit,
    onSavePhrase: () -> Unit,
    onPronounce: () -> Unit,
    onDismiss: () -> Unit,
    onOpenRule: (String) -> Unit,
    onExplainPhrase: () -> Unit,
    maxHeight: Dp,
) {
    val spacing = WolfyTheme.spacing
    // Карточка после выделения фразы обязана встретить читателя вкладкой
    // «Фраза»: он только что взял фразу — показывать вкладку слова значит
    // делать за него выбор заново.
    var mode by remember(state.token.start, state.context) {
        mutableStateOf(if (state.openOnPhrase) CardMode.Phrase else CardMode.Word)
    }
    val flight = LocalFlight.current
    val (launchModifier, launchBounds) = rememberLaunchPad()

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(
                WolfyTheme.colors.surface,
                RoundedCornerShape(topStart = spacing.large, topEnd = spacing.large),
            )
            .padding(horizontal = spacing.large, vertical = spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        DragHandle(onDismiss, Modifier.align(Alignment.CenterHorizontally))

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Header(state, onPronounce, launchModifier)
            CardModeTabs(mode = mode, onMode = { mode = it })
            val motion = WolfyTheme.motion
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    fadeIn(tween(motion.quick, easing = Curves.Paper)) togetherWith
                        fadeOut(tween(motion.instant, easing = Curves.Paper))
                },
                label = "card mode",
            ) { selected ->
                when (selected) {
                    CardMode.Word -> WordEssentials(state, onOpenRule)
                    CardMode.Phrase -> PhraseEssentials(state, onOpenRule, onExplainPhrase)
                }
            }
        }

        // Сохранение живёт внизу, вне прокрутки: действие одно и обязано быть
        // на месте всегда, а не выезжать по мере чтения разделов.
        SaveArea(
            mode = mode,
            state = state,
            onSave = {
                if (mode == CardMode.Word && !state.saved) {
                    launchBounds()?.let { bounds ->
                        flight.send(bounds, FLIGHT_CARDS, state.analysis.surface)
                    }
                }
                if (mode == CardMode.Word) onSave() else onSavePhrase()
            },
        )
    }
}

/**
 * Ручка для перетаскивания.
 *
 * Тянуть можно только за неё, а не за карточку целиком: внутри карточки свой
 * прокручиваемый столбец, и жест «вниз» там означает «читать дальше», а не
 * «закрыть». Ручка нарочно шире и выше, чем видимая полоска: три точки в
 * высоту пальцем не поймать.
 */
@Composable
private fun DragHandle(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Box(
        modifier
            .pointerInput(Unit) {
                var travelled = 0f
                val enough = SWIPE_TO_CLOSE.toPx()
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (travelled > enough) onDismiss() },
                    onDragCancel = { travelled = 0f },
                ) { _, amount -> travelled += amount }
            }
            .padding(horizontal = spacing.large, vertical = spacing.small)
            .pressable(onClick = onDismiss),
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(spacing.tight)
                .background(colors.rule, CircleShape),
        )
    }
}

/**
 * Переключатель «слово / фраза» с едущим фоном.
 *
 * Подложка не появляется заново на выбранной половине, а переезжает на неё:
 * два состояния одной вещи обязаны выглядеть как одна вещь в двух положениях.
 */
@Composable
private fun CardModeTabs(mode: CardMode, onMode: (CardMode) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val motion = WolfyTheme.motion

    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(spacing.huge))
            .padding(spacing.tight),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(TAB_HEIGHT)) {
            val cell = maxWidth / 2
            val shift by animateDpAsState(
                targetValue = cell * if (mode == CardMode.Word) 0f else 1f,
                animationSpec = tween(motion.calm, easing = Curves.Paper),
                label = "tab shift",
            )
            Box(
                Modifier
                    .offset(x = shift)
                    .width(cell)
                    .fillMaxHeight()
                    .background(colors.inverse, RoundedCornerShape(spacing.huge)),
            )
            Row(Modifier.fillMaxSize()) {
                CardMode.entries.forEach { item ->
                    val tint by animateColorAsState(
                        targetValue = if (mode == item) colors.onInverse else colors.inkMuted,
                        animationSpec = tween(motion.quick, easing = Curves.Paper),
                        label = "tab tint",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressable(enabled = mode != item, onClick = { onMode(item) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.title,
                            style = WolfyTheme.typography.button,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}

private val TAB_HEIGHT = 36.dp

// --- Режим «слово»: главное --------------------------------------------------

/** Три главных блока и раскрытие со всем остальным. */
@Composable
private fun WordEssentials(
    state: WordCardState,
    onOpenRule: (String) -> Unit,
) {
    val spacing = WolfyTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        Appear(0) {
            PrimaryCard(title = "Перевод в контексте") {
                Translation(state)
            }
        }
        Appear(1) {
            PrimaryCard(title = "Определение в контексте") {
                MainSense(state)
            }
        }
        Appear(2) {
            PrimaryCard(title = "Основные грамматические признаки") {
                FormSummary(state)
            }
        }
        Appear(3) {
            Disclosure(
                label = "Подробнее о слове",
                hint = "Грамматика фразы, строение, частотность и другие значения",
            ) {
                FactTags(state)
                if (state.grammar.isNotEmpty()) {
                    GrammarList(
                        findings = state.grammar,
                        label = "Грамматика этой фразы",
                        onOpen = onOpenRule,
                    )
                }
                OtherSenses(state)
                WordStructure(state)
                ContextPhrases(state)
                Frequency(state.analysis.zipf)
                FullBreakdown(state)
                WolfyLexicalTip(state)
            }
        }
    }
}

/**
 * Один главный блок: заголовок капслоком и содержимое.
 *
 * Три блока одного вида читаются как три ответа на три вопроса; список из
 * разнородных секций тем же видом читался бы как меню без блюд.
 */
@Composable
private fun PrimaryCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(spacing.small))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .padding(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(title)
            trailing?.invoke()
        }
        content()
    }
}

/**
 * Перевод — единственная часть карточки, которая приходит из сети.
 *
 * Пока он едет, на его месте стоит словарная строка из офлайн-базы, а не
 * пустота: иначе карточка дёргается, когда перевод приезжает и раздвигает
 * содержимое.
 */
@Composable
private fun Translation(state: WordCardState) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography
    val offline = (state.definition as? DefinitionState.Ready)
        ?.entry?.translations.orEmpty()
        .distinct()
        .joinToString(", ")

    when (val translation = state.translation) {
        is TranslationState.Loading -> if (offline.isNotBlank()) {
            LocalTranslation(offline, "Перевод фразы загружается…")
        } else {
            Text(
                text = "Перевод загружается…",
                style = typography.body,
                color = colors.inkMuted,
            )
        }

        is TranslationState.Ready -> Appear(0) {
            Column(verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small)) {
                // Словарная строка: что значит само слово. Курсив гарамона
                // здесь не украшение — так набирают словарные статьи, и глаз
                // отличает толкование от текста книги, не читая его.
                Text(
                    text = translation.word.ifBlank { offline },
                    style = typography.translation,
                    color = colors.ink,
                )
                // А это уже другой вопрос — что сказано во всей фразе. Ради
                // него слово и переводится в контексте, а не по словарю.
                if (translation.sentence.isNotBlank()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(WolfyTheme.spacing.small))
                            .padding(WolfyTheme.spacing.small),
                        verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.hair),
                    ) {
                        SectionLabel("Фраза целиком")
                        Text(
                            text = translation.sentence,
                            style = typography.body,
                            color = colors.ink,
                        )
                    }
                }
            }
        }

        is TranslationState.Failed -> if (offline.isNotBlank()) {
            LocalTranslation(offline, "Перевод всей фразы требует связи с сервером.")
        } else {
            Text(
                text = translation.message,
                style = typography.caption,
                color = colors.inkMuted,
            )
        }

        TranslationState.Idle -> Unit
    }
}

@Composable
private fun LocalTranslation(value: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.tight)) {
        SectionLabel("Перевод слова")
        Text(
            value,
            style = WolfyTheme.typography.translation,
            color = WolfyTheme.colors.ink,
        )
        Text(
            note,
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
    }
}

/**
 * Толкование именно этого значения слова.
 *
 * Словарь перечисляет значения от частого к редкому, но во фразе слово
 * уже выбрало себе значение само — частью речи, с которой оно здесь стоит.
 */
@Composable
private fun MainSense(state: WordCardState) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography
    val spacing = WolfyTheme.spacing

    when (val definition = state.definition) {
        DefinitionState.Loading -> Text(
            text = "Ищу толкование…",
            style = typography.caption,
            color = colors.inkMuted,
        )

        is DefinitionState.Ready -> {
            val position = contextualPos(state.chunks, state.sentenceTokens, state.token)
                ?: state.analysis.primaryPos
            val main = primarySense(definition.entry.senses, position)
            if (main == null) {
                SenseMissing()
                return
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
                Text(
                    text = main.definition,
                    style = typography.translation,
                    color = colors.ink,
                )
                if (main.pos.isNotBlank()) {
                    Text(
                        text = posTitle(main.pos),
                        style = typography.caption,
                        color = colors.partsOfSpeech.forTag(main.pos) ?: colors.inkMuted,
                    )
                }
            }
        }

        DefinitionState.Idle, DefinitionState.Missing -> SenseMissing()
    }
}

@Composable
private fun SenseMissing() {
    Text(
        text = "Толкование пока не нашлось: скачайте офлайн-словарь в настройках " +
            "или проверьте связь.",
        style = WolfyTheme.typography.caption,
        color = WolfyTheme.colors.inkMuted,
    )
}

/**
 * Основные грамматические признаки: форма во фразе, часть речи и два самых
 * веских объяснения.
 *
 * Полный разбор лежит в подробностях. Здесь — то, за что глаз цепляется в
 * первую секунду: какая это форма, какой частью речи она стала и почему.
 */
@Composable
private fun FormSummary(state: WordCardState) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val analysis = state.analysis

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        // Форма → начальная форма. Окончание выделено цветом: именно оно
        // объясняет, почему слово выглядит не как в словаре.
        Text(
            text = buildAnnotatedString {
                append(analysis.surface.lowercase())
                if (analysis.lemma != analysis.surface.lowercase()) {
                    append("  →  ")
                    withStyle(SpanStyle(color = colors.inkMuted)) {
                        append(analysis.lemma)
                    }
                }
            },
            style = WolfyTheme.typography.bookTitle,
            color = colors.ink,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            val position = contextualPos(state.chunks, state.sentenceTokens, state.token)
                ?: analysis.primaryPos
            if (position != null) {
                PosPill(position)
            } else {
                Chip("нет в лексиконе")
            }
            analysis.facts.take(2).forEach { fact -> Chip(fact.value, label = fact.label) }
            if (analysis.facts.isEmpty()) Chip(formTitle(analysis.form))
        }
    }
}

// --- Режим «фраза» -----------------------------------------------------------

@Composable
private fun PhraseEssentials(
    state: WordCardState,
    onOpenRule: (String) -> Unit,
    onExplainPhrase: () -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        // Выделенная фраза показывается отдельно и без задержки появления:
        // читатель должен сразу видеть, что переводится/объясняется именно
        // его выбор, а не всё предложение вокруг.
        if (state.phraseText != null && state.phraseText != state.context) {
            PrimaryCard(title = "Выделенная фраза") {
                Text(
                    state.phraseText,
                    style = WolfyTheme.typography.translation,
                    color = WolfyTheme.colors.ink,
                )
            }
        }
        Appear(0) {
            PrimaryCard(title = "Фраза и части речи") {
                if (state.chunks.isNotEmpty()) {
                    PhraseBlocks(state.sentenceTokens, state.chunks, state.markers)
                } else {
                    Text(
                        state.context,
                        style = WolfyTheme.typography.translation,
                        color = WolfyTheme.colors.ink,
                    )
                }
            }
        }
        Appear(1) {
            PrimaryCard(title = "Перевод в этом контексте") {
                SentenceTranslation(state)
            }
        }
        Appear(2) {
            Disclosure(
                label = "Подробнее о фразе",
                hint = "Правила, которые движок нашёл в этом предложении",
            ) {
                if (state.grammar.isNotEmpty()) {
                    GrammarList(
                        findings = state.grammar,
                        label = "Грамматика фразы",
                        onOpen = onOpenRule,
                    )
                }
                WolfyPhraseTip(state)
            }
        }
        Appear(3) { BetaPhraseExplanation(state.betaExplanation, onExplainPhrase) }
    }
}

@Composable
private fun BetaPhraseExplanation(state: BetaPhraseState, onAsk: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    PrimaryCard(title = "Почему фраза построена так · Beta") {
        Text(
            "ИИ может ошибаться. До 10 запросов в день.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )
        when (state) {
            BetaPhraseState.Idle -> Text("Спросить Gemini", style = WolfyTheme.typography.button, color = colors.accent, modifier = Modifier.pressable(onClick = onAsk).padding(top = spacing.small))
            BetaPhraseState.Loading -> {
                // Запрос уже в полёте: повторное нажатие ничего не должно
                // запускать заново.
                Text("Gemini разбирает фразу…", style = WolfyTheme.typography.caption, color = colors.inkMuted)
            }
            is BetaPhraseState.Failed -> {
                // Ошибка остаётся на экране вместе с кнопкой повтора: пропавшая
                // ошибка выглядит как молчание кнопки.
                Text(state.message, style = WolfyTheme.typography.caption, color = colors.accent)
                Row(Modifier.padding(top = spacing.small), horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    Text("Повторить", style = WolfyTheme.typography.button, color = colors.accent, modifier = Modifier.pressable(onClick = onAsk))
                    // Сервер сообщает остаток не на каждой ошибке: показываем
                    // только когда он действительно приехал.
                    if (state.remaining >= 0) {
                        Text("Осталось сегодня: ${state.remaining}", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                    }
                }
            }
            is BetaPhraseState.Ready -> {
                Text(state.value.title, style = WolfyTheme.typography.body, color = colors.ink)
                Text(state.value.explanation, style = WolfyTheme.typography.caption, color = colors.inkMuted)
                if (state.value.pattern.isNotBlank()) Chip(state.value.pattern)
                state.value.steps.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        Text("${index + 1}", style = WolfyTheme.typography.caption, color = colors.accent)
                        Column { Text(step.label, style = WolfyTheme.typography.caption, color = colors.ink); Text(step.text, style = WolfyTheme.typography.caption, color = colors.inkMuted) }
                    }
                }
                Text("Осталось сегодня: ${state.value.remaining}", style = WolfyTheme.typography.caption, color = colors.inkMuted)
            }
        }
    }
}


@Composable
private fun SentenceTranslation(state: WordCardState) {
    val ready = state.translation as? TranslationState.Ready
    val translated = if (state.phraseText != null) {
        ready?.word.orEmpty().ifBlank { ready?.sentence.orEmpty() }
    } else {
        ready?.sentence.orEmpty()
    }

    when {
        translated.isNotBlank() -> Text(
            "«$translated»",
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.ink,
        )
        state.translation is TranslationState.Loading -> Text(
            "Перевод фразы загружается…",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
        state.translation is TranslationState.Failed -> Text(
            state.translation.message,
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
        else -> Unit
    }
}

// --- Подробности -------------------------------------------------------------

/** Мелкие факты плитками: уровень, редкость, длина. */
@Composable
private fun FactTags(state: WordCardState) {
    val analysis = state.analysis
    val syllables = syllableCount(analysis.lemma)

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
    ) {
        if (analysis.cefr.isNotBlank()) Chip("уровень ${analysis.cefr}")
        if (syllables > 0) Chip(plural(syllables, "слог", "слога", "слогов"))
        if (!analysis.known) Chip("нет в лексиконе")
    }
}

/** Значимые части слова и правило окончания — без псевдоэтимологии. */
@Composable
private fun WordStructure(state: WordCardState) {
    val parts = wordParts(state.analysis.surface, state.analysis.lemma, state.analysis.form)
    if (parts.size < 2) return

    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        SectionLabel("Строение слова")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            parts.forEachIndexed { index, part ->
                Chip(
                    text = part.text,
                    tint = if (part.affix) colors.accent else null,
                )
                if (index != parts.lastIndex) {
                    Text("+", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                }
            }
        }
        parts.lastOrNull()?.explanation?.takeIf(String::isNotBlank)?.let { explanation ->
            Text(
                explanation,
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }
    }
}

/** Соседние слова из текущего предложения — полезнее абстрактного списка. */
@Composable
private fun ContextPhrases(state: WordCardState) {
    val phrases = contextPhrases(state.context, state.token.text)
    if (phrases.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small)) {
        SectionLabel("Словосочетания")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
            verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
        ) {
            phrases.forEach { Chip(it) }
        }
    }
}

/** Другие значения слова без уже показанного главного. */
@Composable
private fun OtherSenses(state: WordCardState) {
    val definition = state.definition as? DefinitionState.Ready ?: return
    val position = contextualPos(state.chunks, state.sentenceTokens, state.token)
        ?: state.analysis.primaryPos
    val rest = otherSenses(definition.entry.senses, primarySense(definition.entry.senses, position))
    if (rest.isEmpty()) return

    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        SectionLabel("Другие значения")
        rest.forEach { sense ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                if (sense.pos.isNotBlank()) PosPill(sense.pos)
                Text(
                    text = sense.definition,
                    style = WolfyTheme.typography.translation,
                    color = colors.ink,
                )
            }
        }
    }
}

/**
 * Насколько часто слово встречается в живой речи.
 *
 * Цвет здесь работает, а не украшает. Редкое слово и частое требуют разного:
 * первое стоит сохранить, второе — узнать в лицо и идти дальше.
 *
 * Полоска заполняется движением от нуля, а не появляется готовой: глаз следит
 * за растущей полосой и успевает заметить, где она остановилась, — а вот
 * готовую он считывает как фон.
 */
@Composable
private fun Frequency(zipf: Float) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    // Шкала Zipf уже логарифмическая и потому линейна для восприятия:
    // 7 — «the», 0 — слово, которого в корпусе нет вовсе.
    val fraction = (zipf / 7f).coerceIn(0f, 1f)

    // Цвет здесь работает, а не украшает: редкое слово и частое требуют
    // разного. Первое стоит сохранить, второе — узнать в лицо и идти дальше.
    val tint = when {
        zipf >= 5f -> colors.partsOfSpeech.adjective
        zipf >= 3f -> colors.gold
        zipf > 0f -> colors.accent
        else -> colors.inkMuted
    }

    val grown = remember { Animatable(0f) }
    val motion = WolfyTheme.motion
    LaunchedEffect(fraction) {
        grown.animateTo(fraction, tween(durationMillis = motion.calm, easing = Curves.Paper))
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Как часто встречается")
            // Полоска показывает «много или мало», но не отвечает «сколько».
            // Zipf-6.2 читателю посреди книги ни о чём: это жаргон корпусной
            // лингвистики. А «примерно раз на тысячу слов» — понятно всем и
            // сразу переводится в опыт: столько слов в двух страницах.
            Text(
                text = frequencyTitle(zipf),
                style = WolfyTheme.typography.caption,
                color = tint,
            )
        }
        Box(Modifier.fillMaxWidth().height(spacing.large)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(spacing.hair)
                    .align(Alignment.Center)
                    .background(colors.rule, CircleShape),
            )
            Box(
                Modifier
                    .fillMaxWidth(grown.value.coerceAtLeast(0.001f))
                    .align(Alignment.CenterStart),
            ) {
                Box(
                    Modifier
                        .size(spacing.medium)
                        .align(Alignment.CenterEnd)
                        .rotate(45f)
                        .background(tint),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("редко", style = WolfyTheme.typography.caption, color = colors.inkMuted)
            Text("часто", style = WolfyTheme.typography.caption, color = colors.inkMuted)
        }
    }
}

private fun frequencyTitle(zipf: Float): String = when {
    zipf >= 6f -> "очень частое"
    zipf >= 5f -> "частое"
    zipf >= 4f -> "обычное"
    zipf > 0f -> "редкое"
    else -> "нет в корпусе"
}

/** Полный разбор формы — когда признаков больше двух. */
@Composable
private fun FullBreakdown(state: WordCardState) {
    if (state.analysis.facts.size <= 2) return

    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
        SectionLabel("Полный разбор формы")
        state.analysis.facts.forEach { fact ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fact.label, style = WolfyTheme.typography.caption, color = colors.inkMuted)
                Text(fact.value, style = WolfyTheme.typography.caption, color = colors.ink)
            }
        }
    }
}

/** Найденные правила списком. Пустой список ничего не рисует. */
@Composable
private fun GrammarList(
    findings: List<Finding>,
    label: String,
    onOpen: (String) -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        SectionLabel(label)
        findings.forEach { finding ->
            GrammarNote(finding, onOpen = { onOpen(finding.rule) })
        }
    }
}

/**
 * Одно грамматическое правило, найденное в предложении.
 *
 * Формула стоит рядом с названием, а не под объяснением, и это не украшение:
 * правило запоминается схемой, а объяснение только помогает её понять.
 */
@Composable
private fun GrammarNote(finding: Finding, onOpen: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                colors.ruleFamilies.forFamily(finding.rule).copy(alpha = 0.42f),
                RoundedCornerShape(spacing.small),
            )
            .pressable(onClick = onOpen)
            .padding(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.tight),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = finding.title, style = typography.body, color = colors.ink)
            Text(
                text = finding.formula,
                style = typography.caption,
                color = colors.ink,
                modifier = Modifier
                    .background(
                        colors.ruleFamilies.forFamily(finding.rule),
                        RoundedCornerShape(spacing.tight),
                    )
                    .padding(horizontal = spacing.tight, vertical = spacing.hair),
            )
        }
        Text(text = finding.explanation, style = typography.caption, color = colors.inkMuted)
        // Правило в карточке объяснено коротко, потому что читатель посреди
        // книги. Захочет разобраться — справочник объяснит то же самое, но с
        // примерами и советом, когда правило уместно.
        Text(
            text = "Подробнее в справочнике",
            style = typography.caption,
            color = colors.accent,
        )
    }
}

@Composable
private fun WolfyLexicalTip(state: WordCardState) {
    val tip = when {
        !state.analysis.known -> "Вульфи: слово редкое или авторское. Сначала проверь контекст, а затем перевод."
        state.analysis.form == "irregular" -> "Вульфи: это неправильная форма. Запоминай её вместе с леммой."
        state.analysis.zipf >= 5f -> "Вульфи: частое слово. Полезнее запомнить его в этой фразе, чем отдельно."
        else -> "Вульфи: слово книжное. Сохрани пример, если оборот хочется использовать самому."
    }
    // Подсказка остаётся короткой и появляется только по желанию в раскрытии.
    WolfyTip(tip)
}

@Composable
private fun WolfyPhraseTip(state: WordCardState) {
    val tip = if (state.grammar.isEmpty()) {
        "Вульфи: здесь важнее порядок и смысл слов, чем отдельное грамматическое правило."
    } else {
        "Вульфи: цвет показывает часть речи, а скобка соединяет слова, которые работают вместе."
    }
    WolfyTip(tip)
}

@Composable
private fun WolfyTip(text: String) {
    var reply by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                WolfyTheme.colors.paper,
                RoundedCornerShape(WolfyTheme.spacing.medium),
            )
            .border(
                WolfyTheme.spacing.rule,
                WolfyTheme.colors.rule,
                RoundedCornerShape(WolfyTheme.spacing.medium),
            )
            .padding(WolfyTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.medium),
    ) {
        // Крупнее подписи рядом: Вульфи единственное живое на карточке, и
        // размером с иконку он читался как значок, а не как зверь, которого
        // можно погладить.
        WolfyCompanion(
            size = 72.dp,
            onPet = {
                reply = listOf("Вууу!", "Ты ж мой сладенький!", "Сохрани фразу целиком — так её легче вспомнить.").random()
            },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
        ) {
            Text(
                text.removePrefix("Вульфи: "),
                style = WolfyTheme.typography.body,
                color = WolfyTheme.colors.ink,
            )
            if (reply.isNotBlank()) Text(reply, style = WolfyTheme.typography.caption, color = WolfyTheme.colors.accent)
        }
    }
}

@Composable
private fun Header(state: WordCardState, onPronounce: () -> Unit, wordModifier: Modifier) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography
    val spacing = WolfyTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HighlightedWord(state, wordModifier)
            CefrBadge(state.analysis.cefr)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pronunciation = (state.definition as? DefinitionState.Ready)
                ?.entry?.pronunciation.orEmpty()
            if (pronunciation.isNotBlank()) {
                Chip("/$pronunciation/")
            }
            Text(
                text = "произнести",
                style = typography.button,
                color = colors.accent,
                modifier = Modifier
                    .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.huge))
                    .pressable(onClick = onPronounce)
                    .padding(horizontal = spacing.small, vertical = spacing.tight),
            )
        }
    }
}

/** Окончание выделено цветом: именно оно объясняет форму слова в тексте. */
@Composable
private fun HighlightedWord(state: WordCardState, modifier: Modifier = Modifier) {
    val surface = state.analysis.surface
    val ending = inflectionEnding(surface, state.analysis.lemma, state.analysis.form)
    val colors = WolfyTheme.colors
    if (ending == null) {
        Text(surface, style = WolfyTheme.typography.screenTitle, color = colors.ink, modifier = modifier)
        return
    }

    Text(
        text = buildAnnotatedString {
            append(surface.substring(0, ending))
            withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) {
                append(surface.substring(ending))
            }
        },
        style = WolfyTheme.typography.screenTitle,
        color = colors.ink,
        modifier = modifier,
    )
}

/**
 * Плитка: короткое свойство без подписи-заголовка.
 *
 * Подпись бывает нужна — «прошедшее время» объясняет себя само, а «read»
 * без слов «третья форма» рядом непонятно, — но тогда она стоит внутри
 * плитки и приглушена, а не задаёт ей ширину.
 */
@Composable
private fun Chip(
    text: String,
    label: String? = null,
    tint: Color? = null,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier
            .background(
                tint?.copy(alpha = TINT) ?: colors.paper,
                RoundedCornerShape(spacing.small),
            )
            .padding(horizontal = spacing.small, vertical = spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(spacing.hair),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(label, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        }
        Text(
            text = text,
            style = WolfyTheme.typography.caption,
            color = tint ?: colors.ink,
        )
    }
}

/** Часть речи цветом и словом сразу. */
@Composable
private fun PosPill(tag: String) {
    // Служебным частям речи своего цвета не досталось намеренно: их пять
    // видов, они встречаются в каждой строке, и раскрашенная страница
    // перестала бы читаться. Серый — это «служебное слово».
    val tint = WolfyTheme.colors.partsOfSpeech.forTag(tag) ?: WolfyTheme.colors.inkMuted
    Chip(text = posTitle(tag), tint = tint)
}

/** Насколько разбавлен цвет части речи под плиткой. */
private const val TINT = 0.14f

// --- Кнопки сохранения -------------------------------------------------------

/** Низ карточки: одна кнопка, соответствующая открытому режиму. */
@Composable
private fun SaveArea(
    mode: CardMode,
    state: WordCardState,
    onSave: () -> Unit,
) {
    when (mode) {
        CardMode.Word -> SaveButton(saved = state.saved, onSave = onSave)
        CardMode.Phrase -> PhraseSaveButton(state = state, onSave = onSave)
    }
}

@Composable
private fun SaveButton(saved: Boolean, onSave: () -> Unit) {
    val colors = WolfyTheme.colors
    val motion = WolfyTheme.motion

    val background by animateColorAsState(
        targetValue = if (saved) colors.surface else colors.inverse,
        animationSpec = tween(motion.quick, easing = Curves.Paper),
        label = "save bg",
    )
    val border by animateColorAsState(
        targetValue = if (saved) colors.rule else colors.inverse,
        animationSpec = tween(motion.quick, easing = Curves.Paper),
        label = "save border",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(WolfyTheme.spacing.huge))
            .border(WolfyTheme.spacing.rule, border, RoundedCornerShape(WolfyTheme.spacing.huge))
            .pressable(onClick = onSave)
            .padding(vertical = WolfyTheme.spacing.medium),
    ) {
        Text(
            text = if (saved) "В колоде книги · убрать" else "В колоду книги",
            style = WolfyTheme.typography.button,
            color = if (saved) colors.inkMuted else colors.onInverse,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Сохранить всё предложение в колоду фраз.
 *
 * Кнопка стоит на месте сразу, а не появляется по готовности перевода:
 * прыгающий интерфейс читается как дрожание. Пока перевода нет, кнопка ждёт
 * и говорит об этом сама — сохранять фразу без русского перевода нельзя,
 * конструктор спрашивает по-русски.
 */
@Composable
private fun PhraseSaveButton(state: WordCardState, onSave: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val ready = (state.translation as? TranslationState.Ready)?.sentence.orEmpty()
    val enabled = ready.isNotBlank() && !state.phraseSaved
    val waiting = !state.phraseSaved && ready.isBlank()

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.tight),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        state.phraseSaved -> colors.surface
                        enabled -> colors.inverse
                        else -> colors.paper
                    },
                    RoundedCornerShape(spacing.huge),
                )
                .border(
                    spacing.rule,
                    if (state.phraseSaved || waiting) colors.rule else colors.inverse,
                    RoundedCornerShape(spacing.huge),
                )
                .pressable(enabled = enabled, onClick = onSave)
                .padding(vertical = spacing.medium),
        ) {
            Text(
                text = if (state.phraseSaved) "Фраза уже в колоде" else "Сохранить фразу целиком",
                style = WolfyTheme.typography.button,
                color = when {
                    state.phraseSaved -> colors.inkMuted
                    enabled -> colors.onInverse
                    else -> colors.inkMuted
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (waiting) {
            Text(
                text = "Фраза сохранится вместе с русским переводом. Он скоро появится.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
                textAlign = TextAlign.Center,
            )
        }
        CopyQuote(sentence = state.context, translation = ready)
    }
}

/**
 * Забрать фразу с собой.
 *
 * Подписью, а не кнопкой: сохранение фразы в колоду — то, ради чего сюда
 * пришли, и второй такой же кнопкой рядом выбор превратился бы в задачу.
 * Копирование стоит ниже и говорит вполголоса.
 *
 * После нажатия подпись сама рассказывает, что случилось, и через пару секунд
 * возвращается. Своего окошка с сообщением здесь нет: на Android система с 13-й
 * версии показывает такое сама, и два уведомления об одном действии читаются
 * как сбой.
 */
@Composable
private fun CopyQuote(sentence: String, translation: String) {
    val quote = quoteOf(sentence, translation)
    if (quote.isEmpty()) return

    val clipboard = rememberClipboard()
    val clipboardLabel = stringResource(Res.string.quote_clipboard_label)
    var feedback by remember(quote) { mutableStateOf(CopyFeedback.Idle) }
    LaunchedEffect(feedback) {
        if (feedback == CopyFeedback.Idle) return@LaunchedEffect
        delay(SAID_ENOUGH)
        feedback = CopyFeedback.Idle
    }

    Text(
        text = when (feedback) {
            CopyFeedback.Idle -> stringResource(Res.string.copy_quote)
            CopyFeedback.Copied -> stringResource(Res.string.quote_copied)
            CopyFeedback.Failed -> stringResource(Res.string.quote_copy_failed)
        },
        style = WolfyTheme.typography.caption,
        color = if (feedback == CopyFeedback.Idle) WolfyTheme.colors.accent else WolfyTheme.colors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.pressable {
            feedback = if (clipboard.put(quote, clipboardLabel)) CopyFeedback.Copied else CopyFeedback.Failed
        },
    )
}

private enum class CopyFeedback { Idle, Copied, Failed }

/** Сколько подпись держит ответ, прежде чем снова стать предложением нажать. */
private const val SAID_ENOUGH = 2_000L

// --- Помощники ---------------------------------------------------------------

private data class WordPart(
    val text: String,
    val affix: Boolean,
    val explanation: String = "",
)

private fun wordParts(surface: String, lemma: String, form: String): List<WordPart> {
    val lower = surface.lowercase()
    val inflection = inflectionEnding(surface, lemma, form)
    if (inflection != null) {
        val suffix = lower.substring(inflection)
        return listOf(
            WordPart(lower.substring(0, inflection), affix = false),
            WordPart("-$suffix", affix = true, explanation = endingExplanation(suffix)),
        )
    }

    val prefixes = listOf("under", "inter", "trans", "over", "anti", "non", "pre", "dis", "mis", "sub", "un", "re")
    val suffixes = listOf("ation", "ition", "ment", "ness", "able", "ible", "less", "ful", "ous", "ive", "ity", "tion", "sion", "ly")
    val prefix = prefixes.firstOrNull { lower.startsWith(it) && lower.length - it.length >= 4 }
    val afterPrefix = prefix?.let { lower.substring(it.length) } ?: lower
    val suffix = suffixes.firstOrNull { afterPrefix.endsWith(it) && afterPrefix.length - it.length >= 3 }
    if (prefix == null && suffix == null) return listOf(WordPart(lower, affix = false))

    val rootEnd = afterPrefix.length - (suffix?.length ?: 0)
    return buildList {
        if (prefix != null) add(WordPart("$prefix-", affix = true))
        add(WordPart(afterPrefix.substring(0, rootEnd), affix = false))
        if (suffix != null) {
            add(WordPart("-$suffix", affix = true, explanation = endingExplanation(suffix)))
        }
    }
}

private fun inflectionEnding(surface: String, lemma: String, form: String): Int? {
    if (form == "irregular" || form == "unknown") return null
    val word = surface.lowercase()
    val base = lemma.lowercase()
    if (word == base) return null

    if (word.startsWith(base) && word.length > base.length) return base.length
    if (base.endsWith("e") && word.startsWith(base.dropLast(1)) &&
        word.substring(base.length - 1) in setOf("ing", "ed")) {
        return base.length - 1
    }
    if (base.endsWith("y")) {
        val stem = base.dropLast(1)
        if (word == stem + "ies" || word == stem + "ied") return stem.length
    }
    return listOf("ing", "ed", "es", "s", "er", "est")
        .firstOrNull { word.endsWith(it) && word.length - it.length >= 3 }
        ?.let { word.length - it.length }
}

private fun endingExplanation(suffix: String): String = when (suffix) {
    "ing" -> "-ing показывает процесс, длительность или герундий; точную роль задаёт фраза."
    "ed", "ied" -> "-ed показывает прошедшее время либо причастие; точную роль задаёт вспомогательный глагол."
    "s", "es", "ies" -> "Окончание показывает множественное число либо третье лицо; часть речи снимает неоднозначность."
    "er" -> "-er часто образует сравнительную степень или название действующего лица."
    "est" -> "-est образует превосходную степень."
    "ly" -> "-ly обычно превращает признак в наречие образа действия."
    "ness", "ity", "tion", "sion", "ation", "ition", "ment" ->
        "Суффикс образует абстрактное существительное."
    "able", "ible", "ous", "ive", "less", "ful" ->
        "Суффикс образует прилагательное и подсказывает значение признака."
    else -> "Окончание меняет форму или часть речи слова."
}

private fun contextPhrases(context: String, selected: String): List<String> {
    val words = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
        .findAll(context)
        .map { it.value }
        .toList()
    val index = words.indexOfFirst { it.equals(selected, ignoreCase = true) }
    if (index < 0) return emptyList()

    return buildList {
        if (index > 0) add(words[index - 1] + " " + words[index])
        if (index + 1 < words.size) add(words[index] + " " + words[index + 1])
        if (index > 0 && index + 1 < words.size) {
            add(words.subList(index - 1, index + 2).joinToString(" "))
        }
    }.distinct()
}

/**
 * «12 книг», «1 книга», «3 книги».
 *
 * Без согласования числительного счётчик читается как опечатка.
 */
private fun plural(count: Int, one: String, few: String, many: String): String {
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

private fun formTitle(form: String): String = when (form) {
    "lemma" -> "словарная"
    "regular" -> "регулярная форма"
    "irregular" -> "неправильная форма"
    else -> "неизвестная"
}

/** Достаточная для учебной подсказки оценка английских слогов. */
private fun syllableCount(word: String): Int {
    val normalized = word.lowercase().filter { it in 'a'..'z' }
    if (normalized.isEmpty()) return 0
    val vowels = "aeiouy"
    var count = 0
    var previousWasVowel = false
    normalized.forEach { char ->
        val vowel = char in vowels
        if (vowel && !previousWasVowel) count += 1
        previousWasVowel = vowel
    }
    if (normalized.endsWith('e') && count > 1 && !normalized.endsWith("le")) count -= 1
    return count.coerceAtLeast(1)
}

/**
 * Насколько надо потянуть карточку вниз, чтобы она закрылась.
 *
 * Шестьдесят точек — это заметное движение, но не размах. Меньше — и карточка
 * будет улетать от случайного касания ручки при прокрутке; больше — и жест
 * перестанет ощущаться как жест.
 */
private val SWIPE_TO_CLOSE = 60.dp
