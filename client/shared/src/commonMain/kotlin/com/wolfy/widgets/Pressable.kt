package com.wolfy.widgets

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.settling

/**
 * Отклик на нажатие.
 *
 * Ripple в приложении выключен — расходящийся круг чужд бумажной странице, —
 * и без замены это выходило боком: между нажатием и тем, как экран сменится,
 * не происходило ровно ничего. Полсекунды тишины интерфейс отдаёт за
 * «подвисло», даже когда всё считается мгновенно.
 *
 * Здесь нажатое сжимается на три процента и тут же возвращается. Три, а не
 * десять: элемент должен ответить, а не подпрыгнуть. Пружина без отскока —
 * бумага не пружинит.
 *
 * Масштаб анимируется, а не переключается: мгновенный скачок в 0.97 читается
 * как дрожание, а не как нажатие.
 *
 * @param enabled выключенный элемент не откликается вовсе. Показать нажатие и
 *   ничего не сделать хуже, чем не показать: первое читатель понимает как
 *   поломку, второе — как «сюда нельзя».
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = WolfyTheme.motion
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED else 1f,
        animationSpec = motion.settling(
            damping = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "press",
    )

    return this
        .scale(scale)
        .clickable(
            interactionSource = interaction,
            // Подсветка своя — сжатие выше; штатная здесь была бы вторым
            // ответом на одно нажатие.
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

private const val PRESSED = 0.97f
