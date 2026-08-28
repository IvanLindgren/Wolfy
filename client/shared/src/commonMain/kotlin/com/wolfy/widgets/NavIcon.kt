package com.wolfy.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** Значки нижней навигации и компактных действий читалки. */
enum class NavIcon { Books, Shelves, Discover, Cards, More, Reading, Recap }

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
            NavIcon.Discover -> drawDiscover(tint)
            NavIcon.Cards -> drawCards(tint)
            NavIcon.More -> drawGear(tint)
            NavIcon.Reading -> drawReadingSettings(tint)
            NavIcon.Recap -> drawRecap(tint)
        }
    }
}

/** Компас ленты: ромбовидная стрелка без платформенного emoji-шрифта. */
private fun DrawScope.drawDiscover(tint: Color) {
    val line = size.minDimension * 0.09f
    drawCircle(tint, radius = size.minDimension * 0.42f, style = Stroke(width = line))
    val needle = Path().apply {
        moveTo(size.width * 0.66f, size.height * 0.22f)
        lineTo(size.width * 0.55f, size.height * 0.55f)
        lineTo(size.width * 0.22f, size.height * 0.66f)
        lineTo(size.width * 0.45f, size.height * 0.45f)
        close()
    }
    drawPath(needle, tint)
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

/**
 * Колода: две карты веером.
 *
 * Была круговая стрелка — знак повторения. Но раздел называется «Карточки», и
 * стрелка обещала механизм, а не содержимое: человек ищет глазами свои
 * карточки, а находит значок обновления.
 */
private fun DrawScope.drawCards(tint: Color) {
    val line = size.minDimension * 0.09f
    val stroke = Stroke(width = line)
    val width = size.width * 0.52f
    val height = size.height * 0.72f

    // Задняя карта завалена влево — из-за наклона стопка и читается стопкой.
    rotate(degrees = -14f, pivot = Offset(size.width * 0.5f, size.height * 0.9f)) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
            size = Size(width, height),
            cornerRadius = CornerRadius(line, line),
            style = stroke,
        )
    }

    drawRoundRect(
        color = tint,
        topLeft = Offset(size.width * 0.34f, size.height * 0.2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(line, line),
        style = stroke,
    )
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

/** Три типографских ползунка: настройки именно текста, не всего приложения. */
private fun DrawScope.drawReadingSettings(tint: Color) {
    val line = size.minDimension * 0.09f
    val ys = listOf(0.24f, 0.50f, 0.76f)
    val knobs = listOf(0.68f, 0.34f, 0.58f)
    for (index in ys.indices) {
        val y = size.height * ys[index]
        drawLine(tint, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), strokeWidth = line)
        drawCircle(tint, radius = line * 1.35f, center = Offset(size.width * knobs[index], y))
    }
}

/** Круговая стрелка назад: вернуться к недавним событиям книги. */
private fun DrawScope.drawRecap(tint: Color) {
    val line = size.minDimension * 0.09f
    drawArc(
        color = tint,
        startAngle = -65f,
        sweepAngle = 285f,
        useCenter = false,
        topLeft = Offset(size.width * 0.13f, size.height * 0.13f),
        size = Size(size.width * 0.74f, size.height * 0.74f),
        style = Stroke(width = line),
    )
    val arrow = Path().apply {
        moveTo(size.width * 0.12f, size.height * 0.38f)
        lineTo(size.width * 0.13f, size.height * 0.13f)
        lineTo(size.width * 0.37f, size.height * 0.18f)
    }
    drawPath(arrow, tint, style = Stroke(width = line))
}
