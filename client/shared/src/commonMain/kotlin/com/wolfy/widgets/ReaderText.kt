package com.wolfy.widgets

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
 * @param selection выделенный кусок абзаца в тех же смещениях, что и токены.
 * @param onPhrase зовётся, пока палец ведут по строке: по нему держится
 *   подсветка выделения.
 * @param onPhraseDone зовётся, когда палец подняли, — здесь открывается
 *   карточка фразы.
 */
@Composable
fun ReaderParagraph(
    parsed: ParsedText,
    modifier: Modifier = Modifier,
    style: TextStyle = WolfyTheme.typography.reader,
    saved: Set<String> = emptySet(),
    savedLemmaOf: (Token) -> String = { it.text.lowercase() },
    selected: Token? = null,
    selection: IntRange? = null,
    onWordTap: (Token) -> Unit = {},
    onPhrase: (IntRange) -> Unit = {},
    onPhraseDone: (IntRange) -> Unit = {},
) {
    val colors = WolfyTheme.colors
    // Раскладка нужна, чтобы перевести точку касания в смещение внутри
    // строки. Пока абзац не отрисован, её нет — и тапы просто игнорируются.
    var layout by remember(parsed) { mutableStateOf<TextLayoutResult?>(null) }

    val text: AnnotatedString = remember(parsed, saved, selected, selection, colors) {
        buildAnnotatedString {
            parsed.tokens.forEach { token ->
                val start = length
                append(token.text)

                if (!token.tappable) return@forEach

                val isSelected = selected != null &&
                    token.start == selected.start && token.end == selected.end
                val isSaved = savedLemmaOf(token) in saved
                val inPhrase = selection != null &&
                    token.start >= selection.first && token.end <= selection.last + 1

                when {
                    // Выделение фразы поверх всего: пока палец ведут по
                    // строке, читатель должен видеть ровно то, что он взял.
                    inPhrase -> addStyle(
                        SpanStyle(background = colors.accent.copy(alpha = 0.18f)),
                        start,
                        length,
                    )
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
            }
            // Выделение фразы. Порог берётся горизонтальный, а не общий, и это
            // главное здесь: вертикальное движение по абзацу — это прокрутка
            // книги, и перехватывать его нельзя. Проведённая же по строке рука
            // ничего, кроме выделения, значить не может.
            .pointerInput(parsed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val started = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    } ?: return@awaitEachGesture

                    val result = layout ?: return@awaitEachGesture
                    val anchor = result.getOffsetForPosition(down.position)
                    var range = parsed.spanBetween(
                        anchor,
                        result.getOffsetForPosition(started.position),
                    )
                    onPhrase(range)

                    drag(started.id) { change ->
                        change.consume()
                        range = parsed.spanBetween(
                            anchor,
                            result.getOffsetForPosition(change.position),
                        )
                        onPhrase(range)
                    }
                    onPhraseDone(range)
                }
            },
    )
}

/**
 * Кусок текста между двумя точками касания, растянутый до границ слов.
 *
 * Растянутый — потому что палец опускают на середину слова, а взять половину
 * слова нельзя: «he was rea» не переводится и не сохраняется. Границы берутся
 * у токенов, то есть у того же разбора, который нарисовал абзац.
 */
private fun ParsedText.spanBetween(from: Int, to: Int): IntRange {
    val left = minOf(from, to)
    val right = maxOf(from, to)
    val start = tokenAt(left)?.start ?: left
    val end = tokenAt(right)?.end ?: right
    return start until maxOf(end, start + 1)
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
