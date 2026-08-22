package com.wolfy.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.wolfy.theme.WolfyTheme

/**
 * Обложка книги, набранная, а не нарисованная.
 *
 * У книги из файла обложки чаще всего нет: txt её не хранит вовсе, а в epub
 * она бывает у одной книги из трёх. Показывать вместо неё серый прямоугольник
 * с надписью «нет обложки» — значит превратить библиотеку в список ошибок.
 *
 * Поэтому обложка набирается из того, что есть: название антиквой, автор
 * мелким шрифтом, фон одного из четырёх цветов. Цвет выбирается по названию, а
 * не случайно, — обложка обязана быть одной и той же при каждом запуске, иначе
 * читатель перестаёт узнавать свои книги в лицо.
 */
@Composable
fun BookCover(
    title: String,
    author: String?,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val typography = WolfyTheme.typography

    // Четыре цвета из макета: чернильный, сигнальный красный, две степени
    // серого. Больше не нужно — библиотека должна выглядеть подборкой, а не
    // палитрой.
    val palette = listOf(
        Color(0xFF17140F) to Color(0xFFF4F4F1),
        Color(0xFFB83A2A) to Color(0xFFF4F4F1),
        Color(0xFF4A4A46) to Color(0xFFF4F4F1),
        Color(0xFF9A968C) to Color(0xFF17140F),
    )
    val (background, foreground) = palette[fingerprint(title) % palette.size]

    Box(
        modifier
            .aspectRatio(0.68f)
            .background(background, RoundedCornerShape(spacing.tight))
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.tight)),
    ) {
        // Тонкая линия вдоль левого края: она превращает прямоугольник в
        // книгу, стоящую к читателю лицом.
        Box(
            Modifier
                .fillMaxHeight()
                .width(spacing.rule)
                .padding(vertical = spacing.small)
                .background(foreground.copy(alpha = 0.3f))
                .align(Alignment.CenterStart)
                .padding(start = spacing.small),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(spacing.medium),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = typography.bookTitle,
                color = foreground,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    style = typography.caption,
                    color = foreground.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Устойчивое число по строке.
 *
 * `hashCode` для этого не годится: он не обещает одинакового значения между
 * запусками виртуальной машины, а обложка обязана быть одной и той же всегда.
 */
private fun fingerprint(text: String): Int {
    var value = 7
    for (character in text) {
        value = (value * 31 + character.code) and 0x7FFFFFF
    }
    return value
}

