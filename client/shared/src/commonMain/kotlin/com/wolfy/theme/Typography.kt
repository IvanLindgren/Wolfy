package com.wolfy.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wolfy.resources.EBGaramond
import com.wolfy.resources.EBGaramond_Italic
import com.wolfy.resources.Fraunces
import com.wolfy.resources.Inter
import com.wolfy.resources.PlayfairDisplay
import com.wolfy.resources.PlayfairDisplay_Italic
import com.wolfy.resources.Res
import org.jetbrains.compose.resources.Font

/**
 * Шрифты Wolfy.
 *
 * Четыре семейства, и у каждого своя работа.
 *
 * **Playfair Display** — заголовки экранов и названия книг. Именно он, а не
 * Fraunces, хотя в макетах подписан Fraunces: у Fraunces нет кириллицы вовсе,
 * и русский заголовок «Настройки чтения» им набрать невозможно — система
 * подставила бы случайный шрифт, и заголовки экранов перестали бы выглядеть
 * как один набор.
 *
 * **Fraunces** — там, где текст заведомо латинский: логотип и буквица в начале
 * главы. Ради этих двух мест его и держим: характерная газетная антиква с
 * высоким контрастом, которую Playfair не заменяет.
 *
 * **EB Garamond** — сам текст книги и переводы. Он спокойнее заголовочных
 * антикв и рассчитан на длинное чтение; кириллица у него есть, поэтому русский
 * перевод рядом с английской фразой набран тем же шрифтом.
 *
 * **Inter** — интерфейс: кнопки, лейблы, счётчики, теги.
 */
@Immutable
data class WolfyFonts(
    val display: FontFamily,
    val dropCap: FontFamily,
    val book: FontFamily,
    val ui: FontFamily,
)

@Composable
fun rememberWolfyFonts(): WolfyFonts = WolfyFonts(
    display = FontFamily(
        Font(Res.font.PlayfairDisplay, FontWeight.Normal),
        Font(Res.font.PlayfairDisplay, FontWeight.Medium),
        Font(Res.font.PlayfairDisplay, FontWeight.Bold),
        Font(Res.font.PlayfairDisplay_Italic, FontWeight.Normal, FontStyle.Italic),
    ),
    dropCap = FontFamily(Font(Res.font.Fraunces, FontWeight.Bold)),
    book = FontFamily(
        Font(Res.font.EBGaramond, FontWeight.Normal),
        Font(Res.font.EBGaramond, FontWeight.Medium),
        Font(Res.font.EBGaramond, FontWeight.Bold),
        Font(Res.font.EBGaramond_Italic, FontWeight.Normal, FontStyle.Italic),
    ),
    ui = FontFamily(
        Font(Res.font.Inter, FontWeight.Normal),
        Font(Res.font.Inter, FontWeight.Medium),
        Font(Res.font.Inter, FontWeight.SemiBold),
    ),
)

/**
 * Набор стилей текста.
 *
 * Размеры взяты из макетов: 28–32 для заголовков экранов, 15–18 для названий,
 * 9–11 для текста читалки в его собственном масштабе, 7–9 для микро-лейблов.
 * В коде они переведены в sp и увеличены до читаемых на устройстве значений —
 * макет нарисован в масштабе печатной полосы, а не экрана телефона.
 */
@Immutable
data class WolfyTypography(
    /** Название экрана: «Полки», «Грамматика». */
    val screenTitle: TextStyle,
    /** Название книги в списке и в карточке. */
    val bookTitle: TextStyle,
    /** Заголовок главы над текстом. */
    val chapterTitle: TextStyle,
    /** Текст книги — то, что читают глазами дольше всего. */
    val reader: TextStyle,
    /** Перевод и подстрочник под английской фразой. */
    val translation: TextStyle,
    /** Разрядка мелким капслоком: «РАЗБОР СЛОВА», «КОЛЛОКАЦИИ». */
    val sectionLabel: TextStyle,
    /** Обычный текст интерфейса. */
    val body: TextStyle,
    /** Подписи, счётчики, проценты. */
    val caption: TextStyle,
    /** Надпись на кнопке. */
    val button: TextStyle,
)

@Composable
fun rememberWolfyTypography(fonts: WolfyFonts): WolfyTypography = WolfyTypography(
    screenTitle = TextStyle(
        fontFamily = fonts.display,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        // Плотный оптический кернинг — то, из-за чего газетный заголовок
        // выглядит монолитным, а не рассыпанным на буквы.
        letterSpacing = (-0.02).em,
    ),
    bookTitle = TextStyle(
        fontFamily = fonts.display,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.01).em,
    ),
    chapterTitle = TextStyle(
        fontFamily = fonts.display,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.01).em,
        textAlign = TextAlign.Center,
    ),
    reader = TextStyle(
        fontFamily = fonts.book,
        fontSize = 19.sp,
        // Интервал по умолчанию 1.5 от кегля — среднее положение ползунка
        // в настройках чтения.
        lineHeight = 28.5.sp,
    ),
    translation = TextStyle(
        fontFamily = fonts.book,
        fontStyle = FontStyle.Italic,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    sectionLabel = TextStyle(
        fontFamily = fonts.ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        // Капслок без разрядки читается как ошибка набора: буквам нужен
        // воздух, которого в прописных нет.
        letterSpacing = 0.12.em,
    ),
    body = TextStyle(
        fontFamily = fonts.ui,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    caption = TextStyle(
        fontFamily = fonts.ui,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    button = TextStyle(
        fontFamily = fonts.ui,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
)

/**
 * Тот же набор, но с укрупнённым текстом книги.
 *
 * Меняются три стиля: сам текст, буквица и перевод под ней. Заголовки и
 * подписи интерфейса остаются как были — они и так подобраны под сетку, а
 * растянутый заголовок главы просто перестал бы помещаться в строку.
 *
 * Межстрочный интервал умножается вместе с кеглем: набор, где вырос кегль, но
 * не выросли пробелы между строками, читается хуже мелкого.
 */
internal fun WolfyTypography.scaledForReading(scale: Float): WolfyTypography = copy(
    reader = reader.copy(
        fontSize = reader.fontSize * scale,
        lineHeight = reader.lineHeight * scale,
    ),
    translation = translation.copy(
        fontSize = translation.fontSize * scale,
        lineHeight = translation.lineHeight * scale,
    ),
    chapterTitle = chapterTitle.copy(
        fontSize = chapterTitle.fontSize * scale,
        lineHeight = chapterTitle.lineHeight * scale,
    ),
)
