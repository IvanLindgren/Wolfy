package com.wolfy.platform

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * Приём файлов, брошенных в окно, на Windows.
 *
 * Система отдаёт брошенное через AWT — тот же механизм, что у любого
 * настольного приложения. Compose пробрасывает его как есть, и разбирать
 * приходится вручную; зато поддерживается всё, что умеет система, включая
 * несколько файлов сразу.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileDropTarget(onDropped: (List<String>) -> Unit): Modifier {
    val callback = rememberUpdatedState(onDropped)

    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val paths = filesFrom(event)
                if (paths.isEmpty()) return false
                callback.value(paths)
                return true
            }
        }
    }

    return this.dragAndDropTarget(
        // Принимаем только список файлов. Текст, картинки из браузера и прочее
        // перетаскиваемое пропускаем мимо: приложение работает с файлами, и
        // молча проглотить брошенную ссылку значило бы соврать, что мы её поняли.
        shouldStartDragAndDrop = { event -> hasFiles(event) },
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun hasFiles(event: DragAndDropEvent): Boolean =
    runCatching {
        event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    }.getOrDefault(false)

@OptIn(ExperimentalComposeUiApi::class)
private fun filesFrom(event: DragAndDropEvent): List<String> =
    runCatching {
        val data = event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)
        (data as? List<*>).orEmpty()
            .filterIsInstance<File>()
            .filter { it.isFile }
            .map { it.absolutePath }
    }.getOrDefault(emptyList())
