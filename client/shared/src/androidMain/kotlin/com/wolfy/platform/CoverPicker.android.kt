package com.wolfy.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Выбор обложки на Android.
 *
 * Photo Picker выдаёт приложению доступ только к конкретно выбранному URI,
 * поэтому разрешение на чтение всей медиатеки не требуется и не запрашивается.
 *
 * Форматы проверяются по типу, который система отдаёт вместе со ссылкой, —
 * расширению файла верить нельзя, его мог назвать кто угодно.
 */
@Composable
actual fun rememberCoverPicker(onPicked: (PickedCover?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { picked ->
        val source = picked ?: return@rememberLauncherForActivityResult

        // Чтение, разбор и пережатие картинки — работа тяжёлая: файл бывает
        // на десятки мегабайт, а колбэк лончера живёт на главном потоке.
        // Держать там декодирование — значит подарить интерфейсу фриз на
        // слабом телефоне и ANR на особо крупном снимке.
        scope.launch {
            val cover = withContext(Dispatchers.IO) {
                val mime = resolver.getType(source).orEmpty()
                if (mime !in COVER_MIME_TYPES) return@withContext null
                val bytes = readLimited(resolver, source) ?: return@withContext null
                prepareCover(bytes)
            }
            callback.value(cover)
        }
    }

    return {
        gallery.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
}

/** Типы картинок, из которых позволено ставить обложку. */
private val COVER_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")

/**
 * Читает файл в память, обрываясь на [COVER_MAX_BYTES].
 *
 * Провайдер вправе не сообщать размер заранее, поэтому лимит держится при
 * чтении, а не по декларации: поток копируется со счётчиком, и перебор
 * означает «файл не подходит», а не нехватку памяти.
 */
private fun readLimited(
    resolver: android.content.ContentResolver,
    source: android.net.Uri,
): ByteArray? = runCatching {
    val input = resolver.openInputStream(source) ?: return null
    input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > COVER_MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}.getOrNull()

/**
 * Уменьшает обложку до разумного размера и выбирает формат хранения.
 *
 * Три прохода намеренно: сначала только размеры, потом декодирование с
 * уменьшением, затем — поворот по EXIF и точная подгонка до
 * [COVER_MAX_SIDE]. `inSampleSize` уменьшает только в степени двойки, и
 * картинка на 2050 точек стала бы 512-точечной; второй проход дотягивает её
 * ровно до 1024, ничего не раздувая.
 *
 * PNG с прозрачностью остаётся PNG — иначе прозрачность превратится в чёрный
 * фон. Всё остальное едет в JPEG: обложка не требует точности пикселей, а
 * весит после этого впятеро меньше.
 */
actual fun prepareCover(bytes: ByteArray): PickedCover? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val side = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (side / sample > COVER_MAX_SIDE) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

    try {
        bitmap = applyExif(bitmap, bytes)

        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > COVER_MAX_SIDE) {
            val scale = COVER_MAX_SIDE.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val hasAlpha = bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (!bitmap.compress(format, 88, out)) return null
        return PickedCover(
            bytes = out.toByteArray(),
            mime = if (hasAlpha) "image/png" else "image/jpeg",
            extension = if (hasAlpha) "png" else "jpg",
        )
    } finally {
        bitmap.recycle()
    }
}

/**
 * Поворачивает картинку так, как её показывает галерея.
 *
 * Камера не переворачивает пиксели — она записывает поворот в EXIF, а галерея
 * читает его при показе. `BitmapFactory` про EXIF не знает, поэтому без этого
 * шага обложка сохранилась бы навсегда повёрнутой.
 */
private fun applyExif(source: Bitmap, bytes: ByteArray): Bitmap {
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return source
    }

    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated != source) source.recycle()
    return rotated
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? {
    // Bounds читаются без выделения всего растра. Иначе маленький архивный
    // JPEG с десятками миллионов пикселей мог бы уронить читалку до того, как
    // успеет сработать LRU-кэш.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || pixels > MAX_DECODE_IMAGE_PIXELS) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
