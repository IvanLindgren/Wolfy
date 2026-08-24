package com.wolfy.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.wolfy.ffi.GrammarChunk
import com.wolfy.ffi.Token
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import kotlinx.coroutines.delay

/** Консервативное дерево ролей: корень-сказуемое и только найденные ветви. */
@Composable
fun SentenceTree(
    tokens: List<Token>,
    chunks: List<GrammarChunk>,
    modifier: Modifier = Modifier,
) {
    val root = chunks.firstOrNull { it.role == "predicate" } ?: return
    val branches = chunks.filterNot { it === root }
    val spacing = WolfyTheme.spacing

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TreeNode(textOf(tokens, root), root.title, root.tint, root = true)
        branches.forEachIndexed { index, chunk ->
            TreeBranch(index, textOf(tokens, chunk), chunk.title, chunk.tint)
        }
    }
}

@Composable
private fun TreeBranch(order: Int, text: String, role: String, tint: String) {
    val colors = WolfyTheme.colors
    val motion = WolfyTheme.motion
    val progress = remember { Animatable(if (motion.calm == 0) 1f else 0f) }
    LaunchedEffect(order, motion) {
        if (motion.stagger > 0) delay(order * motion.stagger.toLong())
        progress.animateTo(1f, tween(motion.calm, easing = Curves.Paper))
    }
    val branchColor = colors.partsOfSpeech.forTag(tint) ?: colors.rule
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(WolfyTheme.spacing.huge).height(WolfyTheme.spacing.huge)) {
            val end = progress.value
            drawLine(
                color = branchColor,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height * end),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
            if (end > 0.7f) {
                drawLine(
                    color = branchColor,
                    start = Offset(size.width / 2f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
        }
        if (progress.value > 0.85f) TreeNode(text, role, tint, root = false)
    }
}

@Composable
private fun TreeNode(text: String, role: String, tint: String, root: Boolean) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val tone = colors.partsOfSpeech.forTag(tint) ?: colors.inkMuted
    Column(
        Modifier
            .then(
                if (root) Modifier.background(colors.inverse, RoundedCornerShape(spacing.large))
                else Modifier.border(spacing.rule, tone, RoundedCornerShape(spacing.large)),
            )
            .padding(horizontal = spacing.large, vertical = spacing.small),
    ) {
        Text(text, style = WolfyTheme.typography.button, color = if (root) colors.onInverse else colors.ink)
        Text(role, style = WolfyTheme.typography.caption, color = if (root) colors.onInverse else tone)
    }
}

private fun textOf(tokens: List<Token>, chunk: GrammarChunk): String =
    (chunk.start until chunk.end)
        .mapNotNull(tokens::getOrNull)
        .joinToString("") { it.text }
        .trim()
