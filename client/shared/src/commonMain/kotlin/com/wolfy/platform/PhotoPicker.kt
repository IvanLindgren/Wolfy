package com.wolfy.platform

import androidx.compose.runtime.Composable

/** Снимок страницы, готовый к отправке. */
data class PickedPhoto(
    val bytes: ByteArray,
    val mime: String,
) {
    // Массив байтов ломает равенство data-класса: два одинаковых снимка
    // сравнивались бы по ссылке. Здесь это нужно только для сравнения в
    // тестах, но молча оставить неверное равенство хуже, чем написать его.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PickedPhoto && mime == other.mime && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mime.hashCode()
}

/**
 * Съёмка страницы бумажной книги.
 *
 * Возвращает функцию, которая открывает камеру или выбор файла, — а не делает
 * это сама: на Android запуск чужого экрана требует регистрации до того, как
 * его позовут, и сделать это можно только внутри композиции.
 *
 * @param fromCamera снимать камерой или выбрать готовый файл. На компьютере
 *   различия нет: камеры у него обычно нет, а страницу фотографируют телефоном
 *   и переносят файлом.
 */
@Composable
expect fun rememberPhotoPicker(fromCamera: Boolean, onPicked: (PickedPhoto) -> Unit): () -> Unit

/**
 * Готовит снимок к отправке.
 *
 * Уменьшает до разумного размера и пережимает. Фотография с телефона весит
 * четыре мегабайта и содержит разрешение, на котором видно волокна бумаги, —
 * распознаванию оно не нужно, а время отправки утраивает.
 */
expect fun compressPhoto(bytes: ByteArray): ByteArray

/** Наибольшая сторона снимка после сжатия. */
const val PHOTO_MAX_SIDE = 1600
