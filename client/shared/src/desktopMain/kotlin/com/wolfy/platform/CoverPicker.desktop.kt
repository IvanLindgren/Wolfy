package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.Image

/**
 * Выбор обложки на Windows.
 *
 * Системное окно с фильтром форматов: png, jpg и webp — то, что читает и
 * ядро отрисовки, и хранилище. Расширению при этом верить нельзя: файл мог
 * получить чужое имя, поэтому картинку ещё и декодируют на проверку.
 */
@Composable
actual fun rememberCoverPicker(onPicked: (PickedCover) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)

    return {
        val dialog = FileDialog(null as Frame?, "Выберите обложку", FileDialog.LOAD)
        dialog.file = "*.png;*.jpg;*.jpeg;*.webp"
        dialog.isVisible = true

        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) {
            val bytes = runCatching { File(directory, name).readBytes() }.getOrNull()
            if (bytes != null) {
                prepareCover(bytes)?.let(callback.value)
            }
        }
    }
}

/**
 * Уменьшает обложку до разумного размера.
 *
 * PNG с прозрачностью остаётся PNG; всё прочее едет в JPEG. ImageIO не умеет
 * писать WebP — да это и не нужно: обложка хранится для показа плиткой, а не
 * для архива оригиналов.
 */
actual fun prepareCover(bytes: ByteArray): PickedCover? {
    val source = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null

    val side = maxOf(source.width, source.height)
    val scale = if (side > COVER_MAX_SIDE) COVER_MAX_SIDE.toDouble() / side else 1.0
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)

    val hasAlpha = source.transparency != BufferedImage.OPAQUE
    val target = BufferedImage(
        width,
        height,
        if (hasAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB,
    )
    val graphics = target.createGraphics()
    graphics.drawImage(source.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
    graphics.dispose()

    val out = ByteArrayOutputStream()
    val written = if (hasAlpha) {
        ImageIO.write(target, "png", out)
    } else {
        ImageIO.write(target, "jpg", out)
    }
    if (!written) return null

    return PickedCover(
        bytes = out.toByteArray(),
        mime = if (hasAlpha) "image/png" else "image/jpeg",
        extension = if (hasAlpha) "png" else "jpg",
    )
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
