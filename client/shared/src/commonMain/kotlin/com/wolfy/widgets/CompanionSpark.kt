package com.wolfy.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.still
import kotlinx.coroutines.delay

/** Что показать над компаньоном. */
enum class SparkKind {
    /** Догадка: лампочка. Ответ пришёл, компаньону есть что сказать. */
    Idea,

    /** Похвала: звёздочки. Глава дочитана, полоса не прервалась. */
    Cheer,
}

/**
 * Короткая вспышка над компаньоном: лампочка или звёздочки.
 *
 * Зачем вообще. У компаньона до сих пор был ровно один способ сообщить о себе -
 * текстовый пузырь. Пузырь требует чтения, а читатель в этот момент занят
 * книгой: он видит текст и невольно читает его вместо страницы. Вспышка
 * говорит то же самое, но не отнимает чтение, и её можно не заметить без
 * потери - в этом и смысл необязательного знака.
 *
 * Играет один раз и уходит. Ничего бесконечного здесь нет намеренно: постоянно
 * мерцающий значок в поле зрения читающего человека - помеха, а не украшение.
 *
 * При выключенном движении знак не исчезает, а показывается неподвижно и так
 * же пропадает по времени. Просивший покоя просил не двигать картинку, а не
 * лишать себя сообщения.
 */
@Composable
fun CompanionSpark(
    trigger: Int,
    kind: SparkKind,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val motion = WolfyTheme.motion
    val progress = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        visible = true
        progress.snapTo(0f)
        if (motion.still) {
            progress.snapTo(SETTLED)
            delay(STILL_HOLD_MILLIS)
        } else {
            progress.animateTo(1f, motion.paced(motion.calm * 2))
        }
        visible = false
    }

    if (!visible) return
    val value = progress.value
    Canvas(modifier.size(if (kind == SparkKind.Idea) 26.dp else 34.dp)) {
        when (kind) {
            SparkKind.Idea -> drawIdea(colors.accent, value)
            SparkKind.Cheer -> drawCheer(colors.accent, value)
        }
    }
}

/**
 * Доля хода, на которой знак виден целиком.
 *
 * При выключенном движении показывается именно она: не начало, где ещё ничего
 * нет, и не конец, где уже всё погасло.
 */
private const val SETTLED = 0.45f

/** Сколько неподвижный знак висит, прежде чем убраться. */
private const val STILL_HOLD_MILLIS = 1_400L

/** Прозрачность: быстрый приход, долгая середина, спокойный уход. */
private fun fade(value: Float): Float = when {
    value < 0.15f -> value / 0.15f
    value > 0.75f -> ((1f - value) / 0.25f).coerceAtLeast(0f)
    else -> 1f
}

/** Лампочка с лучами: колба, цоколь и три штриха вокруг. */
private fun DrawScope.drawIdea(tint: Color, value: Float) {
    val alpha = fade(value)
    if (alpha <= 0f) return
    val stroke = Stroke(width = size.minDimension * 0.09f)
    val bulb = size.minDimension * 0.26f
    val middle = Offset(size.width / 2, size.height * 0.42f)

    drawCircle(tint, radius = bulb, center = middle, alpha = alpha, style = stroke)
    // Цоколь: две короткие полки под колбой.
    val neck = bulb * 0.62f
    for (step in 0..1) {
        val y = middle.y + bulb + stroke.width * (1.4f + step * 1.6f)
        drawLine(
            tint,
            Offset(middle.x - neck, y),
            Offset(middle.x + neck, y),
            strokeWidth = stroke.width,
            alpha = alpha,
        )
    }

    // Лучи расходятся по ходу: сначала прижаты к колбе, потом отходят.
    val spread = bulb * (1.45f + 0.55f * value)
    for (angle in listOf(-70f, -20f, 30f)) {
        val radians = angle * PI_OVER_180
        val direction = Offset(kotlin.math.cos(radians), kotlin.math.sin(radians))
        drawLine(
            tint,
            middle + direction * (bulb * 1.3f),
            middle + direction * spread,
            strokeWidth = stroke.width,
            alpha = alpha * 0.8f,
        )
    }
}

/** Звёздочки: три искры разного размера, всплывающие вверх. */
private fun DrawScope.drawCheer(tint: Color, value: Float) {
    val alpha = fade(value)
    if (alpha <= 0f) return
    // Каждая искра стартует со своим запозданием: одновременный старт читался
    // бы одной вспышкой, а не россыпью.
    val sparks = listOf(
        Triple(0.22f, 0.62f, 0.00f),
        Triple(0.55f, 0.30f, 0.18f),
        Triple(0.80f, 0.66f, 0.34f),
    )
    for ((x, y, lag) in sparks) {
        val own = ((value - lag) / (1f - lag)).coerceIn(0f, 1f)
        if (own <= 0f) continue
        val scale = kotlin.math.sin(own * kotlin.math.PI.toFloat()).coerceAtLeast(0f)
        val rise = size.height * 0.18f * own
        val radius = size.minDimension * 0.16f * scale
        if (radius <= 0f) continue
        translate(size.width * x, size.height * y - rise) {
            drawPath(starPath(radius), tint, alpha = alpha * scale)
        }
    }
}

/**
 * Четырёхлучевая искра: ромб с вогнутыми сторонами.
 *
 * Вогнутость обязательна. Ровный ромб на этом размере читается как точка, а
 * узнаваемой искру делает именно то, что лучи тоньше у основания.
 */
private fun starPath(radius: Float): Path = Path().apply {
    val waist = radius * 0.22f
    moveTo(0f, -radius)
    quadraticBezierTo(waist, -waist, radius, 0f)
    quadraticBezierTo(waist, waist, 0f, radius)
    quadraticBezierTo(-waist, waist, -radius, 0f)
    quadraticBezierTo(-waist, -waist, 0f, -radius)
    close()
}

private const val PI_OVER_180 = 0.017453292f

private operator fun Offset.times(factor: Float): Offset = Offset(x * factor, y * factor)

private operator fun Offset.plus(other: Offset): Offset = Offset(x + other.x, y + other.y)

