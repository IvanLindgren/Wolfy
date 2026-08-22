package com.wolfy.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

/**
 * Отсутствие подсветки нажатия.
 *
 * Material рисует расходящийся круг — жест материальной поверхности, которая
 * отзывается на прикосновение. Страница книги ведёт себя иначе: она бумажная,
 * и круги по ней не расходятся. Нажатие в Wolfy показывается лёгким
 * масштабированием элемента, а слово в тексте — сменой фона под ним.
 */
internal object NoIndication : IndicationNodeFactory {
    private class Node : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() = drawContent()
    }

    override fun create(interactionSource: InteractionSource): DelegatableNode = Node()

    override fun hashCode(): Int = -1

    override fun equals(other: Any?): Boolean = other === this
}
