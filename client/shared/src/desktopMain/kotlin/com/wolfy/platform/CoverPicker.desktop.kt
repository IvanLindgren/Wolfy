package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * Выбор обложки на Windows.
 *
 * Системное окно с фильтром форматов: png, jpg и webp — то, что читает и
 * ядро отрисовки, и хранилище. Расширению при этом верить нельзя: файл мог
 * получить чужое имя, поэтому картинку ещё и декодируют на проверку.
 */
@Composable
actual fun rememberCoverPicker(onPicked: (PickedCover?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()

    return {
        val dialog = FileDialog(null as Frame?, "Выберите обложку", FileDialog.LOAD)
        dialog.file = "*.png;*.jpg;*.jpeg;*.webp"
        dialog.isVisible = true

        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) {
            // Чтение и пережатие — тяжёлая работа, и главному потоку она не
            // нужна: картинка бывает на десятки мегабайт.
            scope.launch {
                val cover = withContext(Dispatchers.Default) {
                    val file = File(directory, name)
                    if (!file.isFile || file.length() > COVER_MAX_BYTES) {
                        return@withContext null
                    }
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                        ?: return@withContext null
                    prepareCover(bytes)
                }
                callback.value(cover)
            }
        }
    }
}

/**
 * Уменьшает обложку до разумного размера.
 *
 * Поворот из EXIF применяется до сжатия: камеры хранят ориентацию отдельно
 * от пикселей, `ImageIO` её не читает, и без этого шага обложка легла бы
 * боком навсегда. PNG с прозрачностью остаётся PNG; всё прочее едет в JPEG.
 * `ImageIO` не умеет писать WebP — да это и не нужно: обложка хранится для
 * показа плиткой, а не для архива оригиналов.
 */
actual fun prepareCover(bytes: ByteArray): PickedCover? {
    var source = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
    source = applyJpegOrientation(source, bytes)

    val side = maxOf(source.width, source.height)
    if (side > COVER_MAX_SIDE) {
        val scale = COVER_MAX_SIDE.toDouble() / side
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(
            width,
            height,
            if (source.transparency != BufferedImage.OPAQUE) {
                BufferedImage.TYPE_INT_ARGB
            } else {
                BufferedImage.TYPE_INT_RGB
            },
        )
        val graphics = scaled.createGraphics()
        graphics.drawImage(source.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        graphics.dispose()
        source = scaled
    }

    val hasAlpha = source.transparency != BufferedImage.OPAQUE
    val out = ByteArrayOutputStream()
    val written = if (hasAlpha) {
        ImageIO.write(source, "png", out)
    } else {
        ImageIO.write(source, "jpg", out)
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

// --- Поворот JPEG по EXIF -----------------------------------------------------

/**
 * Ориентация JPEG по тегу 0x0112 в EXIF-блоке APP1.
 *
 * Своя реализация вместо сторонней библиотеки по той же причине, что и
 * Base64 в общем коде: сорок строк надёжнее ещё одной зависимости. Читаются
 * только маркеры JPEG и ровно один тег; на сломанных или «не JPEG» файлах
 * разбор просто отступает — поворот тогда не нужен или невозможен.
 */
private fun jpegOrientation(bytes: ByteArray): Int? {
    var at = 2
    fun u2(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    while (at + 4 <= bytes.size) {
        if (bytes[at].toInt() != 0xFF) return null
        val marker = bytes[at + 1].toInt() and 0xFF
        if (marker == 0xD8 || (marker in 0xD0..0xD7)) {
            at += 2
            continue
        }
        val length = u2(at + 2)
        // Битый заголовок не должен зациклить разбор.
        if (length < 2) return null
        if (marker == 0xE1) {
            // APP1 бывает не только EXIF (XMP тоже живёт здесь): чужой блок
            // пропускается, сканирование идёт дальше.
            orientationFromApp1(bytes, at + 4, length - 2)?.let { return it }
        }
        if (marker == 0xDA) return null // скан-данные: EXIF дальше не бывает
        at += 2 + length
    }
    return null
}

private fun orientationFromApp1(bytes: ByteArray, start: Int, length: Int): Int? {
    // APP1 начинается с "Exif\0\0", дальше TIFF: порядок байтов, магический
    // 42 и смещение до IFD0. Ориентация — SHORT 0x0112 в IFD0.
    if (length < 14) return null
    if (bytes[start] != 'E'.code.toByte() || bytes[start + 1] != 'x'.code.toByte() ||
        bytes[start + 2] != 'i'.code.toByte() || bytes[start + 3] != 'f'.code.toByte()
    ) {
        return null
    }
    val tiff = start + 6

    val little = bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte()
    val big = bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte()
    if (!little && !big) return null

    // Двухбайтовое число по смещению от начала TIFF-заголовка.
    fun readU2(offset: Int): Int {
        val base = tiff + offset
        if (base < 0 || base + 2 > bytes.size) return -1
        val first = bytes[base].toInt() and 0xFF
        val second = bytes[base + 1].toInt() and 0xFF
        return if (little) first or (second shl 8) else (first shl 8) or second
    }

    if (readU2(2) != 42) return null
    val ifd0 = readU2(4).takeIf { it > 0 } ?: return null
    val count = readU2(ifd0)
    if (count <= 0 || count > 64) return null

    for (index in 0 until count) {
        val entry = ifd0 + 2 + index * 12
        if (readU2(entry) == 0x0112) {
            return readU2(entry + 8).takeIf { it in 1..8 }
        }
    }
    return null
}

private fun applyJpegOrientation(source: BufferedImage, bytes: ByteArray): BufferedImage {
    // Только JPEG хранит ориентацию в EXIF; у остальных форматов её нет.
    if (!bytes.startsWithJpeg()) return source
    val orientation = jpegOrientation(bytes) ?: return source

    // Та же таблица матриц, что в AOSP ExifInterface: повороты по часовой,
    // зеркала и два диагональных отражения. Матрица — столбцовая запись
    // (m00, m10, m01, m11, m02, m12): x' = m00·x + m01·y + m02.
    val width = source.width
    val height = source.height
    val matrix = when (orientation) {
        2 -> doubleArrayOf(-1.0, 0.0, 0.0, 1.0, width.toDouble(), 0.0)
        3 -> doubleArrayOf(-1.0, 0.0, 0.0, -1.0, width.toDouble(), height.toDouble())
        4 -> doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, height.toDouble())
        5 -> doubleArrayOf(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        6 -> doubleArrayOf(0.0, 1.0, -1.0, 0.0, width.toDouble(), 0.0)
        7 -> doubleArrayOf(0.0, -1.0, -1.0, 0.0, width.toDouble(), height.toDouble())
        8 -> doubleArrayOf(0.0, -1.0, 1.0, 0.0, 0.0, height.toDouble())
        else -> return source
    }
    val transform = AffineTransform(
        matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5],
    )

    // Повороты на 90° меняют стороны местами, диагональные отражения — тоже.
    val swapped = orientation in 5..8
    val target = AffineTransformOp(transform, AffineTransformOp.TYPE_BICUBIC)
    val rotated = BufferedImage(
        if (swapped) height else width,
        if (swapped) width else height,
        source.type,
    )
    target.filter(source, rotated)
    return rotated
}

private fun ByteArray.startsWithJpeg(): Boolean =
    size >= 3 &&
        this[0] == 0xFF.toByte() &&
        this[1] == 0xD8.toByte() &&
        this[2] == 0xFF.toByte()
