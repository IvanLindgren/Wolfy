package com.wolfy.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.wolfy.theme.WolfyTheme

/** Слово в разборе: текст и часть речи, которой оно оказалось. */
@Immutable
data class GraphWord(
    val text: String,
    /** Тег universal tagset или `null` у знаков препинания. */
    val tag: String? = null,
)

/**
 * Связь: кусок предложения, который движок узнал как одну конструкцию.
 *
 * [from] и [to] — номера слов в списке, полуинтервал.
 */
@Immutable
data class GraphLink(
    val from: Int,
    val to: Int,
    val label: String,
)

/**
 * Схема предложения: что с чем связано.
 *
 * Разбор в карточке перечисляет найденные правила списком, и этого хватает,
 * пока правило одно. Когда их три, список перестаёт отвечать на главный
 * вопрос — *какие именно слова* образуют каждое из них. «Present Perfect
 * Continuous» в предложении из четырнадцати слов ничего не показывает; скоба
 * под «has been reading» показывает всё.
 *
 * Поэтому здесь не картинка ради картинки, а ровно то, что нашёл движок:
 * слова покрашены по частям речи — теми же цветами, что в книге, — а под
 * ними скобы конструкций. Ничего сверх того, что видел разбор, схема не
 * придумывает.
 *
 * Скобы снизу, а не дуги сверху. Дуги красивее и читаются хуже: пересекаясь,
 * они превращаются в клубок, а скобы просто ложатся друг под друга рядами и
 * остаются разборчивыми при любом числе конструкций.
 */
@Composable
fun SentenceGraph(
    words: List<GraphWord>,
    links: List<GraphLink>,
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty()) return

    val colors = WolfyTheme.colors
    val palette = colors.partsOfSpeech
    val measurer = rememberTextMeasurer()
    val wordStyle = WolfyTheme.typography.body
    val labelStyle = WolfyTheme.typography.sectionLabel

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val gap = with(density) { WolfyTheme.spacing.small.toPx() }
        val lineStep = with(density) { LINE_STEP.toPx() }
        val bracketStep = with(density) { BRACKET_STEP.toPx() }

        val plan = remember(words, links, widthPx, wordStyle, labelStyle) {
            layoutSentence(
                words = words,
                links = links,
                measurer = measurer,
                wordStyle = wordStyle,
                labelStyle = labelStyle,
                maxWidth = widthPx,
                gap = gap,
                lineStep = lineStep,
                bracketStep = bracketStep,
            )
        }

        Canvas(Modifier.fillMaxWidth().height(with(density) { plan.height.toDp() })) {
            plan.words.forEachIndexed { index, placed ->
                drawText(
                    textLayoutResult = placed.layout,
                    color = words[index].tag?.let(palette::forTag) ?: colors.ink,
                    topLeft = Offset(placed.x, placed.y),
                )
            }

            plan.brackets.forEach { bracket ->
                drawBracket(bracket, colors.accent, colors.rule)
            }
        }
    }
}

/** Слово с посчитанным местом. */
private class PlacedWord(val layout: TextLayoutResult, val x: Float, val y: Float)

/** Скоба под куском предложения вместе с подписью. */
private class PlacedBracket(
    val left: Float,
    val right: Float,
    val y: Float,
    val label: TextLayoutResult,
    val labelAt: Offset,
)

private class GraphPlan(
    val words: List<PlacedWord>,
    val brackets: List<PlacedBracket>,
    val height: Float,
)

/**
 * Раскладка схемы.
 *
 * Считается целиком заранее и один раз: слова переносятся по строкам, потом
 * под каждой строкой выкладываются скобы. Порядок именно такой, потому что
 * скоба не может знать своей ширины, пока не известно, где стоят её крайние
 * слова, а строка не может знать высоты, пока не известно, сколько скоб под
 * ней ляжет.
 *
 * Связь, разорванная переносом строки, рисуется двумя скобами — по одной на
 * каждую строку. Подпись при этом ставится к первой: повторять название
 * правила дважды значит сказать, что правил два.
 */
private fun layoutSentence(
    words: List<GraphWord>,
    links: List<GraphLink>,
    measurer: TextMeasurer,
    wordStyle: TextStyle,
    labelStyle: TextStyle,
    maxWidth: Float,
    gap: Float,
    lineStep: Float,
    bracketStep: Float,
): GraphPlan {
    val measured = words.map { measurer.measure(it.text, wordStyle) }
    val labels = links.map { measurer.measure(it.label, labelStyle) }

    // Раскладываем слова по строкам. Знак препинания прилипает к предыдущему
    // слову без пробела — иначе запятая уезжает в начало следующей строки и
    // предложение читается как набор обрывков.
    val placed = ArrayList<PlacedWord>(words.size)
    val lineOf = IntArray(words.size)
    var x = 0f
    var line = 0
    val wordHeight = measured.maxOf { it.size.height }.toFloat()
    // Высота строки известна только вместе со скобами под ней, поэтому пока
    // запоминаем номер строки, а координату y подставим ниже.
    words.forEachIndexed { index, word ->
        val layout = measured[index]
        val sticky = word.tag == null && index > 0
        val advance = if (sticky) 0f else if (x == 0f) 0f else gap
        if (!sticky && x + advance + layout.size.width > maxWidth && x > 0f) {
            line += 1
            x = 0f
        }
        val left = x + if (x == 0f) 0f else advance
        placed.add(PlacedWord(layout, left, 0f))
        lineOf[index] = line
        x = left + layout.size.width
    }
    val lines = line + 1

    // Скобы: каждая связь получает свой ряд под строкой. Ряды считаются по
    // строкам отдельно — под короткой строкой не должно оставаться пустого
    // места из-за скобы, которая лежит под другой.
    val rows = IntArray(lines)
    val brackets = ArrayList<PlacedBracket>()
    val rowOfLink = IntArray(links.size)

    links.forEachIndexed { number, link ->
        val from = link.from.coerceIn(0, words.lastIndex)
        val to = (link.to - 1).coerceIn(from, words.lastIndex)
        val spanned = (lineOf[from]..lineOf[to])
        // Ряд один на всю связь: скобы одной конструкции обязаны лежать на
        // одной высоте, иначе разорванная переносом связь выглядит двумя.
        val row = spanned.maxOf { rows[it] }
        spanned.forEach { rows[it] = row + 1 }
        rowOfLink[number] = row
    }

    // Теперь известны высоты строк — расставляем y.
    val lineTop = FloatArray(lines)
    var cursor = 0f
    for (index in 0 until lines) {
        lineTop[index] = cursor
        cursor += lineStep + rows[index] * bracketStep
    }

    val withY = placed.mapIndexed { index, word ->
        PlacedWord(word.layout, word.x, lineTop[lineOf[index]])
    }

    links.forEachIndexed { number, link ->
        val from = link.from.coerceIn(0, words.lastIndex)
        val to = (link.to - 1).coerceIn(from, words.lastIndex)
        val row = rowOfLink[number]

        (lineOf[from]..lineOf[to]).forEach { current ->
            val onLine = words.indices.filter { lineOf[it] == current && it in from..to }
            if (onLine.isEmpty()) return@forEach
            val left = withY[onLine.first()].x
            val right = withY[onLine.last()].let { it.x + it.layout.size.width }
            val y = lineTop[current] + wordHeight + bracketStep * row + bracketStep * 0.35f
            val label = labels[number]
            brackets.add(
                PlacedBracket(
                    left = left,
                    right = right,
                    y = y,
                    label = label,
                    // Подпись — только у первой скобы связи.
                    labelAt = if (current == lineOf[from]) {
                        Offset(left, y + bracketStep * 0.15f)
                    } else {
                        Offset(Float.NaN, Float.NaN)
                    },
                ),
            )
        }
    }

    val height = cursor
    return GraphPlan(withY, brackets, height)
}

private fun DrawScope.drawBracket(bracket: PlacedBracket, accent: Color, rule: Color) {
    val thickness = 1.dp.toPx()
    val tick = 4.dp.toPx()

    drawLine(
        color = rule,
        start = Offset(bracket.left, bracket.y),
        end = Offset(bracket.right, bracket.y),
        strokeWidth = thickness,
    )
    // Засечки по краям: без них скоба сливается с соседней и непонятно, где
    // одна конструкция кончается и начинается другая.
    listOf(bracket.left, bracket.right).forEach { edge ->
        drawLine(
            color = rule,
            start = Offset(edge, bracket.y - tick),
            end = Offset(edge, bracket.y),
            strokeWidth = thickness,
            cap = Stroke.DefaultCap,
        )
    }

    if (!bracket.labelAt.x.isNaN()) {
        drawText(
            textLayoutResult = bracket.label,
            color = accent,
            topLeft = bracket.labelAt,
        )
    }
}

/** Высота строки слов до первой скобы. */
private val LINE_STEP = 26.dp

/** Шаг между рядами скоб — в нём же живёт подпись. */
private val BRACKET_STEP = 18.dp
