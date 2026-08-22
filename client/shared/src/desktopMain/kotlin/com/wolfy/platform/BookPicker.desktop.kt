package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Выбор книги на Windows.
 *
 * Взято `FileDialog`, а не `JFileChooser`: первый — системное окно, к которому
 * пользователь привык, второй нарисован Swing и выглядит чужим в любой системе.
 *
 * Окно модальное и блокирует поток, но открывается по нажатию — то есть в тот
 * момент, когда приложению всё равно нечего делать, кроме как ждать ответа.
 */
@Composable
actual fun rememberBookPicker(onPicked: (PickedBook) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)

    return {
        val dialog = FileDialog(null as Frame?, "Выберите книгу", FileDialog.LOAD)
        // Фильтр на Windows задаётся именно так: setFilenameFilter системное
        // окно игнорирует, а этот атрибут понимает.
        dialog.file = "*.epub;*.txt;*.pdf"
        dialog.isVisible = true

        val directory = dialog.directory
        val file = dialog.file
        if (directory != null && file != null) {
            val chosen = File(directory, file)
            if (chosen.isFile) {
                callback.value(PickedBook(path = chosen.absolutePath, name = chosen.name))
            }
        }
    }
}
