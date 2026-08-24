package com.wolfy.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.wolfy.ffi.GrammarChunk
import com.wolfy.ffi.GrammarMarker
import com.wolfy.ffi.Token
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import kotlinx.coroutines.delay

/** Фраза, разрезанная Rust-ядром на роли, с подсвеченными опорами правил. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhraseBlocks(
    tokens: List<Token>,
    chunks: List<GrammarChunk>,
    markers: List<GrammarMarker>,
    modifier: Modifier = Modifier,
) {
    if (chunks.isEmpty()) return
    val spacing = WolfyTheme.spacing
    val motion = WolfyTheme.motion
    val markerLight = remember { Animatable(if (motion.calm == 0) 1f else 0f) }
    LaunchedEffect(chunks, markers, motion) {
        if (motion.stagger > 0) delay(chunks.size * motion.stagger.toLong())
        markerLight.animateTo(1f, tween(motion.calm, easing = Curves.Paper))
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.small),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.medium),
    ) {
        chunks.forEachIndexed { index, chunk ->
            Appear(index) {
                PhraseChunk(tokens, chunk, markers, markerLight.value)
            }
        }
    }
}

@Composable
private fun PhraseChunk(
    tokens: List<Token>,
    chunk: GrammarChunk,
    markers: List<GrammarMarker>,
    markerAlpha: Float,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val motion = WolfyTheme.motion
    val line = remember(chunk) { Animatable(if (motion.quick == 0) 1f else 0f) }
    LaunchedEffect(chunk, motion) {
        line.animateTo(1f, tween(motion.quick, easing = Curves.Paper))
    }
    val roleColor = colors.partsOfSpeech.forTag(chunk.tint) ?: colors.inkMuted

    Column {
        Text(
            text = buildAnnotatedString {
                (chunk.start until chunk.end).forEach { tokenIndex ->
                    val token = tokens.getOrNull(tokenIndex) ?: return@forEach
                    val marker = markers.firstOrNull { it.token == tokenIndex }
                    if (marker == null || marker.from >= marker.to) {
                        append(token.text)
                    } else {
                        val from = byteOffsetToCharIndex(token.text, marker.from)
                        val to = byteOffsetToCharIndex(token.text, marker.to).coerceAtLeast(from)
                        append(token.text.substring(0, from))
                        withStyle(
                            SpanStyle(
                                background = colors.ruleFamilies
                                    .forFamily(marker.rule)
                                    .copy(alpha = markerAlpha),
                            ),
                        ) {
                            append(token.text.substring(from, to))
                        }
                        append(token.text.substring(to))
                    }
                }
            },
            style = WolfyTheme.typography.bookTitle,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = spacing.tight),
        )
        Box(
            Modifier
                .fillMaxWidth(line.value)
                .height(spacing.hair)
                .background(roleColor, RoundedCornerShape(spacing.hair)),
        )
        Text(
            text = chunk.title,
            style = WolfyTheme.typography.caption,
            color = roleColor,
            modifier = Modifier.padding(horizontal = spacing.tight),
        )
    }
}

/** Rust отдаёт байты UTF-8; Kotlin режет строку индексами UTF-16. */
private fun byteOffsetToCharIndex(text: String, byteOffset: Int): Int {
    if (byteOffset <= 0) return 0
    for (index in 1..text.length) {
        if (text.substring(0, index).encodeToByteArray().size >= byteOffset) return index
    }
    return text.length
}
