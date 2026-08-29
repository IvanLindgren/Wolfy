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
import androidx.compose.ui.semantics.Role
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
 * Порядок в цепочке модификаторов имеет значение и это единственная тонкость,
 * которую надо помнить. Сжатие — это `graphicsLayer`, а он преобразует только
 * то, что идёт по цепочке дальше. Значит `pressable` ставится **до** `clip`,
 * `background` и `border`:
 *
 * ```
 * Modifier.fillMaxWidth().pressable(onClick = ...).background(...).padding(...)
 * ```
 *
 * При обратном порядке фон рисуется снаружи слоя и не сжимается — сжимается
 * одна подпись внутри плашки. Это читается не как нажатие, а как дрожание
 * текста, то есть ровно как поломка, ради невидимости которой всё и писалось.
 * Размеры и `weight` можно ставить как до, так и после: они на слой не влияют.
 *
 * @param enabled выключенный элемент не откликается вовсе. Показать нажатие и
 *   ничего не сделать хуже, чем не показать: первое читатель понимает как
 *   поломку, второе — как «сюда нельзя».
 * @param role чем элемент представляется озвучке экрана. По умолчанию кнопка:
 *   почти всё нажимаемое в Wolfy — это `Text` с этим модификатором, и без роли
 *   TalkBack читал такую подпись как обычный текст, мимо которого можно пройти.
 *   Переключателю и вкладке роль передают явно.
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
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
            role = role,
            onClick = onClick,
        )
}

private const val PRESSED = 0.97f
