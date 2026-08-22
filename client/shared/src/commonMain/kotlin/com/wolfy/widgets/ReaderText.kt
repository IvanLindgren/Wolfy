package com.wolfy.widgets

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Text
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Token
import com.wolfy.theme.WolfyTheme

/**
 * Абзац книги, в котором можно тапнуть по слову.
 *
 * Разметка приходит из ядра: [ParsedText] несёт токены с позициями в тех же
 * единицах, которыми меряет строки Kotlin. Поэтому подсветка и попадание в
 * слово считаются напрямую по смещениям, без повторного разбора текста на
 * стороне интерфейса — иначе два разных разбора однажды разошлись бы, и
 * читатель нажимал бы на одно слово, а получал соседнее.
 *
 * @param saved начальные формы слов, уже сохранённых в колоду: они помечаются
 *   маркером прямо в тексте, чтобы читатель видел свой словарь на странице.
 * @param selected слово, по которому только что нажали — под ним держится фон,
 *   пока открыта карточка.
 */
@Composable
fun ReaderParagraph(
    parsed: ParsedText,
    modifier: Modifier = Modifier,
    style: TextStyle = WolfyTheme.typography.reader,
    saved: Set<String> = emptySet(),
    savedLemmaOf: (Token) -> String = { it.text.lowercase() },
    selected: Token? = null,
    onWordTap: (Token) -> Unit = {},
) {
    val colors = WolfyTheme.colors
    // Раскладка нужна, чтобы перевести точку касания в смещение внутри
    // строки. Пока абзац не отрисован, её нет — и тапы просто игнорируются.
    var layout by remember(parsed) { mutableStateOf<TextLayoutResult?>(null) }

    val text: AnnotatedString = remember(parsed, saved, selected, colors) {
        buildAnnotatedString {
            parsed.tokens.forEach { token ->
                val start = length
                append(token.text)

                if (!token.tappable) return@forEach

                val isSelected = selected != null &&
                    token.start == selected.start && token.end == selected.end
                val isSaved = savedLemmaOf(token) in saved

                when {
                    // Выбранное слово важнее сохранённого: читатель только что
                    // на него нажал и должен видеть, куда именно попал.
                    isSelected -> addStyle(
                        SpanStyle(background = colors.accent.copy(alpha = 0.22f)),
                        start,
                        length,
                    )
                    isSaved -> addStyle(
                        SpanStyle(background = colors.highlight),
                        start,
                        length,
                    )
                }
            }
        }
    }

    Text(
        text = text,
        style = style.copy(
            color = colors.ink,
            // Выключка по ширине — то, из-за чего страница читается как
            // газетная полоса, а не как лента сообщений.
            textAlign = TextAlign.Justify,
        ),
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(parsed) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    val token = parsed.tokenAt(offset) ?: return@detectTapGestures
                    if (token.tappable) {
                        onWordTap(token)
                    }
                }
            },
    )
}

/** Заголовок главы с линейками сверху и снизу, как в печатной полосе. */
@Composable
fun ChapterHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = WolfyTheme.typography.chapterTitle,
        color = WolfyTheme.colors.ink,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Цитата или эпиграф — своя врезка со сдвигом и курсивом. */
@Composable
fun ReaderQuote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = WolfyTheme.typography.translation.copy(color = WolfyTheme.colors.inkMuted),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Подстрочник над английской фразой.
 *
 * Мелкий перевод ставится ровно над своим словом — так его читают глазами, не
 * отрываясь от строки. Соответствие держится на том, что оба ряда собираются
 * из одного списка токенов.
 */
@Composable
fun InterlinearWord(
    original: String,
    translation: String?,
    partOfSpeechColor: androidx.compose.ui.graphics.Color?,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    Text(
        text = buildAnnotatedString {
            if (translation != null) {
                withStyleSafely(SpanStyle(color = colors.inkMuted)) { append(translation) }
                append("\n")
            }
            withStyleSafely(
                SpanStyle(
                    color = partOfSpeechColor ?: colors.ink,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (partOfSpeechColor != null) {
                        TextDecoration.Underline
                    } else {
                        TextDecoration.None
                    },
                ),
            ) { append(original) }
        },
        style = WolfyTheme.typography.caption,
        modifier = modifier,
    )
}

/**
 * `withStyle` из Compose принимает лямбду и возвращает её результат; здесь он
 * не нужен, а имя без результата читается яснее.
 */
private inline fun AnnotatedString.Builder.withStyleSafely(
    style: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val index = pushStyle(style)
    block()
    pop(index)
}
