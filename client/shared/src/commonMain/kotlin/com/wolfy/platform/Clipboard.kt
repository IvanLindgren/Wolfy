package com.wolfy.platform

import androidx.compose.runtime.Composable

/** Кладёт строку в системный буфер обмена. */
fun interface Clipboard {
    /** Возвращает `true`, только если система приняла текст. */
    fun put(text: String, label: String): Boolean
}

/**
 * Буфер обмена текущей платформы.
 *
 * Своим `expect`, а не общим `LocalClipboard`: тот в Compose Multiplatform
 * отдаёт запись через `ClipEntry`, который собирается по-разному на каждой
 * платформе, — то есть ровно тем же `expect`, только чужим и меняющимся от
 * версии к версии. Здесь нужна одна строка и ничего больше.
 */
@Composable
expect fun rememberClipboard(): Clipboard
