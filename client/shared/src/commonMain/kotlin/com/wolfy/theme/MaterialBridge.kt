package com.wolfy.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Мост к тем немногим компонентам, которые взяты у Material.
 *
 * Интерфейс Wolfy рисуется своими токенами, и это правильно: у Material свои
 * роли цвета, из которых газетной полосе не нужно почти ничего. Но несколько
 * готовых компонентов взять всё же пришлось — ползунок характера, переключатель,
 * поле ввода, индикатор загрузки, подсказка у кнопки. Каждый из них читает
 * `MaterialTheme.colorScheme`, и пока `MaterialTheme` не был объявлен нигде,
 * все они брали базовую схему Material: сиреневый `#6750A4`, одинаковый и на
 * бумаге, и в OLED-теме. Ползунки характера были сиреневыми во всех четырёх
 * темах — не по решению, а потому что их никто не спросил.
 *
 * Мост переводит газетные роли в материальные один раз здесь, а не по одному
 * `colors = ...` на каждом вызове компонента. Своих экранов он не касается:
 * они по-прежнему берут цвет только из [WolfyColors].
 */
internal fun WolfyColors.toMaterialScheme(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        // Акцент — единственный «активный» цвет газеты: заполненная часть
        // ползунка, включённый переключатель, рамка поля в фокусе, курсор.
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface,
        onPrimaryContainer = ink,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = surface,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,

        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        // Незаполненная дорожка ползунка, подложка выключенного переключателя
        // и фон филд-поля: плотнее бумаги, но не плашка.
        surfaceVariant = surface,
        onSurfaceVariant = inkMuted,
        surfaceContainerLowest = paper,
        surfaceContainerLow = paper,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        surfaceTint = accent,

        outline = rule,
        outlineVariant = rule,

        // Выворотная плашка остаётся плашкой во всех темах — на ней рисуется
        // подсказка у кнопок читалки.
        inverseSurface = inverse,
        inverseOnSurface = onInverse,
        inversePrimary = accent,

        // Отдельного «ошибочного» цвета в газете нет: тревога и акцент — один
        // и тот же сигнальный красный.
        error = accent,
        onError = onAccent,
        errorContainer = surface,
        onErrorContainer = accent,

        scrim = Color.Black,
    )
}

/**
 * Тот же перевод для шрифта.
 *
 * Заимствованные компоненты сами решают, каким стилем писать: подпись в поле
 * ввода — `bodyLarge`, текст подсказки — `bodySmall`. Без перевода они
 * набирались системным шрифтом рядом с газетным, и поле ввода имени компаньона
 * выглядело деталью из другого приложения.
 */
internal fun WolfyTypography.toMaterialTypography(): Typography {
    val base = Typography()
    return base.copy(
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = caption,
        labelLarge = button,
        labelMedium = caption,
        labelSmall = caption,
        titleMedium = bookTitle,
        titleSmall = body,
    )
}

@Composable
internal fun rememberMaterialScheme(colors: WolfyColors): ColorScheme =
    remember(colors) { colors.toMaterialScheme() }

@Composable
internal fun rememberMaterialTypography(typography: WolfyTypography): Typography =
    remember(typography) { typography.toMaterialTypography() }
