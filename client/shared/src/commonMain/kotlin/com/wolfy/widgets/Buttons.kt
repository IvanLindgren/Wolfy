package com.wolfy.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wolfy.theme.WolfyTheme

/** Главное действие экрана: одна тёмная кнопка во всю ширину. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Box(
        modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .background(if (enabled) colors.inverse else colors.rule, RoundedCornerShape(spacing.huge))
            .padding(vertical = spacing.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = WolfyTheme.typography.button,
            color = if (enabled) colors.onInverse else colors.inkMuted,
        )
    }
}
