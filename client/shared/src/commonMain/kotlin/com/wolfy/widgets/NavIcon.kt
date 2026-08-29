package com.wolfy.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
 *
 * Набор держится на двух правилах, и раньше нарушались оба.
 *
 * Одна толщина линии на все значки. Было три — 0.075, 0.09 и 0.1, — и разница
 * в треть веса заметна даже в двадцати двух точках: рядом стоящие значки
 * выглядели набранными разными шрифтами.
 *
 * Одна логика заливки: контур рисуется штрихом, заливку получает только то,
 * что и в жизни является телом, — корешки книг на полке. Полка из четырёх
 * сплошных прямоугольников читалась как столбчатая диаграмма, а не как книги.
 *
 * Концы линий прямые. Это не упущение: скруглённые концы — примета гротеска,
 * а здесь газетная сетка.
 */
@Composable
fun NavGlyph(icon: NavIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        when (icon) {
            NavIcon.Books -> drawBook(tint)
            NavIcon.Shelves -> drawShelves(tint)
            NavIcon.Discover -> drawDiscover(tint)
            NavIcon.Cards -> drawCards(tint)
            NavIcon.More -> drawMore(tint)
            NavIcon.Reading -> drawReadingSettings(tint)
            NavIcon.Recap -> drawRecap(tint)
        }
    }
}

/** Единственная толщина линии в наборе. */
private val DrawScope.line: Float get() = size.minDimension * 0.085f

private val DrawScope.hairline: Stroke get() = Stroke(width = line)

/** Компас ленты: ромбовидная стрелка без платформенного emoji-шрифта. */
private fun DrawScope.drawDiscover(tint: Color) {
    drawCircle(tint, radius = size.minDimension * 0.42f, style = hairline)
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
    val margin = line
    val middle = size.width / 2

    val page = Path().apply {
        moveTo(middle, size.height * 0.22f)
        lineTo(margin, size.height * 0.12f)
        lineTo(margin, size.height - margin)
        lineTo(middle, size.height * 0.88f)
    }
    drawPath(page, tint, style = hairline)

    val mirrored = Path().apply {
        moveTo(middle, size.height * 0.22f)
        lineTo(size.width - margin, size.height * 0.12f)
        lineTo(size.width - margin, size.height - margin)
        lineTo(middle, size.height * 0.88f)
    }
    drawPath(mirrored, tint, style = hairline)
}

/**
 * Полка: корешки, стоящие на доске.
 *
 * Было четыре сплошных прямоугольника разной высоты — ровно столбчатая
 * диаграмма. Полку от диаграммы отличает то, на чём книги стоят, поэтому
 * доска здесь и появилась, а корешки стали уже и разной ширины: одинаковые
 * прямоугольники через равный шаг снова читались бы как график.
 */
private fun DrawScope.drawShelves(tint: Color) {
    val board = size.height * 0.80f
    // Доска во всю ширину — она и делает полку полкой.
    drawRect(
        color = tint,
        topLeft = Offset(0f, board),
        size = Size(size.width, line),
    )

    // Корешки разной ширины и высоты: ряд одинаковых книг не стоит нигде.
    val spines = listOf(0.13f to 0.42f, 0.11f to 0.58f, 0.15f to 0.34f, 0.10f to 0.50f, 0.13f to 0.44f)
    var x = size.width * 0.04f
    val gap = size.width * 0.045f
    for ((widthShare, heightShare) in spines) {
        val width = size.width * widthShare
        val height = size.height * heightShare
        drawRect(
            color = tint,
            topLeft = Offset(x, board - height),
            size = Size(width, height),
        )
        x += width + gap
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
    val width = size.width * 0.52f
    val height = size.height * 0.72f

    // Задняя карта завалена влево — из-за наклона стопка и читается стопкой.
    rotate(degrees = -14f, pivot = Offset(size.width * 0.5f, size.height * 0.9f)) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
            size = Size(width, height),
            cornerRadius = CornerRadius(line, line),
            style = hairline,
        )
    }

    drawRoundRect(
        color = tint,
        topLeft = Offset(size.width * 0.34f, size.height * 0.2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(line, line),
        style = hairline,
    )
}

/**
 * «Ещё»: три точки.
 *
 * Была шестерёнка из кольца и восьми прямоугольных зубцов. В двадцати двух
 * точках зуб — это три пикселя, и вся корона рассыпалась в кашу на любой
 * плотности, кроме самой высокой. Хуже того, шестерёнка обещала настройки, а
 * за вкладкой лежат словарь, справочник, компаньон и уже потом настройки.
 *
 * Три точки — единственная фигура, которая честно означает «и ещё вот это»
 * и не разваливается ни на каком размере.
 */
private fun DrawScope.drawMore(tint: Color) {
    val radius = line * 0.95f
    val y = size.height / 2
    for (share in listOf(0.22f, 0.5f, 0.78f)) {
        drawCircle(tint, radius = radius, center = Offset(size.width * share, y))
    }
}

/** Три типографских ползунка: настройки именно текста, не всего приложения. */
private fun DrawScope.drawReadingSettings(tint: Color) {
    val ys = listOf(0.24f, 0.50f, 0.76f)
    val knobs = listOf(0.68f, 0.34f, 0.58f)
    for (index in ys.indices) {
        val y = size.height * ys[index]
        drawLine(tint, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), strokeWidth = line)
        drawCircle(tint, radius = line * 1.35f, center = Offset(size.width * knobs[index], y))
    }
}

/**
 * «Вспомнить сюжет»: дуга назад.
 *
 * Была раскрытая книга с корешком, стрелкой внутри и отдельной линией под
 * ней — четыре элемента тоньше основного веса в поле двадцать два на
 * двадцать два. Рядом со значком книги в той же шапке она к тому же читалась
 * как второй значок книги.
 *
 * Осталось одно движение: почти полный круг против часовой и наконечник.
 * Здесь круговая стрелка уместна ровно потому, из-за чего её убрали с
 * «Карточек», — вернуться к прочитанному и есть то, что делает действие.
 */
private fun DrawScope.drawRecap(tint: Color) {
    val inset = line * 1.6f
    val box = Rect(
        offset = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
    )
    // Разрыв сверху слева — там, где встаёт наконечник.
    drawArc(
        color = tint,
        startAngle = 200f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = box.topLeft,
        size = box.size,
        style = hairline,
    )
    val tip = Offset(box.left + box.width * 0.5f, box.top)
    val head = Path().apply {
        moveTo(tip.x, tip.y - line * 1.5f)
        lineTo(tip.x - line * 2.1f, tip.y)
        lineTo(tip.x, tip.y + line * 1.5f)
        close()
    }
    drawPath(head, tint)
}
