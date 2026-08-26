package com.wolfy.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import kotlin.math.absoluteValue
import kotlin.math.min

@Immutable
data class Flight(
    val id: Long,
    val from: Rect,
    val to: Rect,
    val target: String,
    val text: String,
)

@Stable
class FlightController {
    private val targets = HashMap<String, Rect>()
    private var nextId = 1L

    var flight by mutableStateOf<Flight?>(null)
        private set

    /** Сессионный счётчик: новое с последнего посещения раздела. */
    val arrivals = mutableStateMapOf<String, Int>()

    fun place(id: String, bounds: Rect) {
        targets[id] = bounds
    }

    fun forget(id: String) {
        targets.remove(id)
    }

    fun send(from: Rect, to: String, text: String): Boolean {
        val landing = targets[to] ?: return false
        arrivals[to] = (arrivals[to] ?: 0) + 1
        flight = Flight(nextId++, from, landing, to, text)
        return true
    }

    fun landed(id: Long) {
        if (flight?.id == id) flight = null
    }

    fun clearArrivals(target: String) {
        arrivals.remove(target)
    }
}

val LocalFlight = staticCompositionLocalOf { FlightController() }

@Composable
fun Modifier.flightTarget(id: String): Modifier {
    val controller = LocalFlight.current
    DisposableEffect(controller, id) { onDispose { controller.forget(id) } }
    return onGloballyPositioned { controller.place(id, it.boundsInRoot()) }
}

@Composable
fun rememberLaunchPad(): Pair<Modifier, () -> Rect?> {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    return Modifier.onGloballyPositioned { bounds = it.boundsInRoot() } to { bounds }
}

@Composable
fun FlightOverlay(modifier: Modifier = Modifier) {
    val controller = LocalFlight.current
    val motion = WolfyTheme.motion
    val flight = controller.flight ?: return
    val progress = remember(flight.id) { Animatable(if (motion.flight == 0) 1f else 0f) }

    // Переключение reduced motion завершает уже летящую карточку сразу, а не
    // только влияет на следующий запуск анимации.
    LaunchedEffect(flight.id, motion) {
        progress.animateTo(1f, tween(motion.flight, easing = Curves.Toss))
        controller.landed(flight.id)
    }

    val point = along(flight.from.center, flight.to.center, progress.value, LIFT)
    Box(modifier.fillMaxSize()) {
        Text(
            text = flight.text,
            style = WolfyTheme.typography.bookTitle,
            color = WolfyTheme.colors.ink,
            modifier = Modifier.graphicsLayer {
                translationX = point.x - size.width / 2f
                translationY = point.y - size.height / 2f
                val scale = scaleAt(progress.value)
                scaleX = scale
                scaleY = scale
                alpha = if (progress.value < 0.8f) 1f else (1f - progress.value) / 0.2f
            },
        )
    }
}

private fun along(from: Offset, to: Offset, t: Float, lift: Float): Offset {
    val control = Offset(
        x = (from.x + to.x) / 2f,
        y = min(from.y, to.y) - (to.y - from.y).absoluteValue * lift,
    )
    val rest = 1f - t
    return Offset(
        x = rest * rest * from.x + 2f * rest * t * control.x + t * t * to.x,
        y = rest * rest * from.y + 2f * rest * t * control.y + t * t * to.y,
    )
}

private fun scaleAt(t: Float): Float = if (t < 0.12f) {
    1f + t / 0.12f * 0.06f
} else {
    1.06f - (t - 0.12f) / 0.88f * 0.66f
}

private const val LIFT = 0.26f
