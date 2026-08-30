package com.wolfy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wolfy.platform.AppUpdateController
import com.wolfy.platform.AppUpdateState
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.settling
import com.wolfy.theme.paced
import com.wolfy.widgets.pressable
import kotlinx.coroutines.launch

/**
 * Ненавязчивое предложение загрузить обновление либо перезапустить его.
 *
 * ## Почему отказ тоже виден
 *
 * Кнопка показывалась только на `Available` и `Ready`. Любой отказ — пакет
 * повреждён, установщик не нашёлся, сервер обновлений недоступен — переводил
 * состояние в `Failed`, и кнопка просто пропадала. Со стороны читателя это
 * выглядело так: обновление предложили, он нажал, оно исчезло. Приложение при
 * этом знало причину и молчало о ней.
 *
 * Молчащий отказ хуже видимого: видимый можно повторить, а про молчащий даже
 * непонятно, было ли что нажимать. Теперь кнопка остаётся и на `Failed`, только
 * не зовёт перезапускаться, а предлагает попробовать ещё раз, и причина
 * доезжает до озвучки экрана целиком.
 */
@Composable
internal fun UpdateReadyButton(
    controller: AppUpdateController,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val ready = state as? AppUpdateState.Ready
    val available = state as? AppUpdateState.Available
    val failed = state as? AppUpdateState.Failed
    val version = ready?.version ?: available?.version
    val motion = WolfyTheme.motion
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = version != null || failed != null,
        modifier = modifier.zIndex(20f),
        enter = fadeIn(motion.paced(motion.quick)) + scaleIn(motion.settling(), initialScale = 0.8f),
        exit = fadeOut(motion.paced(motion.instant)) + scaleOut(motion.paced(motion.quick), targetScale = 0.8f),
    ) {
        val colors = WolfyTheme.colors
        Box(
            modifier = Modifier
                .size(48.dp)
                // Отказ повторяют проверкой, а не установкой: ставить нечего,
                // пакета нет. Проверка сама вернёт состояние в Available.
                .pressable(onClick = { if (failed != null) scope.launch { controller.checkNow() } else onRestart() })
                .background(if (failed != null) colors.paper else colors.inverse, CircleShape)
                .border(WolfyTheme.spacing.rule, colors.rule, CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = when {
                        // Причина целиком, а не «что-то пошло не так»: читать её
                        // будет тот, кто уже нажал и не увидел результата.
                        failed != null -> "Обновление не установилось: ${failed.reason}. Нажмите, чтобы проверить ещё раз"
                        ready != null -> "Перезапустить и установить Wolfy ${ready.version}"
                        else -> "Загрузить обновление Wolfy ${available?.version.orEmpty()}"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (failed != null) colors.inkMuted else colors.onInverse
            Canvas(Modifier.size(25.dp)) {
                val stroke = size.minDimension * 0.105f
                val inset = stroke
                drawArc(
                    color = ink,
                    startAngle = -55f,
                    sweepAngle = 285f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                val tip = Offset(size.width * 0.90f, size.height * 0.24f)
                val arrow = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(size.width * 0.61f, size.height * 0.20f)
                    lineTo(size.width * 0.80f, size.height * 0.43f)
                    close()
                }
                drawPath(arrow, ink)
            }
        }
    }
}
