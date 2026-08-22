package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Выбор снимка на Windows.
 *
 * Камеры у настольного компьютера обычно нет, а если есть — это веб-камера,
 * которой страницу книги не снять. Поэтому здесь всегда выбор файла: страницу
 * фотографируют телефоном и переносят.
 */
@Composable
actual fun rememberPhotoPicker(
    fromCamera: Boolean,
    onPicked: (PickedPhoto) -> Unit,
): () -> Unit {
    val callback = rememberUpdatedState(onPicked)

    return {
        val dialog = FileDialog(null as Frame?, "Выберите снимок страницы", FileDialog.LOAD)
        dialog.file = "*.jpg;*.jpeg;*.png;*.webp"
        dialog.isVisible = true

        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) {
            val file = File(directory, name)
            if (file.isFile) {
                callback.value(
                    PickedPhoto(
                        bytes = compressPhoto(file.readBytes()),
                        mime = "image/jpeg",
                    ),
                )
            }
        }
    }
}

actual fun compressPhoto(bytes: ByteArray): ByteArray {
    val source = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return bytes

    val side = maxOf(source.width, source.height)
    val scale = if (side > PHOTO_MAX_SIDE) PHOTO_MAX_SIDE.toDouble() / side else 1.0
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)

    // Всегда в RGB без прозрачности: JPEG её не умеет, а PNG со снимка
    // страницы весит втрое больше при том же результате распознавания.
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    graphics.drawImage(source.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
    graphics.dispose()

    val out = ByteArrayOutputStream()
    return if (ImageIO.write(target, "jpg", out)) out.toByteArray() else bytes
}
