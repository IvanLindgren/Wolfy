package com.wolfy.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

/**
 * Выбор обложки на Android.
 *
 * Системный выбор фотографий: разрешение на чтение галереи не спрашивается,
 * потому что его спрашивает само системное окно. Форматы проверяются по типу,
 * который система отдаёт вместе со ссылкой, — расширение здесь ни при чём.
 */
@Composable
actual fun rememberCoverPicker(onPicked: (PickedCover) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { picked ->
        val source = picked ?: return@rememberLauncherForActivityResult
        val mime = resolver.getType(source).orEmpty()
        if (mime !in COVER_MIME_TYPES) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            resolver.openInputStream(source)?.use { it.readBytes() }
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        prepareCover(bytes)?.let(callback.value)
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
 * Уменьшает обложку до разумного размера и выбирает формат хранения.
 *
 * PNG с прозрачностью остаётся PNG — иначе прозрачность превратится в чёрный
 * фон. Всё остальное едет в JPEG: обложка не требует точности пикселей, а
 * весит после этого впятеро меньше WebP-оригинала.
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
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

    return try {
        val hasAlpha = bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (!bitmap.compress(format, 88, out)) return null
        PickedCover(
            bytes = out.toByteArray(),
            mime = if (hasAlpha) "image/png" else "image/jpeg",
            extension = if (hasAlpha) "png" else "jpg",
        )
    } finally {
        bitmap.recycle()
    }
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
