package com.wolfy.widgets

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
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
import com.wolfy.theme.HighlightInk
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Краски отметок читателя в смещениях этого абзаца.
     *
     * Список, а не одна пара: в абзаце спокойно живут три выделения разными
     * красками, и рисовать их по очереди было бы тремя проходами по тексту.
     */
    marks: List<TextMark> = emptyList(),
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
        remember(parsed, saved, selected, selection, marks, anchors, dimmed, bright, colors) {
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
                val painted = marks.firstOrNull { mark ->
                    token.start >= mark.range.first && token.end <= mark.range.last + 1
                }

                when {
                    // Выделение фразы поверх всего: пока палец ведут по
                    // строке, читатель должен видеть ровно то, что он взял.
                    inPhrase -> addStyle(
                        SpanStyle(background = colors.accent.copy(alpha = 0.18f)),
                        start,
                        length,
                    )
                    /*
                     * Краска читателя важнее и сохранённого слова, и разбора:
                     * её поставили нарочно, а подсветку словаря приложение
                     * рисует само. Чернила задаются вместе с фоном - заливки
                     * светлые во всех темах, и на ночной теме светлый текст по
                     * светлой краске пропал бы ровно там, где его пометили.
                     */
                    painted != null -> addStyle(
                        SpanStyle(background = painted.color, color = HighlightInk),
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

    // Ручки лежат поверх текста, поэтому текст живёт в коробке. Коробка
    // обтягивает абзац и ничего не занимает сверх него.
    Box(modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = style.copy(
                color = colors.ink,
                // Выключка по ширине — то, из-за чего страница читается как
                // газетная полоса, а не как лента сообщений.
                textAlign = TextAlign.Justify,
            ),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(parsed, selectViaMouse, offsetShift) {
                    // Ссылки на локальные переменные (::layout) Kotlin пока не
                    // берёт — раскладку отдаём лямбдой. Жестовые автоматы живут
                    // в отдельной области ожидания событий указателя.
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
        val settled = layout
        if (selection != null && !selectViaMouse && settled != null) {
            SelectionHandles(
                parsed = parsed,
                selection = selection,
                shift = offsetShift,
                layout = settled,
                onPhrase = onPhrase,
                onPhraseDone = onPhraseDone,
            )
        }
    }
}

/**
 * Две ручки на концах выделения.
 *
 * Выделение фразы пальцем было односторонним: долгое нажатие брало предложение
 * целиком, протягивание вело границу, поднятие пальца всё закрепляло.
 * Промахнулся на слово - начинай жест заново, потому что поправить готовое
 * выделение было нечем. А промахивается на телефоне каждый: палец закрывает
 * ровно то место, куда целятся.
 *
 * Ручки убирают повтор жеста и не добавляют ни одного нажатия удачному случаю:
 * кто выделил верно с первого раза, их просто не трогает.
 *
 * Сидят они под строкой, а не на ней: ручка поверх буквы закрывала бы ту самую
 * границу, ради которой её тянут.
 *
 * Мышью не показываются - там граница ведётся точным курсором, и попадать в
 * слово со второго раза не приходится.
 */
@Composable
private fun BoxScope.SelectionHandles(
    parsed: ParsedText,
    selection: IntRange,
    shift: Int,
    layout: TextLayoutResult,
    onPhrase: (IntRange) -> Unit,
    onPhraseDone: (IntRange) -> Unit,
) {
    val colors = WolfyTheme.colors
    val length = layout.layoutInput.text.length
    if (length == 0) return

    // Живые снимки для жеста.
    //
    // Ключами `pointerInput` они быть не могут, и это не мелочь: ведение ручки
    // само меняет выделение, смена ключа отменяет корутину жеста, и обработчик
    // умирал бы сразу после первого движения пальца. Ручка тянулась бы на один
    // кадр и залипала. Ключи здесь только то, что за время жеста не меняется.
    val liveSelection = rememberUpdatedState(selection)
    val liveLayout = rememberUpdatedState(layout)

    val drawnStart = handleCenter(layout, (selection.first - shift).coerceIn(0, length - 1), leading = true)
    val drawnEnd = handleCenter(layout, (selection.last - shift).coerceIn(0, length - 1), leading = false)

    Canvas(
        Modifier
            .matchParentSize()
            .pointerInput(parsed, shift) {
                val grab = HANDLE_GRAB.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val result = liveLayout.value
                        val anchor = liveSelection.value
                        val size = result.layoutInput.text.length
                        if (size == 0) continue
                        val from = (anchor.first - shift).coerceIn(0, size - 1)
                        val to = (anchor.last - shift).coerceIn(from, size - 1)

                        // Ловится касание в широком круге, а не по самому
                        // кружку: кружок в шесть точек пальцем не поймать.
                        val toStart = (down.position - handleCenter(result, from, leading = true)).getDistance()
                        val toEnd = (down.position - handleCenter(result, to, leading = false)).getDistance()
                        if (minOf(toStart, toEnd) > grab) continue
                        val movingStart = toStart <= toEnd
                        down.consume()

                        // Неподвижный конец запоминается один раз, на момент
                        // захвата: считывать его из живого выделения значило бы
                        // тянуть обе границы разом, ведь выделение меняется тем
                        // же жестом.
                        val fixed = if (movingStart) anchor.last else anchor.first
                        var range = anchor
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val at = result.getOffsetForPosition(change.position) + shift
                            // Какая из двух границ левее, разберётся spanBetween.
                            range = parsed.spanBetween(at, fixed)
                            onPhrase(range)
                        }
                        onPhraseDone(range)
                    }
                }
            },
    ) {
        val radius = HANDLE_RADIUS.toPx()
        drawCircle(colors.accent, radius = radius, center = drawnStart)
        drawCircle(colors.accent, radius = radius, center = drawnEnd)
    }
}

/** Точка под границей выделения, где сидит ручка. */
private fun handleCenter(layout: TextLayoutResult, offset: Int, leading: Boolean): Offset {
    val box = layout.getBoundingBox(offset)
    return Offset(if (leading) box.left else box.right, box.bottom)
}

/** Кружок ручки: заметный, но не закрывающий строку. */
private val HANDLE_RADIUS = 6.dp

/** Радиус, в котором касание считается попаданием по ручке. */
private val HANDLE_GRAB = 28.dp

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
        // Ожидание ограничено настоящим таймером. `awaitPointerEvent` само не
        // присылает кадров, пока палец неподвижен, поэтому проверка времени
        // только внутри цикла превращала спокойный long press в обычный тап.
        var moved = false
        var lifted = false
        val eventBeforeDeadline = withTimeoutOrNull(longPressMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                if (!change.pressed) {
                    lifted = true
                    return@withTimeoutOrNull true
                }
                // Считаем путь от исходной точки, а не маленькую дельту одного
                // события: медленная прокрутка тоже должна отменить long press.
                if ((change.position - down.position).getDistance() > slop) {
                    moved = true
                    return@withTimeoutOrNull true
                }
            }
        }
        val held = eventBeforeDeadline == null && !lifted && !moved

        val result = layout() ?: continue
        val anchor = result.getOffsetForPosition(down.position) + shift

        if (lifted && !held && !moved) {
            // Обычный тап. Служебный глагол уходит в разбор всей цепочки,
            // остальное — в карточку слова.
            if (expandChain(parsed, anchor, onPhrase, onPhraseDone)) continue
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
    val radiusPx = viewConfiguration.touchSlop * 2f
    var pendingClick: PendingMouseClick? = null

    while (true) {
        // Первый клик нельзя отдавать карточке сразу: она успевает появиться и
        // перехватить второй. Пока есть кандидат, ждём второй down ровно окно
        // двойного клика; по таймауту честно выполняем одиночный клик.
        val nextDown = if (pendingClick == null) {
            awaitFirstDown(requireUnconsumed = false)
        } else {
            withTimeoutOrNull(doubleWindowMs) {
                awaitFirstDown(requireUnconsumed = false)
            }
        }
        if (nextDown == null) {
            pendingClick?.let { click ->
                if (!expandChain(parsed, click.offset, onPhrase, onPhraseDone)) {
                    parsed.tokenAt(click.offset)?.takeIf(Token::tappable)?.let(onWordTap)
                }
            }
            pendingClick = null
            continue
        }
        val down = nextDown
        val previous = pendingClick
        val isSecondClick = previous != null &&
            (down.position - previous.position).getDistance() <= radiusPx

        if (!isSecondClick) {
            // Клик в другом месте завершает предыдущий одиночный и сам
            // становится новым кандидатом на double click.
            previous?.let { click ->
                if (!expandChain(parsed, click.offset, onPhrase, onPhraseDone)) {
                    parsed.tokenAt(click.offset)?.takeIf(Token::tappable)?.let(onWordTap)
                }
            }
            pendingClick = null
            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                if (!change.pressed) break
            }
            if (!dragged) {
                layout()?.let { result ->
                    pendingClick = PendingMouseClick(
                        position = down.position,
                        offset = result.getOffsetForPosition(down.position) + shift,
                    )
                }
            }
            continue
        }

        // Второй клик: включаем выделение.
        pendingClick = null
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

/** Первый отпущенный клик, пока ещё способный стать двойным. */
private data class PendingMouseClick(val position: Offset, val offset: Int)

/** Краска отметки на куске абзаца. */
data class TextMark(val range: IntRange, val color: Color)

/** Кусок текста между двумя точками касания, растянутый до границ слов. */
private fun ParsedText.spanBetween(from: Int, to: Int): IntRange {
    val left = minOf(from, to)
    val right = maxOf(from, to)
    val start = tokenAt(left)?.start ?: left
    val end = tokenAt(right)?.end ?: right
    return start until maxOf(end, start + 1)
}

/**
 * Тап по служебному глаголу: выделяет всю группу сказуемого.
 *
 * «is» сам по себе в словаре пуст — читатель, ткнувший в него, спрашивает про
 * форму, а форма это «is walking» целиком. Поэтому касание по связке
 * превращается в выделение фразы: тот же путь, что и у долгого нажатия, и тот
 * же лист разбора на выходе.
 *
 * Касание по смысловому глаголу сюда не попадает намеренно: «walking» искать в
 * словаре осмысленно, и подменять там перевод разбором значило бы отнимать у
 * читателя ровно то, за чем он тыкал. Отличает их ядро, отдавая вместе с
 * цепочкой начало смыслового глагола.
 *
 * @return `true`, если касание израсходовано на цепочку.
 */
private fun expandChain(
    parsed: ParsedText,
    anchor: Int,
    onPhrase: (IntRange) -> Unit,
    onPhraseDone: (IntRange) -> Unit,
): Boolean {
    val chain = parsed.chainToExpand(anchor) ?: return false
    onPhrase(chain.range)
    onPhraseDone(chain.range)
    return true
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
