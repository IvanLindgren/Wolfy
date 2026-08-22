package com.wolfy.widgets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Огонёк серии занятий.
 *
 * Нарисован, а не набран эмодзи, и причина не в красоте. Эмодзи рисует шрифт
 * системы, а он на разных системах разный: на Windows в наборе, которым набран
 * интерфейс, цветного огня нет вовсе — и «🔥» на золотом кружке выходил чёрной
 * кляксой. Своя фигура выглядит одинаково везде и красится темой, а не
 * решением производителя шрифта.
 *
 * @param alive горит ли серия сейчас. Погасший огонь рисуется контуром: серии
 *   нет, и делать вид, что она есть, незачем — но место под неё видно, и
 *   понятно, что должно здесь появиться.
 */
@Composable
fun Flame(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    alive: Boolean = true,
) {
    // Живой огонь чуть дышит. Полторы секунды и три процента — движение на
    // грани заметности: оно оживляет экран, но не тянет на себя взгляд, когда
    // читатель пришёл заниматься, а не любоваться.
    val breath by rememberInfiniteTransition(label = "flame").animateFloat(
        initialValue = 1f,
        targetValue = if (alive) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Canvas(modifier.size(size)) {
        scale(scaleX = 1f, scaleY = breath, pivot = center.copy(y = this.size.height)) {
            drawFlame(color, filled = alive)
        }
    }
}

/**
 * Язык пламени: широкое основание, изогнутый кончик и капля внутри.
 *
 * Кривые заданы вручную, потому что огонь узнаётся именно по асимметрии —
 * симметричная капля читается как воздушный шар.
 */
private fun DrawScope.drawFlame(color: Color, filled: Boolean) {
    val width = size.width
    val height = size.height

    val body = Path().apply {
        moveTo(width * 0.50f, height * 0.06f)
        // Правый бок: подъём от основания к завалившемуся вправо кончику.
        cubicTo(
            width * 0.74f, height * 0.28f,
            width * 0.92f, height * 0.46f,
            width * 0.84f, height * 0.68f,
        )
        cubicTo(
            width * 0.76f, height * 0.92f,
            width * 0.24f, height * 0.92f,
            width * 0.16f, height * 0.68f,
        )
        // Левый бок ниже правого — из-за этого фигура и выглядит горящей.
        cubicTo(
            width * 0.10f, height * 0.50f,
            width * 0.26f, height * 0.44f,
            width * 0.32f, height * 0.24f,
        )
        cubicTo(
            width * 0.36f, height * 0.40f,
            width * 0.44f, height * 0.34f,
            width * 0.50f, height * 0.06f,
        )
        close()
    }

    if (filled) {
        // Тело приглушено, а сердцевина — в полную силу: получается горячее
        // ядро внутри пламени. Наоборот не работает: полупрозрачная капля
        // поверх непрозрачного тела того же цвета не даёт вообще ничего, а
        // вырезать её цветом фона нельзя — плашка под огнём бывает разной.
        drawPath(body, color.copy(alpha = 0.55f))
        val heart = Path().apply {
            moveTo(width * 0.50f, height * 0.46f)
            cubicTo(
                width * 0.66f, height * 0.60f,
                width * 0.64f, height * 0.82f,
                width * 0.50f, height * 0.82f,
            )
            cubicTo(
                width * 0.36f, height * 0.82f,
                width * 0.34f, height * 0.62f,
                width * 0.50f, height * 0.46f,
            )
            close()
        }
        drawPath(heart, color)
    } else {
        drawPath(body, color, style = Stroke(width = size.minDimension * 0.09f))
    }
}
