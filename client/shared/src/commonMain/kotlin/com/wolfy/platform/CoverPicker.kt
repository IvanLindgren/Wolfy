package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Обложка, выбранная из галереи, уже приведённая к размеру для хранения. */
data class PickedCover(
    val bytes: ByteArray,
    val mime: String,
    /** Расширение без точки: «png» или «jpg». */
    val extension: String,
) {
    // Массив байтов ломает равенство data-класса — см. [PickedPhoto].
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PickedCover &&
                mime == other.mime &&
                extension == other.extension &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + mime.hashCode()) + extension.hashCode()
}

/**
 * Выбор обложки книги из галереи.
 *
 * Возвращает функцию, которая открывает выбор, — а не делает это сама: на
 * Android запуск чужого экрана требует регистрации до того, как его позовут.
 *
 * Камеры здесь нет намеренно: обложку ставят готовой картинке, а она и так
 * лежит в галерее.
 *
 * Колбэк получает `null` не при отмене выбора, а когда файл не подошёл:
 * чужой формат, слишком большой размер или повреждённая картинка. Отмена —
 * просто молчание: читатель передумал, и сообщать ему об этом нечего.
 */
@Composable
expect fun rememberCoverPicker(onPicked: (PickedCover?) -> Unit): () -> Unit

/**
 * Готовит картинку к хранению.
 *
 * Обложка показывается плиткой в сотню точек; хранить четыре мегапикселя
 * ради этого — значит разбухать файлам библиотеки без пользы. Уменьшает до
 * [COVER_MAX_SIDE], применяет поворот из EXIF (камеры хранят его отдельно от
 * пикселей) и пережимает: PNG остаётся PNG (там бывает прозрачность),
 * всё прочее превращается в JPEG.
 *
 * `null` — картинка не читается вовсе: файл повреждён или это не картинка,
 * как бы он ни назывался.
 */
expect fun prepareCover(bytes: ByteArray): PickedCover?

/** Декодирует сохранённую обложку для показа. `null` — файл не читается. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Наибольшая сторона обложки после сжатия. */
const val COVER_MAX_SIDE = 1024

/** Исходники тяжелее в память не читаются вовсе. */
const val COVER_MAX_BYTES = 24 * 1024 * 1024
