package com.wolfy.widgets

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Перетаскивание внутри экрана.
 *
 * Своё, а не системное. Системное перетаскивание Compose устроено по-разному
 * на Android и на настольной системе: там разные типы переносимых данных, и
 * общего кода из них не выходит. А нужно здесь немногое — потащить книгу и
 * положить её на полку, — и это немногое пишется одинаково для обеих платформ.
 *
 * Устройство простое: цели записывают свои границы, источник ведёт палец, при
 * отпускании ищется цель под пальцем. Всё состояние — в одном объекте, потому
 * что перетаскивание по природе своей связывает два далёких места экрана, и
 * растащить его по компонентам значит потерять из виду, кто на кого падает.
 */
@Stable
class DragBoard {
    /** Что тащат сейчас. `null` — ничего. */
    var dragged by mutableStateOf<Dragged?>(null)
        private set

    /** Цель под пальцем: по ней подсвечивается место, куда упадёт книга. */
    var hovered by mutableStateOf<String?>(null)
        private set

    private val targets = mutableStateMapOf<String, Rect>()

    fun registerTarget(key: String, bounds: Rect) {
        targets[key] = bounds
    }

    fun forgetTarget(key: String) {
        targets.remove(key)
    }

    internal fun start(id: String, label: String, at: Offset) {
        dragged = Dragged(id = id, label = label, position = at)
        hovered = targetAt(at)
    }

    internal fun moveTo(at: Offset) {
        val current = dragged ?: return
        dragged = current.copy(position = at)
        hovered = targetAt(at)
    }

    /** Отпускание. Возвращает цель, на которую упало, или `null`. */
    internal fun release(): String? {
        val target = hovered
        dragged = null
        hovered = null
        return target
    }

    internal fun cancel() {
        dragged = null
        hovered = null
    }

    private fun targetAt(point: Offset): String? =
        targets.entries.firstOrNull { (_, bounds) -> bounds.contains(point) }?.key
}

/** Перетаскиваемое — то, что видно под пальцем. */
data class Dragged(
    val id: String,
    /** Подпись на «призраке»: читатель должен видеть, что именно тащит. */
    val label: String,
    /** Положение пальца в координатах окна. */
    val position: Offset,
)

/**
 * Делает элемент перетаскиваемым.
 *
 * Начинается по долгому нажатию, а не сразу: короткое нажатие открывает книгу,
 * и если тащить начиналось бы с первого движения пальца, открыть книгу
 * прокруткой списка стало бы невозможно.
 */
fun Modifier.dragSource(
    board: DragBoard,
    id: String,
    label: String,
    onDropped: (target: String) -> Unit,
): Modifier = composedDragSource(board, id, label, onDropped)

private fun Modifier.composedDragSource(
    board: DragBoard,
    id: String,
    label: String,
    onDropped: (String) -> Unit,
): Modifier {
    var origin = Offset.Zero
    var pointer = Offset.Zero

    return this
        .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
        .pointerInput(id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start ->
                    // Палец в координатах окна: цели знают свои границы именно
                    // в них, а внутри элемента координаты начинаются заново.
                    pointer = origin + start
                    board.start(id, label, pointer)
                },
                onDrag = { change, amount ->
                    change.consume()
                    pointer += amount
                    board.moveTo(pointer)
                },
                onDragEnd = { board.release()?.let(onDropped) },
                onDragCancel = { board.cancel() },
            )
        }
}

/** Отмечает область как место, куда можно положить. */
fun Modifier.dropTarget(board: DragBoard, key: String): Modifier =
    this.onGloballyPositioned { board.registerTarget(key, it.boundsInWindow()) }
