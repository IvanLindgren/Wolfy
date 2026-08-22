package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * На Android файлы в окно не бросают.
 *
 * Перетаскивание между приложениями там есть — на планшетах, в разделённом
 * экране, — но добавляют книгу иначе: из файлового менеджера через «открыть
 * с помощью» или кнопкой в самой библиотеке. Заводить ради редкого случая
 * второй способ значит поддерживать его вечно.
 */
@Composable
actual fun Modifier.fileDropTarget(onDropped: (List<String>) -> Unit): Modifier = this
