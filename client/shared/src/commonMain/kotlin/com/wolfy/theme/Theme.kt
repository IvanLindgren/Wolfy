package com.wolfy.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Оформление Wolfy.
 *
 * Тема своя, а не `MaterialTheme`, и это не вкусовщина. Material несёт с собой
 * готовые роли цвета, скругления, тени и ripple — целую систему, из которой
 * газетному интерфейсу не нужно почти ничего. Дешевле объявить четыре
 * собственных набора токенов, чем всю дорогу переопределять чужие.
 *
 * Токены берутся только отсюда. Литерал `Color(0xFF...)` или `24.dp` внутри
 * экрана — ошибка ревью: тем четыре, и любой такой литерал сломается в трёх
 * из них.
 */
object WolfyTheme {
    val colors: WolfyColors
        @Composable get() = LocalWolfyColors.current

    val typography: WolfyTypography
        @Composable get() = LocalWolfyTypography.current

    val fonts: WolfyFonts
        @Composable get() = LocalWolfyFonts.current

    val spacing: WolfySpacing
        @Composable get() = LocalWolfySpacing.current
}

/**
 * Отступы газетной сетки.
 *
 * Шаг четыре точки: он мельче обычной восьмёрки Material, потому что
 * типографская вёрстка живёт более мелкими интервалами — между строкой
 * заголовка и линейкой под ней восемь точек уже много.
 */
@Immutable
data class WolfySpacing(
    val hair: Dp = 2.dp,
    val tight: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val xlarge: Dp = 24.dp,
    val huge: Dp = 32.dp,
    /** Поля страницы читалки: в них живёт воздух печатной полосы. */
    val pageMargin: Dp = 20.dp,
    /** Толщина разделительной линейки. */
    val rule: Dp = 1.dp,
)

val LocalWolfyColors: ProvidableCompositionLocal<WolfyColors> =
    staticCompositionLocalOf { PaperColors }

val LocalWolfyTypography: ProvidableCompositionLocal<WolfyTypography> =
    staticCompositionLocalOf { error("WolfyTypography не задана: оберните экран в WolfyTheme") }

val LocalWolfyFonts: ProvidableCompositionLocal<WolfyFonts> =
    staticCompositionLocalOf { error("WolfyFonts не заданы: оберните экран в WolfyTheme") }

val LocalWolfySpacing: ProvidableCompositionLocal<WolfySpacing> =
    staticCompositionLocalOf { WolfySpacing() }

/**
 * Оборачивает интерфейс в выбранную тему.
 *
 * @param theme тема оформления, выбранная читателем в настройках.
 * @param fontScale множитель кегля читалки. Растягивает только текст книги:
 *   интерфейс от него не меняется, иначе подписи полезли бы друг на друга, а
 *   читателю нужен крупнее именно текст.
 */
@Composable
fun WolfyTheme(
    theme: ReadingTheme = ReadingTheme.Paper,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val fonts = rememberWolfyFonts()
    val base = rememberWolfyTypography(fonts)
    val typography = remember(base, fontScale) {
        if (fontScale == 1f) base else base.scaledForReading(fontScale)
    }

    CompositionLocalProvider(
        LocalWolfyColors provides theme.colors,
        LocalWolfyFonts provides fonts,
        LocalWolfyTypography provides typography,
        LocalWolfySpacing provides WolfySpacing(),
        // Ripple убран намеренно: расходящийся круг — жест материальной
        // поверхности, а страница книги бумажная. Нажатие показывается
        // деликатным масштабированием, см. widgets/Pressable.
        LocalIndication provides NoIndication,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // Фон задаётся здесь один раз: иначе при смене темы между
                // экранами мелькает белый прямоугольник по умолчанию.
                .background(theme.colors.paper),
        ) {
            content()
        }
    }
}
