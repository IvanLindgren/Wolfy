package com.wolfy.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** Значки нижней навигации. */
enum class NavIcon { Books, Shelves, Srs, More }

/**
 * Значок нижней навигации, нарисованный кодом.
 *
 * Готовый набор значков потянул бы за собой несколько мегабайт ради четырёх
 * фигур — и всё равно выглядел бы чужим: у них скруглённые концы и вес,
 * рассчитанные на гротеск, а не на газетную сетку. Здесь фигуры простые
 * настолько, что описать их короче, чем подключить библиотеку.
 */
@Composable
fun NavGlyph(icon: NavIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        when (icon) {
            NavIcon.Books -> drawBook(tint)
            NavIcon.Shelves -> drawShelves(tint)
            NavIcon.Srs -> drawCycle(tint)
            NavIcon.More -> drawGear(tint)
        }
    }
}

/** Раскрытая книга: две страницы, сходящиеся к корешку. */
private fun DrawScope.drawBook(tint: Color) {
    val line = size.minDimension * 0.09f
    val stroke = Stroke(width = line)
    val margin = line
    val middle = size.width / 2

    val page = Path().apply {
        moveTo(middle, size.height * 0.22f)
        lineTo(margin, size.height * 0.12f)
        lineTo(margin, size.height - margin)
        lineTo(middle, size.height * 0.88f)
    }
    drawPath(page, tint, style = stroke)

    val mirrored = Path().apply {
        moveTo(middle, size.height * 0.22f)
        lineTo(size.width - margin, size.height * 0.12f)
        lineTo(size.width - margin, size.height - margin)
        lineTo(middle, size.height * 0.88f)
    }
    drawPath(mirrored, tint, style = stroke)
}

/** Полка: корешки книг разной высоты — как на макете. */
private fun DrawScope.drawShelves(tint: Color) {
    val count = 4
    val gap = size.width * 0.06f
    val width = (size.width - gap * (count - 1)) / count
    val heights = listOf(0.72f, 0.92f, 0.60f, 0.84f)

    heights.forEachIndexed { index, share ->
        val height = size.height * share
        drawRect(
            color = tint,
            topLeft = Offset(index * (width + gap), size.height - height),
            size = Size(width, height),
        )
    }
}

/** Повторение: круговая стрелка. */
private fun DrawScope.drawCycle(tint: Color) {
    val line = size.minDimension * 0.1f
    val inset = line * 1.6f
    drawArc(
        color = tint,
        startAngle = 40f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(width = line),
    )

    // Остриё стрелки на разомкнутом конце дуги — без него круг читается как
    // буква «C», а не как повторение.
    val tip = size.minDimension * 0.22f
    val head = Path().apply {
        moveTo(size.width * 0.78f, size.height * 0.16f)
        lineTo(size.width * 0.78f + tip, size.height * 0.16f + tip * 0.5f)
        lineTo(size.width * 0.78f - tip * 0.1f, size.height * 0.16f + tip)
        close()
    }
    drawPath(head, tint)
}

/** Шестерёнка: кольцо и восемь зубцов. */
private fun DrawScope.drawGear(tint: Color) {
    val line = size.minDimension * 0.1f
    val radius = size.minDimension * 0.28f
    val center = Offset(size.width / 2, size.height / 2)

    drawCircle(tint, radius = radius, center = center, style = Stroke(width = line))

    val toothLength = size.minDimension * 0.16f
    val toothWidth = size.minDimension * 0.14f
    repeat(8) { index ->
        rotate(degrees = index * 45f, pivot = center) {
            drawRect(
                color = tint,
                topLeft = Offset(center.x - toothWidth / 2, center.y - radius - toothLength * 0.9f),
                size = Size(toothWidth, toothLength),
            )
        }
    }
}
