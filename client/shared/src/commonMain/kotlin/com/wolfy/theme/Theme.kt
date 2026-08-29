package com.wolfy.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
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

    val motion: WolfyMotion
        @Composable get() = LocalWolfyMotion.current
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

/**
 * Палитра текущей темы.
 *
 * `compositionLocalOf`, а не `staticCompositionLocalOf`: палитра перестала
 * быть постоянной величиной — при смене темы она едет к новой (см.
 * [rememberAnimatedColors]). Статический вариант на каждом кадре перехода
 * перерисовывал бы всё поддерево целиком, включая то, что цвета не читает.
 */
val LocalWolfyColors: ProvidableCompositionLocal<WolfyColors> =
    compositionLocalOf { PaperColors }

val LocalWolfyTypography: ProvidableCompositionLocal<WolfyTypography> =
    staticCompositionLocalOf { error("WolfyTypography не задана: оберните экран в WolfyTheme") }

val LocalWolfyFonts: ProvidableCompositionLocal<WolfyFonts> =
    staticCompositionLocalOf { error("WolfyFonts не заданы: оберните экран в WolfyTheme") }

val LocalWolfySpacing: ProvidableCompositionLocal<WolfySpacing> =
    staticCompositionLocalOf { WolfySpacing() }

val LocalWolfyMotion: ProvidableCompositionLocal<WolfyMotion> =
    staticCompositionLocalOf { WolfyMotion() }

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
    lineScale: Float = 1f,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val fonts = rememberWolfyFonts()
    val base = rememberWolfyTypography(fonts)
    val typography = remember(base, fontScale, lineScale) {
        if (fontScale == 1f && lineScale == 1f) base else base.scaledForReading(fontScale, lineScale)
    }
    val motion = if (reduceMotion) NoMotion else WolfyMotion()
    // Бумага и чернила переходят к новой теме, а не подменяются кадром.
    val colors = rememberAnimatedColors(theme.colors, motion)

    // MaterialTheme стоит снаружи, а наши локали — внутри: так собственное
    // отключение ripple и собственный цвет содержимого перекрывают то, что
    // Material успел объявить, а не наоборот.
    MaterialTheme(
        colorScheme = rememberMaterialScheme(colors),
        typography = rememberMaterialTypography(typography),
    ) {
        CompositionLocalProvider(
            LocalWolfyColors provides colors,
            LocalWolfyFonts provides fonts,
            LocalWolfyTypography provides typography,
            LocalWolfySpacing provides WolfySpacing(),
            LocalWolfyMotion provides motion,
            // Ripple убран намеренно: расходящийся круг — жест материальной
            // поверхности, а страница книги бумажная. Нажатие показывается
            // деликатным масштабированием, см. widgets/Pressable.
            LocalIndication provides NoIndication,
            // Text без явного цвета берёт его отсюда. По умолчанию Material
            // отдаёт чёрный, и такая подпись пропадала на тёмной бумаге.
            LocalContentColor provides colors.ink,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Фон задаётся здесь один раз: иначе при смене темы между
                    // экранами мелькает белый прямоугольник по умолчанию.
                    .background(colors.paper),
            ) {
                content()
            }
        }
    }
}
