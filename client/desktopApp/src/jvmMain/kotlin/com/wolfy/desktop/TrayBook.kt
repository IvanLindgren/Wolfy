package com.wolfy.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Значок в трее: раскрытая книга.
 *
 * Рисуется вектором, а не картинкой из ресурсов, и это не поза. Трей на
 * Windows показывает значок в трёх разных размерах в зависимости от масштаба
 * интерфейса, и растровая картинка, подобранная под один из них, в остальных
 * двух выглядит мыльной. Вектор рисуется под тот размер, который спросили.
 *
 * Форма — та же, что у значка «читалка» в самом приложении: два разворота,
 * сходящихся посередине. Узнаваемость важнее оригинальности: значок в трее
 * ищут глазами среди двух десятков чужих.
 */
val TrayBook: ImageVector = ImageVector.Builder(
    name = "TrayBook",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // Штрих в 1,8 единицы, а не 1,6, как в приложении: значок трея живут в
    // шестнадцати пикселях, и волосяная линия на них исчезает вовсе.
    path(
        stroke = SolidColor(Color(0xFF111111)),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3f, 5.5f)
        curveTo(6f, 4.3f, 9f, 4.3f, 12f, 5.5f)
        verticalLineTo(18.5f)
        curveTo(9f, 17.3f, 6f, 17.3f, 3f, 18.5f)
        close()
    }
    path(
        stroke = SolidColor(Color(0xFF111111)),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(12f, 5.5f)
        curveTo(15f, 4.3f, 18f, 4.3f, 21f, 5.5f)
        verticalLineTo(18.5f)
        curveTo(18f, 17.3f, 15f, 17.3f, 12f, 18.5f)
        close()
    }
}.build()
