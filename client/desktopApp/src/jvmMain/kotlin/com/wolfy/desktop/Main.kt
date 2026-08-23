package com.wolfy.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.wolfy.ui.WolfyApplication
import java.awt.GraphicsEnvironment
import java.awt.SplashScreen

fun main() = application {
    val state = rememberWindowState(size = defaultWindowSize())

    Window(
        onCloseRequest = ::exitApplication,
        title = "Wolfy",
        state = state,
    ) {
        LaunchedEffect(Unit) { hideSplash() }

        // Адрес сервиса и токен берутся из окружения: в разработке они разные
        // у каждого, а зашивать их в код значит однажды выложить чужой токен
        // в репозиторий.
        WolfyApplication(
            serverUrl = System.getenv("WOLFY_SERVER_URL") ?: "http://localhost:8080",
            sessionToken = System.getenv("WOLFY_SESSION_TOKEN"),
        )
    }
}

/**
 * Убирает заставку, которую показал запускатель JVM.
 *
 * Именно после первого кадра, а не после первой композиции. Композиция
 * заканчивается раньше, чем окно нарисовано, и заставка, снятая по ней,
 * оставляет читателя перед пустым прямоугольником — то есть возвращает ровно
 * ту дыру, ради которой её и заводили.
 *
 * Если заставки не было — запуск из исходников, чужая сборка, — метод
 * возвращает `null`, и делать ничего не нужно. `close` на уже закрытой
 * заставке бросает исключение, поэтому вызов обёрнут: снять её могло и само
 * окно.
 */
private suspend fun hideSplash() {
    withFrameNanos { }
    runCatching { SplashScreen.getSplashScreen()?.close() }
}

/**
 * Размер окна при первом запуске.
 *
 * Считается от экрана, а не задаётся константой. Константа неизбежно
 * оказывается больше чьего-нибудь экрана: при масштабе интерфейса 150% окно в
 * 1040 точек занимает 1560 пикселей, и на ноутбуке его нижний край вместе с
 * карточкой слова уезжает за границу.
 *
 * Верхний предел всё равно нужен: на широком мониторе окно во весь экран даёт
 * строку в полторы сотни знаков, которую невозможно читать — глаз теряет
 * начало следующей строки.
 */
private fun defaultWindowSize(): DpSize {
    val environment = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
    }.getOrNull() ?: return DpSize(1040.dp, 720.dp)

    val bounds = runCatching { environment.maximumWindowBounds }.getOrNull()
        ?: return DpSize(1040.dp, 720.dp)

    // Точка Compose не равна пикселю экрана. При масштабе интерфейса 150%,
    // обычном на ноутбуках, окно в 860 точек занимает 1290 пикселей — и не
    // помещается на экран высотой 1067, вместе с нижней частью карточки слова.
    // Поэтому размер экрана переводится в точки, а не берётся как есть.
    val scale = runCatching {
        environment.defaultScreenDevice.defaultConfiguration.defaultTransform.scaleY
    }.getOrNull()?.takeIf { it > 0.0 } ?: 1.0

    val availableWidth = (bounds.width / scale)
    val availableHeight = (bounds.height / scale)

    return DpSize(
        // Верхний предел по ширине нужен и на большом мониторе: окно во весь
        // экран даёт строку в полторы сотни знаков, на которой глаз теряет
        // начало следующей.
        width = (availableWidth * 0.75).dp.coerceIn(720.dp, 1100.dp),
        height = (availableHeight * 0.9).dp.coerceAtLeast(560.dp),
    )
}
