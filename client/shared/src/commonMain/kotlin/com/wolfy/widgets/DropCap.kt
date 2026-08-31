package com.wolfy.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.material3.Text
import androidx.compose.ui.layout.SubcomposeLayout
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Token
import com.wolfy.theme.FRAUNCES_CAP_HEIGHT
import com.wolfy.theme.GARAMOND_CAP_HEIGHT
import com.wolfy.theme.PLAYFAIR_CAP_HEIGHT
import com.wolfy.theme.WolfyTheme

/**
 * Первый абзац главы с буквицей.
 *
 * ## Почему это не одна строка кода
 *
 * В печати буквица работает обтеканием: крупная литера занимает три строки, а
 * текст огибает её справа. В Compose обтекания нет — `Text` рисует
 * прямоугольник, и никакого «float» в его модели не существует.
 *
 * Поэтому абзац честно делится на две части. Первая — те слова, что
 * помещаются в узкую колонку рядом с буквицей; они рисуются справа от литеры.
 * Вторая — остаток, он идёт под ними на всю ширину. Границу между частями
 * находит измеритель текста: он раскладывает абзац в узкой ширине и говорит,
 * на каком символе кончается третья строка.
 *
 * ## Четыре правила, без которых приём разваливается
 *
 * **Буквица меряется от линии прописных, а не от верха коробки.** Приём
 * определён двумя границами набора: верх литеры стоит на линии прописных
 * первой строки, низ — на базовой линии последней. Прежний код отсчитывал верх
 * от верха первой строки, то есть прихватывал и междустрочный воздух, и
 * надстрочное поле шрифта: около семи точек при кегле 19. Литера получалась на
 * десятую часть крупнее положенной и начиналась выше текста отдельным этажом.
 * Считает это [dropCapPlan], и он же единственное место, где эту арифметику
 * можно проверить.
 *
 * **Буквица садится на базовую линию, а не в начало коробки.** Печатная
 * буквица стоит на той же линии, что последняя строка рядом с ней; выключка по
 * верхнему краю давала литеру, висящую отдельно от текста.
 *
 * **Шрифт буквицы выбирает буква.** Во Fraunces нет кириллицы — только
 * латиница. Русская глава открывалась литерой от случайного системного шрифта,
 * которой при этом назначали кегль по метрике Fraunces: и характер не тот, и
 * высота не та. Кириллицу набирает Playfair, у которого кириллица есть, и
 * метрика к нему прилагается своя.
 *
 * **Замер обязан мерить то, что будет нарисовано.** Абзац рисуется с
 * полужирными основами слов, если читатель включил их в настройках, а мерился
 * обычным начертанием. Полужирный шире, реальные строки не совпадали с
 * посчитанными, и разрез приходился не туда, где кончается третья строка.
 *
 * И общее правило поверх этих четырёх: если рядом с литерой не остаётся места на
 * осмысленную строку, буквицы просто не будет. Абзац, потерявший первую букву
 * ради приёма, который не сработал, — это не оформление, а опечатка.
 */
@Composable
fun DropCapParagraph(
    parsed: ParsedText,
    modifier: Modifier = Modifier,
    linesBesideCap: Int = 3,
    saved: Set<String> = emptySet(),
    savedLemmaOf: (Token) -> String = { it.text.lowercase() },
    selected: Token? = null,
    selection: IntRange? = null,
    /** Краски отметок; передаются так же, как выделение фразы. */
    marks: List<TextMark> = emptyList(),
    selectViaMouse: Boolean = false,
    /** Докуда набирать каждое слово полужирным — по числу на токен абзаца. */
    anchors: List<Int> = emptyList(),
    /** Притушить абзац целиком: читатель сейчас не здесь. */
    dimmed: Boolean = false,
    /** Единственный светлый кусок абзаца в его собственных смещениях. */
    bright: IntRange? = null,
    onWordTap: (Token) -> Unit = {},
    onPhrase: (IntRange) -> Unit = {},
    onPhraseDone: (IntRange) -> Unit = {},
    /** Границы выделения; каждая часть буквичной вёрстки сообщает свою. */
    onSelectionBounds: ((Rect) -> Unit)? = null,
) {
    val colors = WolfyTheme.colors
    val typography = WolfyTheme.typography
    val spacing = WolfyTheme.spacing
    val fonts = WolfyTheme.fonts
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val fullText = remember(parsed) { parsed.tokens.joinToString("") { it.text } }

    // Буквица — первая буква абзаца. Если абзац начинается не с буквы
    // (кавычка, тире прямой речи), буквицу не рисуем вовсе: висящая кавычка
    // кеглем в три строки выглядит опечаткой, а не приёмом.
    val capChar = fullText.firstOrNull()
    if (capChar == null || !capChar.isLetter()) {
        ReaderParagraph(
            parsed = parsed,
            modifier = modifier,
            style = typography.reader,
            saved = saved,
            savedLemmaOf = savedLemmaOf,
            selected = selected,
            selection = selection,
            marks = marks,
            selectViaMouse = selectViaMouse,
            anchors = anchors,
            dimmed = dimmed,
            bright = bright,
            onWordTap = onWordTap,
            onPhrase = onPhrase,
            onPhraseDone = onPhraseDone,
            onSelectionBounds = onSelectionBounds,
        )
        return
    }

    // Метрики набора, по которым считается буквица. Первая базовая линия
    // берётся замером, а не формулой: Compose распределяет разницу между
    // высотой строки и метриками шрифта сам, и повторять это распределение
    // числом значило бы держать копию чужого правила.
    val bodyBaseline = remember(typography.reader) {
        measurer.measure(AnnotatedString(BASELINE_PROBE), typography.reader).firstBaseline
    }

    // Замер остатка абзаца — с теми же полужирными основами, с какими он
    // будет нарисован. Первый токен своей основы лишается: её начало унесла
    // буквица, и слайсер обнуляет якорь ровно так же (см. slice ниже).
    val measuredRest: AnnotatedString = remember(parsed, anchors) {
        buildAnnotatedString {
            parsed.tokens.forEachIndexed { index, token ->
                val start = length
                append(token.text)
                if (index == 0) return@forEachIndexed
                val anchor = anchors.getOrElse(index) { 0 }
                if (anchor in 1 until token.text.length) {
                    addStyle(SpanStyle(fontWeight = FontWeight.W600), start, start + anchor)
                }
            }
        }.let { if (it.length > 1) it.subSequence(1, it.length) else AnnotatedString("") }
    }

    // Мера «узкой колонки»: строка, в которую не влезает и восьми знаков, —
    // это лесенка из обрывков слов, а не колонка. Меряется настоящим кеглем
    // читалки, поэтому предел едет вместе с размером текста.
    val narrowColumn = remember(typography.reader) {
        measurer.measure(AnnotatedString(NARROW_PROBE), typography.reader).size.width
    }

    val plainParagraph: @Composable () -> Unit = {
        ReaderParagraph(
            parsed = parsed,
            style = typography.reader,
            saved = saved,
            savedLemmaOf = savedLemmaOf,
            selected = selected,
            selection = selection,
            marks = marks,
            selectViaMouse = selectViaMouse,
            anchors = anchors,
            dimmed = dimmed,
            bright = bright,
            onWordTap = onWordTap,
            onPhrase = onPhrase,
            onPhraseDone = onPhraseDone,
            onSelectionBounds = onSelectionBounds,
        )
    }

    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth

        // Высота видимой литеры: от линии прописных первой строки до базовой
        // линии последней рядом с ней. Это и есть определение буквицы в n
        // строк, а кегль из него уже выводится.
        val lineHeightPx = typography.reader.lineHeight.toPx()
        // Буквой определяется и шрифт, и метрика: одно без другого даёт литеру
        // верного начертания и неверной высоты.
        val latin = frauncesHasLetter(capChar)
        val plan = dropCapPlan(
            bodyBaseline = bodyBaseline,
            bodyCapHeight = typography.reader.fontSize.toPx() * GARAMOND_CAP_HEIGHT,
            lineHeight = lineHeightPx,
            lines = linesBesideCap,
            capHeightRatio = if (latin) FRAUNCES_CAP_HEIGHT else PLAYFAIR_CAP_HEIGHT,
        )
        val capStyle = TextStyle(
            fontFamily = if (latin) fonts.dropCap else fonts.display,
            fontWeight = FontWeight.Bold,
            fontSize = plan.fontSize.toSp(),
            color = colors.ink,
        )

        val capLayout = measurer.measure(AnnotatedString(capChar.toString()), capStyle)
        val capWidth = capLayout.size.width
        val gap = with(density) { spacing.small.roundToPx() }
        val besideWidth = width - capWidth - gap

        // Отступление к обычному абзацу. Оно же — единственный ответ на любую
        // настройку, при которой приём не помещается: буква остаётся на своём
        // месте в слове, а не улетает в отдельную строку.
        fun fallback(): androidx.compose.ui.layout.MeasureResult {
            val plain = subcompose(DropCapSlot.Plain, plainParagraph).first().measure(constraints)
            return layout(plain.width, plain.height) { plain.place(0, 0) }
        }

        if (besideWidth < narrowColumn) return@SubcomposeLayout fallback()

        // Раскладываем остаток абзаца в узкой колонке и смотрим, где кончается
        // последняя строка, помещающаяся рядом с буквицей.
        val restLayout = measurer.measure(
            text = measuredRest,
            style = typography.reader,
            constraints = Constraints(maxWidth = besideWidth),
        )
        // Буквице нужен абзац, а не строка. Одна-две строки — это заголовок,
        // подпись или короткая реплика, и трёхстрочная литера рядом с ними
        // выглядит не приёмом, а сбоем вёрстки: именно так первый короткий
        // блок главы получал огромную букву и одну строку текста сбоку, а всё
        // остальное съезжало вниз отдельным абзацем.
        if (restLayout.lineCount <= linesBesideCap) return@SubcomposeLayout fallback()
        val splitAt = restLayout.getLineEnd(linesBesideCap - 1, visibleEnd = true)
        if (splitAt <= 0) return@SubcomposeLayout fallback()

        // Смещения считаются от начала абзаца: буквица — один символ, поэтому
        // граница в исходном тексте на единицу больше.
        val beside = parsed.slice(1, splitAt + 1, anchors)
        val below = parsed.slice(splitAt + 1, fullText.length, anchors)
        if (beside.parsed.tokens.isEmpty()) return@SubcomposeLayout fallback()

        // Тап по обрезанному куску слова должен открывать карточку целого
        // слова: «he» после буквицы — это по-прежнему «the», и разбирать надо
        // его. Вёрстка чинит то, что сама разрезала.
        val tapWhole: (Token) -> Unit = { clipped ->
            val whole = parsed.tokens.firstOrNull {
                it.start <= clipped.start && clipped.end <= it.end
            } ?: clipped
            onWordTap(whole)
        }

        val besidePlaceable = subcompose(DropCapSlot.Beside) {
            Box(Modifier.width(with(density) { besideWidth.toDp() })) {
                ReaderParagraph(
                    parsed = beside.parsed,
                    style = typography.reader,
                    saved = saved,
                    savedLemmaOf = savedLemmaOf,
                    selected = selected,
                    selection = selection,
                    marks = marks,
                    // Локальные смещения этой раскладки начинаются с нуля, а
                    // токены — с единицы (буквицы): без сдвига попадание
                    // уехало бы на один знак.
                    offsetShift = 1,
                    selectViaMouse = selectViaMouse,
                    anchors = beside.anchors,
                    dimmed = dimmed,
                    bright = bright?.shiftedInto(1, splitAt + 1),
                    onWordTap = tapWhole,
                    onPhrase = onPhrase,
                    onPhraseDone = onPhraseDone,
                    onSelectionBounds = onSelectionBounds,
                )
            }
        }.first().measure(Constraints(maxWidth = besideWidth))

        val capPlaceable = subcompose(DropCapSlot.Cap) {
            Text(text = capChar.toString(), style = capStyle)
        }.first().measure(Constraints())

        // Буквица садится базовой линией на базовую линию последней строки
        // рядом с ней. Строк заведомо хватает: более короткий абзац сюда не
        // доходит. Верх литеры при этом приходит на линию прописных первой
        // строки сам — ровно из этого посчитан её кегль.
        //
        // Координата у литеры получается отрицательной, и так и надо: над
        // видимой буквой у шрифта остаётся пустое поле выносных элементов
        // (0.978 кегля до базовой линии против 0.700 у самой литеры). Отбивать
        // сверху пустоту в четверть буквицы, лишь бы число было
        // неотрицательным, значило бы двигать текст ради невидимого.
        val capY = (restLayout.getLineBaseline(linesBesideCap - 1) - capLayout.firstBaseline).toInt()
        val besideY = 0

        val belowPlaceable = if (below.parsed.tokens.isEmpty()) {
            null
        } else {
            subcompose(DropCapSlot.Below) {
                ReaderParagraph(
                    parsed = below.parsed,
                    style = typography.reader,
                    saved = saved,
                    savedLemmaOf = savedLemmaOf,
                    selected = selected,
                    selection = selection,
                    marks = marks,
                    offsetShift = splitAt + 1,
                    selectViaMouse = selectViaMouse,
                    anchors = below.anchors,
                    dimmed = dimmed,
                    bright = bright?.shiftedInto(splitAt + 1, fullText.length),
                    onWordTap = tapWhole,
                    onPhrase = onPhrase,
                    onPhraseDone = onPhraseDone,
                    onSelectionBounds = onSelectionBounds,
                )
            }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        }

        // Низ блока — низ колонки рядом с буквицей или базовая линия литеры,
        // что ниже. Полная высота площадки шрифта в счёт не идёт: у прописной
        // буквы нет выносного элемента вниз, и вычитать её значило бы отбить
        // снизу треть строки пустоты.
        val capBottom = capY + capLayout.firstBaseline.toInt()
        val headHeight = maxOf(besideY + besidePlaceable.height, capBottom)
        val totalHeight = headHeight + (belowPlaceable?.height ?: 0)
        layout(width, totalHeight) {
            capPlaceable.place(0, capY)
            besidePlaceable.place(capWidth + gap, besideY)
            belowPlaceable?.place(0, headHeight)
        }
    }
}

/** Слоты подкомпозиции. Именованные, чтобы порядок вызовов не был контрактом. */
private enum class DropCapSlot { Plain, Cap, Beside, Below }

/**
 * Проба узкой колонки.
 *
 * Восемь знаков — граница, за которой строка перестаёт быть строкой: в неё не
 * помещается среднее слово, и колонка превращается в столбик переносов.
 */
private const val NARROW_PROBE = "восемьзн"

/** Проба для замера первой базовой линии набора. */
private const val BASELINE_PROBE = "Hg"

/**
 * Кусок разбора между двумя смещениями.
 *
 * Токены, попавшие в кусок, сохраняют исходные позиции — иначе тап по слову во
 * второй половине абзаца открыл бы карточку не того слова. Поэтому смещения
 * пересчитываются относительно начала куска только для отрисовки, а исходные
 * границы едут в `Token.start`/`Token.end` как есть.
 */
private data class SlicedText(val parsed: ParsedText, val anchors: List<Int>)

private fun ParsedText.slice(from: Int, to: Int, anchors: List<Int>): SlicedText {
    if (from >= to) return SlicedText(ParsedText(), emptyList())

    val inside = mutableListOf<Token>()
    val insideAnchors = mutableListOf<Int>()

    tokens.forEachIndexed { index, token ->
        val start = maxOf(token.start, from)
        val end = minOf(token.end, to)
        if (start >= end) return@forEachIndexed

        if (start == token.start && end == token.end) {
            inside.add(token)
            insideAnchors.add(anchors.getOrElse(index) { 0 })
        } else {
            // Токен пересекает границу — обрезаем его, а не выбрасываем.
            // Именно здесь живёт буквица: слово «The» делится на литеру «T» и
            // остаток «he», и без обрезки этот остаток пропал бы со страницы.
            inside.add(
                token.copy(
                    start = start,
                    end = end,
                    text = token.text.substring(start - token.start, end - token.start),
                ),
            )
            // У обрезанного слова якоря нет: он считался от начала целого
            // слова, а начала здесь уже нет — буквица его унесла. Полужирный
            // хвост без своего начала выглядел бы опечаткой.
            insideAnchors.add(0)
        }
    }
    return SlicedText(ParsedText(tokens = inside, sentences = sentences), insideAnchors)
}

/**
 * Светлый кусок окна чтения в координатах отрезанной части.
 *
 * Окно приходит в смещениях всего абзаца, а каждая часть буквичной вёрстки
 * раскладывается со своими, от нуля. Пересечения нет — значит эта часть вся
 * притушена, и `null` здесь сказал бы обратное; поэтому пустой диапазон.
 */
private fun IntRange.shiftedInto(from: Int, to: Int): IntRange {
    val start = maxOf(first, from)
    val end = minOf(last + 1, to)
    if (start >= end) return IntRange.EMPTY
    return (start - from) until (end - from)
}
