package com.wolfy.ui.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wolfy.ffi.Finding
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.CefrBadge
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.SentenceGraph
import com.wolfy.widgets.WolfyCompanion
import com.wolfy.widgets.pressable
import kotlin.math.pow
import kotlinx.coroutines.delay

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
    onSavePhrase: () -> Unit,
    onPronounce: () -> Unit,
    onOpenRule: (String) -> Unit,
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
                CardBody(
                    state = state,
                    onSave = onSave,
                    onSavePhrase = onSavePhrase,
                    onPronounce = onPronounce,
                    onDismiss = onDismiss,
                    onOpenRule = onOpenRule,
                    maxHeight = maxCardHeight,
                )
            }
        }
    }
}

@Composable
private fun CardBody(
    state: WordCardState,
    onSave: () -> Unit,
    onSavePhrase: () -> Unit,
    onPronounce: () -> Unit,
    onDismiss: () -> Unit,
    onOpenRule: (String) -> Unit,
    maxHeight: Dp,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography
    var mode by remember(state.token.start, state.context) { mutableStateOf(CardMode.Word) }

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
        // Ручка для перетаскивания.
        //
        // Тянуть можно только за неё, а не за карточку целиком: внутри
        // карточки свой прокручиваемый столбец, и жест «вниз» там означает
        // «читать дальше», а не «закрыть». Ручка нарочно шире и выше, чем
        // видимая полоска: три точки в высоту пальцем не поймать.
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
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

        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Header(state, onPronounce)
            Translation(state)
            Definition(state)
            CardModeTabs(mode = mode, onMode = { mode = it })
            AnimatedContent(targetState = mode, label = "card mode") { selected ->
                when (selected) {
                    CardMode.Word -> WordDetails(
                        state = state,
                        onSave = onSave,
                        onOpenRule = onOpenRule,
                    )
                    CardMode.Phrase -> PhraseDetails(
                        state = state,
                        onSave = onSavePhrase,
                        onOpenRule = onOpenRule,
                    )
                }
            }
        }
    }
}

private enum class CardMode { Word, Phrase }

@Composable
private fun CardModeTabs(mode: CardMode, onMode: (CardMode) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(spacing.huge))
            .padding(spacing.tight),
    ) {
        listOf(CardMode.Word to "Слово", CardMode.Phrase to "Фраза").forEach { (item, title) ->
            Text(
                text = title,
                style = WolfyTheme.typography.button,
                color = if (mode == item) colors.onInverse else colors.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (mode == item) colors.inverse else colors.paper,
                        RoundedCornerShape(spacing.huge),
                    )
                    .pressable(onClick = { onMode(item) })
                    .padding(vertical = spacing.small),
            )
        }
    }
}

@Composable
private fun WordDetails(
    state: WordCardState,
    onSave: () -> Unit,
    onOpenRule: (String) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        Appear(0) { Facts(state) }
        Appear(1) { WordStructure(state) }
        Appear(2) { ContextPhrases(state) }
        Appear(3) { Frequency(state.analysis.zipf) }
        // Грамматика фразы стоит и здесь, а не только на вкладке «Фраза».
        // Читатель тапнул по слову внутри предложения, и правило, которое это
        // предложение строит, — часть ответа на вопрос «почему слово выглядит
        // так». Прятать его за вкладку значит требовать догадаться, что там
        // что-то есть.
        if (state.grammar.isNotEmpty()) {
            Appear(4) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    SectionLabel("Грамматика этой фразы")
                    state.grammar.forEach { finding ->
                        GrammarNote(finding, onOpen = { onOpenRule(finding.rule) })
                    }
                }
            }
        }
        Appear(5) { WolfyLexicalTip(state) }
        Appear(6) { SaveButton(saved = state.saved, onSave = onSave) }
    }
}

/**
 * Свойства слова плитками, а не списком.
 *
 * Раньше здесь стояло шесть строк «подпись — значение» одинакового вида:
 * лемма, части речи, форма, уровень, длина, распространённость. Одинаковый
 * вид и означал, что всё одинаково важно, — а это неправда. Уровень уже
 * написан в углу карточки, лемма — в шапке, а «7 букв» и «неправильная
 * форма» это вещи совершенно разного веса.
 *
 * Плитка снимает подпись вовсе: «2 слога» не нуждается в слове «длина»
 * перед собой. А что осталось без значения, то не показывается: строка
 * «Форма: начальная» сообщает ровно ничего.
 */
@Composable
private fun Facts(state: WordCardState) {
    val spacing = WolfyTheme.spacing
    val analysis = state.analysis
    val primary = analysis.primaryPos

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        // Вторая часть речи — не мелочь: слово, которое бывает и глаголом, и
        // существительным, читается неверно чаще всего именно поэтому.
        analysis.pos.filter { it != primary }.forEach { PosPill(it) }

        if (analysis.form.isNotBlank() && analysis.form != "base") {
            Chip(formTitle(analysis.form))
        }
        val syllables = syllableCount(analysis.lemma)
        if (syllables > 0) Chip(plural(syllables, "слог", "слога", "слогов"))

        analysis.facts.forEach { fact -> Chip(fact.value, label = fact.label) }
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
        Text(
            "Сочетания взяты из текущего предложения, поэтому сохраняют авторский контекст.",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
    }
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

/**
 * Появление с задержкой.
 *
 * Карточка выезжала собранной целиком, и взгляду некуда было сесть первым:
 * слово, перевод и шесть строк разбора приезжали одновременно и весили
 * одинаково. Здесь они приходят по очереди — сначала главное, — и порядок
 * чтения задаётся движением, а не размером шрифта.
 *
 * Шаг маленький: сорок миллисекунд между блоками читаются как «оживает», а
 * не как «подтормаживает». Всё вместе укладывается в треть секунды.
 */
@Composable
private fun Appear(order: Int, content: @Composable () -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * STEP)
        shown.animateTo(1f, tween(durationMillis = 260))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = shown.value
            // Подъём снизу, а не падение сверху: содержимое приходит из-под
            // края карточки, как продолжение её собственного выезда.
            translationY = (1f - shown.value) * RISE.toPx()
        },
    ) {
        content()
    }
}

/** Шаг между блоками при появлении. */
private const val STEP = 40L

/** Насколько блок приподнят перед тем, как встать на место. */
private val RISE = 10.dp

/** Насколько разбавлен цвет части речи под плиткой. */
private const val TINT = 0.14f

/** «2 слога», «5 слогов» — иначе выходит «5 слог». */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val tens = count % 100
    val ones = count % 10
    val word = when {
        tens in 11..14 -> many
        ones == 1 -> one
        ones in 2..4 -> few
        else -> many
    }
    return "$count $word"
}

@Composable
private fun PhraseDetails(
    state: WordCardState,
    onSave: () -> Unit,
    onOpenRule: (String) -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        SectionLabel("Разбор фразы")
        Text(
            state.context,
            style = WolfyTheme.typography.translation,
            color = WolfyTheme.colors.ink,
        )
        val translated = (state.translation as? TranslationState.Ready)?.sentence.orEmpty()
        if (translated.isNotBlank()) {
            Text(
                "«$translated»",
                style = WolfyTheme.typography.body,
                color = WolfyTheme.colors.inkMuted,
            )
        }
        SectionLabel("Связи слов в предложении")
        if (state.graphLinks.isEmpty()) {
            Text(
                "В этой фразе нет однозначных связей, которые можно показать без догадки.",
                style = WolfyTheme.typography.caption,
                color = WolfyTheme.colors.inkMuted,
            )
        } else {
            SentenceGraph(words = state.graphWords, links = state.graphLinks)
        }

        if (state.grammar.isNotEmpty()) {
            SectionLabel("Грамматика фразы")
            state.grammar.forEach { finding ->
                GrammarNote(finding, onOpen = { onOpenRule(finding.rule) })
            }
        }
        WolfyPhraseTip(state)
        PhraseButton(state = state, onSave = onSave)
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
    WolfyTip(tip)
}

@Composable
private fun WolfyPhraseTip(state: WordCardState) {
    val tip = if (state.grammar.isEmpty()) {
        "Вульфи: здесь важнее порядок и смысл слов, чем отдельное грамматическое правило."
    } else {
        "Вульфи: цвет показывает часть речи, а скобка — слова, которые работают вместе."
    }
    WolfyTip(tip)
}

@Composable
private fun WolfyTip(text: String) {
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
        WolfyCompanion(size = 152.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
        ) {
            SectionLabel("Вульфи замечает")
            Text(
                text.removePrefix("Вульфи: "),
                style = WolfyTheme.typography.body,
                color = WolfyTheme.colors.ink,
            )
        }
    }
}

@Composable
private fun Header(state: WordCardState, onPronounce: () -> Unit) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography
    val spacing = WolfyTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HighlightedWord(state)
            CefrBadge(state.analysis.cefr)
        }
        val tag = state.analysis.primaryPos
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            // Цвет части речи — тот же, которым слово покрашено на странице.
            if (tag != null) PosPill(tag) else Chip("нет в лексиконе")
            if (state.analysis.lemma != state.analysis.surface.lowercase()) {
                Chip("от «" + state.analysis.lemma + "»")
            }
            val pronunciation = (state.definition as? DefinitionState.Ready)
                ?.entry?.pronunciation.orEmpty()
            if (pronunciation.isNotBlank()) Chip("/$pronunciation/")
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
private fun HighlightedWord(state: WordCardState) {
    val surface = state.analysis.surface
    val ending = inflectionEnding(surface, state.analysis.lemma, state.analysis.form)
    val colors = WolfyTheme.colors
    if (ending == null) {
        Text(surface, style = WolfyTheme.typography.screenTitle, color = colors.ink)
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

        // Перевод — единственное, чего читатель ждёт, и приходит он позже
        // всего остального. Проявление отмечает этот приход: без него строка
        // просто возникает, и половину раз читатель не замечает, что она
        // сменила «Перевод загружается…».
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
                            .background(colors.paper, RoundedCornerShape(WolfyTheme.spacing.small))
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

/** Английское толкование: в отличие от перевода оно объясняет само слово. */
@Composable
private fun Definition(state: WordCardState) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography
    val spacing = WolfyTheme.spacing

    when (val definition = state.definition) {
        DefinitionState.Loading -> Text(
            text = "Толкование загружается…",
            style = typography.caption,
            color = colors.inkMuted,
        )
        is DefinitionState.Ready -> Column(
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            SectionLabel("Толкование")
            definition.entry.senses.take(4).forEach { sense ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    if (sense.pos.isNotBlank()) PosPill(sense.pos)
                    Text(
                        text = sense.definition,
                        style = typography.translation,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        DefinitionState.Idle, DefinitionState.Missing -> Unit
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
private fun GrammarNote(finding: Finding, onOpen: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(spacing.small))
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
            Text(text = finding.formula, style = typography.caption, color = colors.accent)
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

/**
 * Насколько часто слово встречается в живой речи.
 *
 * Цвет здесь работает, а не украшает. Редкое слово и частое требуют разного:
 * первое стоит сохранить, второе — узнать в лицо и идти дальше. Полоска
 * одного цвета этого не говорила, и читателю приходилось считать проценты
 * глазами.
 *
 * Заполняется движением от нуля, а не появляется готовой: глаз следит за
 * растущей полосой и успевает заметить, где она остановилась, — а вот
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

    // Полоска заполняется от нуля, а не появляется готовой: глаз следит за
    // растущей полосой и успевает заметить, где она остановилась, а готовую
    // считывает как фон.
    val grown = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        grown.animateTo(fraction, tween(durationMillis = 520))
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
                text = frequencyTitle(zipf) + " · " + frequencyRate(zipf),
                style = WolfyTheme.typography.caption,
                color = tint,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(spacing.small)
                .background(colors.paper, CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(grown.value)
                    .height(spacing.small)
                    .background(tint, CircleShape),
            )
        }
    }
}

/**
 * Сохранить всё предложение в колоду фраз.
 *
 * Появляется только когда перевод предложения уже пришёл: конструктор фраз
 * спрашивает по-русски, и фраза без русской строки была бы карточкой, которую
 * невозможно показать.
 *
 * Строкой, а не второй чёрной кнопкой: главное действие карточки одно — «в
 * колоду книги», — и две одинаковые кнопки подряд заставляли бы выбирать там,
 * где выбирать не нужно.
 */
@Composable
private fun PhraseButton(state: WordCardState, onSave: () -> Unit) {
    val colors = WolfyTheme.colors
    val ready = (state.translation as? TranslationState.Ready)?.sentence.orEmpty()
    if (ready.isBlank()) return

    Text(
        text = if (state.phraseSaved) "Фраза уже в колоде" else "Сохранить фразу целиком",
        style = WolfyTheme.typography.caption,
        color = if (state.phraseSaved) colors.inkMuted else colors.accent,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(enabled = !state.phraseSaved, onClick = onSave),
    )
}

@Composable
private fun SaveButton(saved: Boolean, onSave: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (saved) colors.surface else colors.inverse,
                RoundedCornerShape(spacing.huge),
            )
            .border(
                spacing.rule,
                if (saved) colors.rule else colors.inverse,
                RoundedCornerShape(spacing.huge),
            )
            .pressable(onClick = onSave)
            .padding(vertical = spacing.medium),
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

private fun frequencyTitle(zipf: Float): String = when {
    zipf >= 6f -> "очень частое"
    zipf >= 5f -> "частое"
    zipf >= 4f -> "обычное"
    zipf > 0f -> "редкое"
    else -> "нет в корпусе"
}

/**
 * Частотность в словах, которые можно себе представить.
 *
 * Шкала Zipf логарифмическая: Zipf 6 — тысяча слов на миллион, Zipf 3 — одно.
 * Отсюда и «раз на столько-то»: делим миллион на частоту и округляем до
 * круглого — читателю нужен порядок, а не точность до единицы.
 */
private fun frequencyRate(zipf: Float): String {
    if (zipf <= 0f) return "в корпусе не встретилось"
    val perMillion = 10.0.pow((zipf - 3f).toDouble())
    val everyN = (1_000_000 / perMillion).toLong()
    val round = when {
        everyN < 10 -> everyN
        everyN < 1_000 -> everyN / 10 * 10
        everyN < 100_000 -> everyN / 1_000 * 1_000
        else -> everyN / 100_000 * 100_000
    }.coerceAtLeast(1)
    return "примерно раз на " + spaced(round) + " слов"
}

/** Разряды пробелом: «100 000» читается, «100000» — нет. */
private fun spaced(value: Long): String =
    value.toString().reversed().chunked(3).joinToString("\u00A0").reversed()

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
