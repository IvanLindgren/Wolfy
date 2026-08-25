package com.wolfy.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.pressable

/**
 * Полоса помощи вниманию: отрезок и ведущая строка.
 *
 * Появляется, только если включено хотя бы одно из двух, и занимает одну
 * строку. Панель управления вниманием, которая сама требует внимания,
 * противоречит себе — поэтому здесь нет ни рамок, ни заливок, только тонкая
 * линейка прогресса и подписи.
 *
 * Считается тем же, чем меряет отрезок ядро, — словами. Слова читатель
 * переводит во время сам и точнее, чем это сделали бы мы: скорость чтения у
 * каждого своя, и обещание «пять минут» оказалось бы неверным ровно для того,
 * кто читает медленнее.
 */
@Composable
fun AttentionBar(
    state: ReaderState,
    activeBlock: Int,
    pacing: Boolean,
    pacerWpm: Int,
    onPace: (Boolean) -> Unit,
    onNextSegment: (Int) -> Unit,
    onStopSegments: () -> Unit,
) {
    val segment = state.segment
    if (segment == null && pacerWpm <= 0) return

    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    // Место читателя — первый токен видимого блока. Точнее не нужно: отрезок
    // отвечает на вопрос «сколько ещё», а не «на каком я слове».
    val at = state.blocks.getOrNull(activeBlock)?.firstToken ?: -1
    val done = segment != null && at >= 0 && at >= segment.end
    val passed = if (segment == null || at < 0) {
        0
    } else {
        wordsBetween(state.blocks, segment.start, minOf(at, segment.end))
    }
    val share = if (segment != null && segment.words > 0) {
        (passed.toFloat() / segment.words).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageMargin, vertical = spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (segment != null && !done) {
            Text(
                text = "Подход",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(spacing.rule * 2)
                    .background(colors.rule, RoundedCornerShape(spacing.hair)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(share)
                        .height(spacing.rule * 2)
                        .background(colors.accent, RoundedCornerShape(spacing.hair)),
                )
            }
            Text(
                text = "$passed из ${segment.words} слов",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (segment != null && done) {
            Text(
                text = if (segment.last) "Глава дочитана" else "Отрезок пройден",
                style = WolfyTheme.typography.button,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (!segment.last) {
                Text(
                    text = "ещё один",
                    style = WolfyTheme.typography.button,
                    color = colors.accent,
                    modifier = Modifier.pressable { onNextSegment(segment.end) },
                )
            }
            Text(
                text = "хватит",
                style = WolfyTheme.typography.button,
                color = colors.inkMuted,
                modifier = Modifier.pressable(onClick = onStopSegments),
            )
        }

        if (pacerWpm > 0) {
            if (segment == null) Box(Modifier.weight(1f))
            Text(
                text = if (pacing) "пауза" else "вести · $pacerWpm",
                style = WolfyTheme.typography.button,
                color = if (pacing) colors.accent else colors.inkMuted,
                modifier = Modifier
                    .width(WolfyTheme.spacing.huge * 2)
                    .pressable { onPace(!pacing) },
            )
        }
    }
}

/**
 * Сколько слов между двумя токенами главы.
 *
 * Считается по блокам, а не по главе целиком: у читалки на руках уже разобранные
 * абзацы, и второй проход по всей главе ради того же числа был бы лишней
 * работой на каждый кадр прокрутки.
 */
private fun wordsBetween(blocks: List<ReaderBlock>, from: Int, to: Int): Int {
    if (to <= from) return 0
    var words = 0
    for (block in blocks) {
        val parsed = block.parsed ?: continue
        if (block.firstToken < 0) continue
        val blockEnd = block.firstToken + parsed.tokens.size
        if (blockEnd <= from || block.firstToken >= to) continue
        parsed.tokens.forEachIndexed { index, token ->
            val global = block.firstToken + index
            if (global in from until to && token.kind == "word") words += 1
        }
    }
    return words
}
