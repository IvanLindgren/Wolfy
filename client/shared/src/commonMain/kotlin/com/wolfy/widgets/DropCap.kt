package com.wolfy.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.layout.SubcomposeLayout
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Token
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
 * помещаются в узкую колонку рядом с буквицей; они рисуются в `Row` справа от
 * литеры. Вторая — остаток, он идёт под ними на всю ширину. Границу между
 * частями находит измеритель текста: он раскладывает абзац в узкой ширине и
 * говорит, на каком символе кончается третья строка.
 *
 * Дешёвых способов тут нет. Отказаться от буквицы можно, но она — половина
 * узнаваемости газетного разворота, ради которого всё и затевалось.
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
    onWordTap: (Token) -> Unit = {},
    onPhrase: (IntRange) -> Unit = {},
    onPhraseDone: (IntRange) -> Unit = {},
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
            onWordTap = onWordTap,
            onPhrase = onPhrase,
            onPhraseDone = onPhraseDone,
        )
        return
    }

    val capStyle = remember(typography, fonts, linesBesideCap, colors) {
        TextStyle(
            fontFamily = fonts.dropCap,
            fontWeight = FontWeight.Bold,
            // Кегль буквицы — высота стольких строк основного текста.
            fontSize = typography.reader.lineHeight * linesBesideCap,
            lineHeight = typography.reader.lineHeight * linesBesideCap,
            color = colors.ink,
        )
    }

    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth

        val capLayout = measurer.measure(capChar.toString(), capStyle)
        val capWidth = capLayout.size.width
        val gap = with(density) { spacing.small.roundToPx() }
        val besideWidth = (width - capWidth - gap).coerceAtLeast(1)

        // Раскладываем остаток абзаца в узкой колонке и смотрим, где кончается
        // последняя строка, помещающаяся рядом с буквицей.
        val rest = fullText.substring(1)
        val restLayout = measurer.measure(
            text = rest,
            style = typography.reader,
            constraints = Constraints(maxWidth = besideWidth),
        )
        val splitAt = if (restLayout.lineCount <= linesBesideCap) {
            rest.length
        } else {
            restLayout.getLineEnd(linesBesideCap - 1, visibleEnd = true)
        }

        // Смещения считаются от начала абзаца: буквица — один символ, поэтому
        // граница в исходном тексте на единицу больше.
        val beside = parsed.slice(1, splitAt + 1, anchors)
        val below = parsed.slice(splitAt + 1, fullText.length, anchors)
        val besideParsed = beside.parsed
        val belowParsed = below.parsed

        // Тап по обрезанному куску слова должен открывать карточку целого
        // слова: «he» после буквицы — это по-прежнему «the», и разбирать надо
        // его. Вёрстка чинит то, что сама разрезала.
        val tapWhole: (Token) -> Unit = { clipped ->
            val whole = parsed.tokens.firstOrNull {
                it.start <= clipped.start && clipped.end <= it.end
            } ?: clipped
            onWordTap(whole)
        }

        val content = subcompose("dropCap") {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = capChar.toString(),
                        style = capStyle,
                        modifier = Modifier.padding(end = spacing.small),
                    )
                    Box(Modifier.width(with(density) { besideWidth.toDp() })) {
                        ReaderParagraph(
                            parsed = besideParsed,
                            style = typography.reader,
                            saved = saved,
                            savedLemmaOf = savedLemmaOf,
                            selected = selected,
                            selection = selection,
                            marks = marks,
                            // Локальные смещения этой раскладки начинаются с
                            // нуля, а токены — с единицы (буквицы): без
                            // сдвига попадание уехало бы на один знак.
                            offsetShift = 1,
                            selectViaMouse = selectViaMouse,
                            anchors = beside.anchors,
                            dimmed = dimmed,
                            onWordTap = tapWhole,
                            onPhrase = onPhrase,
                            onPhraseDone = onPhraseDone,
                        )
                    }
                }
                if (belowParsed.tokens.isNotEmpty()) {
                    ReaderParagraph(
                        parsed = belowParsed,
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
                        onWordTap = tapWhole,
                        onPhrase = onPhrase,
                        onPhraseDone = onPhraseDone,
                    )
                }
            }
        }

        val placeable = content.first().measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

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

