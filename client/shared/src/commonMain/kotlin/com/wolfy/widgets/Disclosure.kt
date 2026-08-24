package com.wolfy.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme

/**
 * Раскрывающийся раздел «подробности».
 *
 * Карточка обязана отвечать на главный вопрос сразу: перевод, толкование,
 * грамматические признаки. Всё остальное — строение слова, частотность,
 * найденные правила — интересно не каждому и потому прячется сюда, за одну
 * явную кнопку вместо длинного скролла.
 *
 * Вульфи сидит на кнопке не для украшения: закрытая карточка выглядит как
 * список фактов, открытая — как разговор, и смена его настроения отмечает
 * эту разницу без единого слова.
 */
@Composable
fun Disclosure(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val motion = WolfyTheme.motion
    var open by rememberSaveable { mutableStateOf(false) }

    val turn by animateFloatAsState(
        targetValue = if (open) -8f else 0f,
        animationSpec = if (motion.calm == 0) {
            androidx.compose.animation.core.snap()
        } else {
            tween(motion.calm, easing = Curves.Paper)
        },
        label = "disclosure wolfy",
    )

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.paper, RoundedCornerShape(spacing.medium))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.medium)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable { open = !open }
                .padding(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.hair)) {
                Text(
                    text = if (open) "Скрыть подробности" else label,
                    style = WolfyTheme.typography.button,
                    color = colors.ink,
                )
                Text(
                    text = if (open) "Оставить только главное" else hint,
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
            WolfySticker(
                sticker = if (open) Sticker.Celebrate else Sticker.Thinking,
                size = 48.dp,
                modifier = Modifier.graphicsLayer { rotationZ = turn },
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically(
                animationSpec = tween(motion.calm, easing = Curves.Paper),
            ) + fadeIn(tween(motion.calm)),
            exit = shrinkVertically(
                animationSpec = tween(motion.quick, easing = Curves.Paper),
            ) + fadeOut(tween(motion.quick)),
        ) {
            Column(
                Modifier
                    .padding(start = spacing.small, end = spacing.small, bottom = spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Rule()
                content()
            }
        }
    }
}
