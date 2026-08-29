package com.wolfy.widgets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.still

/**
 * Полоса набора: чем занят экран, пока идёт ожидание.
 *
 * Ожидание ответа модели длится от пяти секунд до минуты, и всё это время
 * читатель видел неподвижную строку. Люди в такой ситуации нажимают
 * «Отменить» — не от долготы, а от неизвестности; а отмена стоила дневного
 * запроса из десяти.
 *
 * Форма выбрана под остальной интерфейс: не круг, который крутится, а тонкая
 * линейка, по которой идёт набранный кусок — линейка в газете и так везде.
 * Заливка не притворяется прогрессом: доли выполнения у запроса к модели нет,
 * и рисовать её было бы враньём.
 *
 * При выключенном движении бесконечный ход не заводится вовсе — остаётся
 * ровная линейка. Попросивший покоя просил в том числе и о том, чтобы на
 * экране ничего не двигалось само.
 */
@Composable
fun TypesettingLine(modifier: Modifier = Modifier) {
    val colors = WolfyTheme.colors
    val motion = WolfyTheme.motion

    val travel = if (motion.still) {
        null
    } else {
        rememberInfiniteTransition(label = "typesetting").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(motion.flight * 2, easing = Curves.Paper),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "travel",
        ).value
    }

    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(colors.rule, size = size)
        if (travel == null) return@Canvas
        // Кусок в треть линейки ходит от края до края. Ширина постоянная:
        // растягивающийся отрезок читался бы как прогресс, которого нет.
        val run = size.width * 0.34f
        drawRect(
            color = colors.accent,
            topLeft = Offset((size.width - run) * travel, 0f),
            size = Size(run, size.height),
        )
    }
}
