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
import com.wolfy.widgets.pressable

/** Кнопка появляется только после полной загрузки и проверки пакета. */
@Composable
internal fun UpdateReadyButton(
    controller: AppUpdateController,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val ready = state as? AppUpdateState.Ready
    AnimatedVisibility(
        visible = ready != null,
        modifier = modifier.zIndex(20f),
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
    ) {
        val colors = WolfyTheme.colors
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(colors.inverse, CircleShape)
                .border(WolfyTheme.spacing.rule, colors.rule, CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = "Перезапустить и установить Wolfy ${ready?.version.orEmpty()}"
                }
                .pressable(onClick = onRestart),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(25.dp)) {
                val stroke = size.minDimension * 0.105f
                val inset = stroke
                drawArc(
                    color = colors.onInverse,
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
                drawPath(arrow, colors.onInverse)
            }
        }
    }
}
