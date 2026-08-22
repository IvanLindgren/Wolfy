package com.wolfy.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.wolfy.ui.WolfyApplication

fun main() = application {
    val state = rememberWindowState(
        // Разворот книги на большом экране: текст по центру, словарь и
        // прогресс на полях. Уже — и вторая колонка перестаёт помещаться.
        size = DpSize(1280.dp, 860.dp),
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "Wolfy",
        state = state,
    ) {
        WolfyApplication()
    }
}
