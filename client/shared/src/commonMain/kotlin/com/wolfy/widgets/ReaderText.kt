package com.wolfy.widgets

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Text
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Token
import com.wolfy.theme.WolfyTheme

/**
 * Абзац книги, в котором можно тапнуть по слову и выделить фразу.
 *
 * Разметка приходит из ядра: [ParsedText] несёт токены с позициями в тех же
 * единицах, которыми меряет строки Kotlin. Поэтому подсветка и попадание в
 * слово считаются напрямую по смещениям, без повторного разбора текста на
 * стороне интерфейса — иначе два разных разбора однажды разошлись бы, и
 * читатель нажимал бы на одно слово, а получал соседнее.
 *
 * @param saved начальные формы слов, уже сохранённых в колоду: они помечаются
 *   маркером прямо в тексте, чтобы читатель видел свой словарь на странице.
 * @param selected слово, по которому только что нажали — под ним держится фон,
 *   пока открыта карточка.
 * @param selection выделенный кусок абзаца в тех же смещениях, что и токены.
 * @param offsetShift добавка к локальным смещениям раскладки. Буквица режет
 *   абзац на части, у каждой своя раскладка со смещениями от нуля, а токены
 *   остались в координатах полного абзаца; без этой добавки тап во второй
 *   половине попадал бы мимо слова.
 * @param selectViaMouse жест настраивается мышью (двойной клик + протягивание),
 *   а не долгим нажатием. Desktop — true, Android — false.
 * @param onPhrase зовётся, пока палец ведут по строке: по нему держится
 *   подсветка выделения.
 * @param onPhraseDone зовётся, когда палец подняли, — здесь открывается
 *   карточка фразы.
 */
@Composable
fun ReaderParagraph(
    parsed: ParsedText,
    modifier: Modifier = Modifier,
    style: TextStyle = WolfyTheme.typography.reader,
    saved: Set<String> = emptySet(),
    savedLemmaOf: (Token) -> String = { it.text.lowercase() },
    selected: Token? = null,
    selection: IntRange? = null,
    offsetShift: Int = 0,
    selectViaMouse: Boolean = false,
    /**
     * Докуда набирать каждое слово полужирным — по числу на токен абзаца.
     *
     * Пустой список означает «не выделять»: проверять настройку здесь незачем,
     * её уже проверил тот, кто решал, считать якоря или нет.
     */
    anchors: List<Int> = emptyList(),
    /**
     * Притушить абзац целиком: читатель сейчас не здесь.
     *
     * Притушивается цвет чернил, а не прозрачность всего элемента: элемент с
     * прозрачностью Compose выносит в отдельный слой, и на главе в сотню
     * абзацев это сотня слоёв на каждый кадр прокрутки.
     */
    dimmed: Boolean = false,
    /**
     * Единственный светлый кусок абзаца — в смещениях этого абзаца.
     *
     * Всё вне его притушивается. `null` — светлый весь абзац.
     */
    bright: IntRange? = null,
    onWordTap: (Token) -> Unit = {},
    onPhrase: (IntRange) -> Unit = {},
    onPhraseDone: (IntRange) -> Unit = {},
) {
    val colors = WolfyTheme.colors
    // Раскладка нужна, чтобы перевести точку касания в смещение внутри
    // строки. Пока абзац не отрисован, её нет — и тапы просто игнорируются.
    var layout by remember(parsed) { mutableStateOf<TextLayoutResult?>(null) }

    // Притушенные чернила: между бумагой и текстом, а не серый цвет из
    // палитры — иначе на тёмной теме «притушено» оказалось бы светлее
    // обычного текста.
    val dim = colors.ink.copy(alpha = 0.3f)

    val text: AnnotatedString =
        remember(parsed, saved, selected, selection, anchors, dimmed, bright, colors) {
        buildAnnotatedString {
            parsed.tokens.forEachIndexed { index, token ->
                val start = length
                append(token.text)

                /*
                 * Полужирная основа.
                 *
                 * Ставится раньше всех прочих стилей и отдельным диапазоном:
                 * это насыщенность, а не фон, и с подсветкой сохранённого
                 * слова она не спорит — они складываются.
                 *
                 * Насыщенность умеренная (`W600`, а не `Bold`): на странице,
                 * где выделено каждое слово, разница в двести единиц
                 * превращает текст в сплошную черноту, и якорь перестает быть
                 * якорем.
                 */
                val anchor = anchors.getOrElse(index) { 0 }
                if (anchor in 1 until token.text.length) {
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.W600),
                        start,
                        start + anchor,
                    )
                }

                if (!token.tappable) return@forEachIndexed

                val isSelected = selected != null &&
                    token.start == selected.start && token.end == selected.end
                val isSaved = savedLemmaOf(token) in saved
                val inPhrase = selection != null &&
                    token.start >= selection.first && token.end <= selection.last + 1

                when {
                    // Выделение фразы поверх всего: пока палец ведут по
                    // строке, читатель должен видеть ровно то, что он взял.
                    inPhrase -> addStyle(
                        SpanStyle(background = colors.accent.copy(alpha = 0.18f)),
                        start,
                        length,
                    )
                    // Выбранное слово важнее сохранённого: читатель только что
                    // на него нажал и должен видеть, куда именно попал.
                    isSelected -> addStyle(
                        SpanStyle(background = colors.accent.copy(alpha = 0.22f)),
                        start,
                        length,
                    )
                    isSaved -> addStyle(
                        SpanStyle(background = colors.highlight),
                        start,
                        length,
                    )
                }
            }

            /*
             * Окно чтения. Ставится последним и поверх всего: оно говорит не
             * «это слово такое», а «сюда сейчас не смотрим», и должно
             * перебивать и подсветку сохранённого слова, и полужирную основу.
             */
            when {
                dimmed -> addStyle(SpanStyle(color = dim), 0, length)
                bright != null -> {
                    val from = bright.first.coerceIn(0, length)
                    val to = (bright.last + 1).coerceIn(from, length)
                    if (from > 0) addStyle(SpanStyle(color = dim), 0, from)
                    if (to < length) addStyle(SpanStyle(color = dim), to, length)
                }
            }
        }
    }

    Text(
        text = text,
        style = style.copy(
            color = colors.ink,
            // Выключка по ширине — то, из-за чего страница читается как
            // газетная полоса, а не как лента сообщений.
            textAlign = TextAlign.Justify,
        ),
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(parsed, selectViaMouse, offsetShift) {
                // Ссылки на локальные переменные (::layout) Kotlin пока не
                // берёт — раскладку отдаём лямбдой. Жестовые автоматы живут в
                // отдельной области ожидания событий указателя.
                awaitPointerEventScope {
                    val layoutProvider: () -> TextLayoutResult? = { layout }
                    if (selectViaMouse) {
                        mouseGestures(parsed, offsetShift, layoutProvider, onWordTap, onPhrase, onPhraseDone)
                    } else {
                        touchGestures(parsed, offsetShift, layoutProvider, viewConfiguration.longPressTimeoutMillis, onWordTap, onPhrase, onPhraseDone)
                    }
                }
            },
    )
}

/**
 * Жесты пальцем: долгое нажатие включает режим выделения, протягивание ведёт
 * диапазон по словам, поднятие пальца открывает карточку фразы.
 *
 * До активации жест целиком остаётся прокрутке страницы: вертикальное
 * движение после долгого нажатия тоже относится к выделению, а до него —
 * обычный скролл. Долгое нажатие без движения выбирает предложение вокруг
 * пальца — оно полезнее одиночного слова, которое и так открывается тапом.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.touchGestures(
    parsed: ParsedText,
    shift: Int,
    layout: () -> TextLayoutResult?,
    longPressMillis: Long,
    onWordTap: (Token) -> Unit,
    onPhrase: (IntRange) -> Unit,
    onPhraseDone: (IntRange) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    while (true) {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startTime = down.uptimeMillis

        // Ждём конца долгого нажатия: любое движение сильнее slop отменяет
        // кандидатуру — это прокрутка, и её нельзя блокировать.
        var moved = false
        var held = false
        var lifted = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) {
                lifted = true
                break
            }
            if (change.uptimeMillis - startTime >= longPressMillis) {
                held = true
                break
            }
            if (change.positionChange().getDistance() > slop) {
                moved = true
                break
            }
        }

        val result = layout() ?: continue
        val anchor = result.getOffsetForPosition(down.position) + shift

        if (lifted && !held && !moved) {
            // Обычный тап — карточка слова, как раньше.
            val token = parsed.tokenAt(anchor)
            if (token?.tappable == true) onWordTap(token)
            continue
        }
        if (!held || moved) continue

        // Долгое нажатие состоялось: сразу подсвечиваем предложение вокруг
        // пальца и дальше ведём жест только как выделение.
        var range = parsed.spanAroundSentence(anchor)
        onPhrase(range)

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            change.consume()
            range = parsed.spanBetween(anchor, result.getOffsetForPosition(change.position) + shift)
            onPhrase(range)
        }
        onPhraseDone(range)
    }
}

/**
 * Жесты мышью: одиночный клик открывает карточку слова, второй клик в пределах
 * интервала двойного клика начинает выделение, которое ведётся зажатой кнопкой
 * и фиксируется отпусканием.
 *
 * Время первого клика живёт между жестами, иначе второй клик не с чем сравнить.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.mouseGestures(
    parsed: ParsedText,
    shift: Int,
    layout: () -> TextLayoutResult?,
    onWordTap: (Token) -> Unit,
    onPhrase: (IntRange) -> Unit,
    onPhraseDone: (IntRange) -> Unit,
) {
    val doubleWindowMs = 400L
    var lastClickTime = 0L
    var lastClickPos = Offset.Zero
    val radiusPx = 12f

    while (true) {
        val down = awaitFirstDown(requireUnconsumed = false)
        val isSecondClick = down.uptimeMillis - lastClickTime < doubleWindowMs &&
            (down.position - lastClickPos).getDistance() <= radiusPx

        if (!isSecondClick) {
            // Первый клик: запоминаем момент и место, ждём отпускания.
            lastClickTime = down.uptimeMillis
            lastClickPos = down.position
            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (change.positionChange().getDistance() > 8f) dragged = true
                if (!change.pressed) break
            }
            if (!dragged) {
                layout()?.let { result ->
                    val token = parsed.tokenAt(result.getOffsetForPosition(down.position) + shift)
                    if (token?.tappable == true) onWordTap(token)
                }
            }
            continue
        }

        // Второй клик: включаем выделение.
        lastClickTime = 0L
        val result = layout() ?: continue
        val anchor = result.getOffsetForPosition(down.position) + shift
        var range = parsed.spanBetween(anchor, anchor)
        onPhrase(range)

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            change.consume()
            range = parsed.spanBetween(anchor, result.getOffsetForPosition(change.position) + shift)
            onPhrase(range)
        }
        onPhraseDone(range)
    }
}

/** Кусок текста между двумя точками касания, растянутый до границ слов. */
private fun ParsedText.spanBetween(from: Int, to: Int): IntRange {
    val left = minOf(from, to)
    val right = maxOf(from, to)
    val start = tokenAt(left)?.start ?: left
    val end = tokenAt(right)?.end ?: right
    return start until maxOf(end, start + 1)
}

/**
 * Предложение вокруг точки касания, прижатое к границам разбираемых токенов.
 *
 * Предложение — самый честный ответ на «долгое нажатие без движения»: слово
 * под пальцем уже открывается простым тапом, а фраза целиком чаще всего и есть
 * то, что читатель хотел спросить.
 */
private fun ParsedText.spanAroundSentence(offset: Int): IntRange {
    val anchorToken = tokenAt(offset)
        ?: tokens.firstOrNull { it.tappable }
        ?: return offset until offset + 1
    val sentence = sentenceAt(anchorToken.start)
        ?: return anchorToken.start until maxOf(anchorToken.end, anchorToken.start + 1)
    return sentence.start until maxOf(sentence.end, sentence.start + 1)
}

/** Заголовок главы с линейками сверху и снизу, как в печатной полосе. */
@Composable
fun ChapterHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = WolfyTheme.typography.chapterTitle,
        color = WolfyTheme.colors.ink,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Цитата или эпиграф — своя врезка со сдвигом и курсивом. */
@Composable
fun ReaderQuote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = WolfyTheme.typography.translation.copy(color = WolfyTheme.colors.inkMuted),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Подстрочник над английской фразой.
 *
 * Мелкий перевод ставится ровно над своим словом — так его читают глазами, не
 * отрываясь от строки. Соответствие держится на том, что оба ряда собираются
 * из одного списка токенов.
 */
@Composable
fun InterlinearWord(
    original: String,
    translation: String?,
    partOfSpeechColor: androidx.compose.ui.graphics.Color?,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    Text(
        text = buildAnnotatedString {
            if (translation != null) {
                withStyleSafely(SpanStyle(color = colors.inkMuted)) { append(translation) }
                append("\n")
            }
            withStyleSafely(
                SpanStyle(
                    color = partOfSpeechColor ?: colors.ink,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (partOfSpeechColor != null) {
                        TextDecoration.Underline
                    } else {
                        TextDecoration.None
                    },
                ),
            ) { append(original) }
        },
        style = WolfyTheme.typography.caption,
        modifier = modifier,
    )
}

/**
 * `withStyle` из Compose принимает лямбду и возвращает её результат; здесь он
 * не нужен, а имя без результата читается яснее.
 */
private inline fun AnnotatedString.Builder.withStyleSafely(
    style: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val index = pushStyle(style)
    block()
    pop(index)
}
