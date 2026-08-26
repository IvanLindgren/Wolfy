package com.wolfy.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Съёмка страницы на Android.
 *
 * Камера отдаёт снимок в файл, а не в память, и это не прихоть системы:
 * фотография страницы весит мегабайты, а межпроцессная передача ограничена
 * мегабайтом. Готовый снимок из галереи приходит ссылкой — её тоже надо
 * прочитать до того, как система её отзовёт.
 *
 * Разрешение на камеру не запрашивается: снимок делает системное приложение
 * камеры, и права нужны ему, а не нам. Это тот редкий случай, когда правильный
 * способ ещё и проще.
 */
@Composable
actual fun rememberPhotoPicker(
    fromCamera: Boolean,
    onPicked: (PickedPhoto) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()

    // Файл под снимок готовится заранее: камера пишет в него сама, и сказать
    // ей, куда писать, надо до запуска.
    val target = remember { photoFile(context) }
    val uri = remember(target) { photoUri(context, target) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (!taken) return@rememberLauncherForActivityResult
        scope.launch {
            val photo = withContext(Dispatchers.Default) {
                val bytes = runCatching { target.readBytes() }.getOrNull() ?: return@withContext null
                PickedPhoto(compressPhoto(bytes), "image/jpeg")
            }
            // Снимок уже сжат и отправлен: держать оригинал в кэше незачем.
            target.delete()
            photo?.let(callback.value)
        }
    }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { picked ->
        val source = picked ?: return@rememberLauncherForActivityResult
        scope.launch {
            val photo = withContext(Dispatchers.Default) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                PickedPhoto(compressPhoto(bytes), "image/jpeg")
            }
            photo?.let(callback.value)
        }
    }

    return {
        if (fromCamera) {
            camera.launch(uri)
        } else {
            gallery.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
    }
}

private fun photoFile(context: Context): File {
    val directory = File(context.cacheDir, "photos").apply { mkdirs() }
    return File(directory, "page.jpg")
}

/**
 * Ссылка на файл для чужого приложения.
 *
 * Через FileProvider, а не `Uri.fromFile`: начиная с Android 7 передача
 * file-ссылки другому приложению роняет его с FileUriExposedException.
 */
private fun photoUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".photos", file)

/**
 * Уменьшает снимок до разумного размера.
 *
 * Считается в два прохода: сперва читаются только размеры, потом картинка
 * загружается уже уменьшенной. Загрузить четырёхмегапиксельный снимок целиком
 * ради того, чтобы сразу его сжать, — верный способ получить нехватку памяти
 * на слабом телефоне.
 */
actual fun compressPhoto(bytes: ByteArray): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val side = maxOf(bounds.outWidth, bounds.outHeight)
    if (side <= 0) return bytes

    // inSampleSize — степень двойки: система умеет уменьшать только так, зато
    // делает это при чтении, не разворачивая оригинал в памяти.
    var sample = 1
    while (side / sample > PHOTO_MAX_SIDE) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return bytes

    return try {
        val out = ByteArrayOutputStream()
        // 85 — то качество, на котором буквы ещё чёткие, а вес уже втрое
        // меньше. Ниже начинают плыть засечки, и распознавание ошибается.
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
