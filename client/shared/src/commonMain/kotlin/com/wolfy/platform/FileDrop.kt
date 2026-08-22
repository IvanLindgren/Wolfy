package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Приём файлов, брошенных в окно.
 *
 * Самый естественный способ добавить книгу на компьютере: файл лежит в папке
 * «Загрузки», окно приложения открыто рядом. Диалог выбора после этого
 * выглядит лишним шагом — и он им и является.
 *
 * На телефоне бросать нечего: файлового менеджера рядом с приложением нет, и
 * модификатор там ничего не делает. Это не заглушка «на будущее», а честный
 * ответ: жеста, который сюда ведёт, на телефоне не существует.
 */
@Composable
expect fun Modifier.fileDropTarget(onDropped: (List<String>) -> Unit): Modifier

/**
 * Читает файл целиком.
 *
 * Нужно брошенному в окно снимку: он приходит путём, а распознавание ждёт
 * байты. `null` — файл исчез или не читается; для брошенного файла это
 * обычное дело, и падать из-за него нельзя.
 */
expect fun readBytes(path: String): ByteArray?

/** Имя файла из пути — разделитель у платформ разный. */
fun fileNameOf(path: String): String =
    path.substringAfterLast('\\').substringAfterLast('/')

/** Похоже ли на снимок страницы, а не на книгу. */
fun looksLikePhoto(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic")
