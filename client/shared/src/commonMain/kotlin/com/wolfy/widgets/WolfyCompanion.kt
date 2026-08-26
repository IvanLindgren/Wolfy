package com.wolfy.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wolfy.resources.Res
import com.wolfy.resources.wolfy_card
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.settling
import com.wolfy.theme.still
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Вульфи в карточке — и его можно погладить.
 *
 * Единственное место в приложении, которое ничего не делает полезного, и это
 * намеренно. Карточка слова — момент, когда чтение остановилось: читатель
 * споткнулся о незнакомое слово и ждёт перевода из сети. Здесь ему и место:
 * пока едет перевод, есть кого потрепать по загривку.
 *
 * Гладят движением, а не нажатием. Нажатие — это кнопка, а ласка — это
 * проведённая рука: Вульфи заваливается в сторону, куда его гладят, и
 * возвращается пружиной, когда руку убрали. Каждое движение туда-обратно он
 * считает и на третьем начинает пускать сердечки.
 */
@Composable
fun WolfyCompanion(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
) {
    val colors = WolfyTheme.colors
    val scope = rememberCoroutineScope()

    val tilt = remember { Animatable(0f) }
    val squish = remember { Animatable(1f) }
    var strokes by remember { mutableIntStateOf(0) }
    val hearts = remember { mutableStateListOf<Int>() }
    var nextHeart by remember { mutableIntStateOf(0) }

    // Дыхание: Вульфи живой и когда его не трогают. Три процента за две
    // секунды — на грани заметности, как и огонёк серии.
    //
    // В тишине он не дышит вовсе. Движение, которое идёт само и не
    // прекращается, — первое, от чего избавляются, включая «уменьшить
    // движение»; отвечать на прикосновение это ему не мешает.
    val motion = WolfyTheme.motion
    val breath = if (motion.still) {
        1f
    } else {
        rememberInfiniteTransition(label = "wolfy").animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        ).value
    }

    // Направление предыдущего движения и сколько прошли в нём. Перемена
    // направления — это и есть одно поглаживание; считать по расстоянию
    // нельзя, иначе одно длинное движение через весь экран засчиталось бы за
    // десяток.
    val stroke = remember { StrokeCounter() }

    Box(
        modifier
            .size(size)
            // Ключ — темп: в тишине возврат должен быть мгновенным, а
            // захваченное лямбдой значение само не меняется.
            .pointerInput(motion) {
                detectDragGestures(
                    onDragEnd = {
                        stroke.reset()
                        scope.launch {
                            tilt.animateTo(0f, motion.settling(Spring.DampingRatioMediumBouncy))
                        }
                        scope.launch { squish.animateTo(1f, motion.settling()) }
                    },
                ) { change, drag ->
                    change.consume()
                    if (stroke.push(drag.x)) {
                        strokes += 1
                        if (strokes >= HEARTS_AFTER) {
                            hearts.add(nextHeart++)
                        }
                    }
                    scope.launch {
                        tilt.snapTo((tilt.value + drag.x * 0.35f).coerceIn(-TILT, TILT))
                        squish.snapTo(0.94f)
                    }
                }
            }
            .pointerInput(motion) {
                detectTapGestures {
                    // Тычок — не ласка, но и без ответа его оставлять нельзя:
                    // короткий кивок говорит, что здесь вообще что-то живое.
                    scope.launch {
                        squish.animateTo(0.9f, motion.settling(stiffness = Spring.StiffnessHigh))
                        squish.animateTo(1f, motion.settling(Spring.DampingRatioMediumBouncy))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.wolfy_card),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = tilt.value
                    scaleX = squish.value * breath
                    scaleY = squish.value * breath
                    // Опора внизу: волк заваливается, стоя на месте, а не
                    // вращается вокруг собственной середины.
                    transformOrigin = TransformOrigin(0.5f, 0.9f)
                },
        )

        hearts.forEach { id ->
            key(id) {
                FloatingHeart(color = colors.accent, onDone = { hearts.remove(id) })
            }
        }
    }
}

/**
 * Счётчик поглаживаний.
 *
 * Одно поглаживание — движение в одну сторону не короче порога, за которым
 * последовало движение в обратную. Порог нужен, чтобы дрожание руки на месте
 * не считалось лаской.
 */
private class StrokeCounter {
    private var direction = 0
    private var travelled = 0f

    fun push(dx: Float): Boolean {
        if (dx == 0f) return false
        val sign = if (dx > 0) 1 else -1
        if (sign != direction) {
            val counted = direction != 0 && abs(travelled) >= MIN_TRAVEL
            direction = sign
            travelled = dx
            return counted
        }
        travelled += dx
        return false
    }

    fun reset() {
        direction = 0
        travelled = 0f
    }

    private companion object {
        /** Меньше — это уже дрожание руки, а не движение. */
        const val MIN_TRAVEL = 12f
    }
}

/** Сердечко, всплывающее над Вульфи и тающее. */
@Composable
private fun FloatingHeart(color: Color, onDone: () -> Unit) {
    val rise = remember { Animatable(0f) }

    val motion = WolfyTheme.motion
    LaunchedEffect(Unit) {
        // Вдвое дольше самого долгого хода интерфейса: сердечко должно успеть
        // всплыть и растаять, а не мигнуть. Число берётся из темпа, поэтому в
        // тишине оно исчезает сразу — как и всё остальное.
        rise.animateTo(1f, motion.paced(motion.flight * 2))
        onDone()
    }

    Canvas(
        Modifier
            .size(14.dp)
            .graphicsLayer {
                translationY = -size.height * 3f * rise.value
                translationX = size.width * 0.8f
                alpha = 1f - rise.value
                scaleX = 0.6f + rise.value * 0.6f
                scaleY = 0.6f + rise.value * 0.6f
            },
    ) {
        val heart = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.95f)
            cubicTo(
                -size.width * 0.25f, size.height * 0.5f,
                size.width * 0.15f, -size.height * 0.15f,
                size.width * 0.5f, size.height * 0.3f,
            )
            cubicTo(
                size.width * 0.85f, -size.height * 0.15f,
                size.width * 1.25f, size.height * 0.5f,
                size.width * 0.5f, size.height * 0.95f,
            )
            close()
        }
        drawPath(heart, color)
    }
}

/** Наклон в градусах, дальше которого Вульфи не заваливается. */
private const val TILT = 9f

/** Со скольких поглаживаний летят сердечки. */
private const val HEARTS_AFTER = 3
