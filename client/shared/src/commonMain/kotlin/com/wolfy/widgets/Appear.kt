package com.wolfy.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import kotlinx.coroutines.delay

/** Появление частей экрана в общем темпе приложения. */
@Composable
fun Appear(
    order: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = WolfyTheme.motion
    val shown = remember { Animatable(if (motion.calm == 0) 1f else 0f) }
    LaunchedEffect(order, motion) {
        if (motion.stagger > 0) delay(order * motion.stagger.toLong())
        shown.animateTo(1f, tween(motion.calm, easing = Curves.Paper))
    }
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    Box(
        modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * rise
        },
    ) {
        content()
    }
}
