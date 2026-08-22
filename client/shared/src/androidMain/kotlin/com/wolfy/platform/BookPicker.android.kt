package com.wolfy.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Выбор книги на Android.
 *
 * Система отдаёт не путь, а ссылку — и ссылку, которая живёт до перезапуска
 * приложения. Ядро на Rust открывает файлы по пути, да и книгу, исчезающую
 * после перезагрузки телефона, читателю не предложишь, поэтому файл сразу
 * копируется во временный каталог. Оттуда его заберёт библиотека, когда решит,
 * что книга остаётся.
 */
@Composable
actual fun rememberBookPicker(onPicked: (PickedBook) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onPicked)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = copyToCache(context, uri) ?: return@rememberLauncherForActivityResult
        callback.value(picked)
    }

    return {
        // Форматы ровно те, что понимает ядро. «application/octet-stream»
        // добавлен не от широты души: файловые менеджеры нередко отдают epub
        // именно с этим типом, и без него книга просто не выбирается.
        launcher.launch(
            arrayOf(
                "application/epub+zip",
                "application/pdf",
                "text/plain",
                "application/octet-stream",
            ),
        )
    }
}

private fun copyToCache(context: Context, uri: Uri): PickedBook? {
    val name = displayName(context, uri) ?: "book"
    val target = File(context.cacheDir, "import-$name")

    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use(input::copyTo)
        } ?: return null
        PickedBook(path = target.absolutePath, name = name)
    } catch (e: Exception) {
        // Ссылка могла протухнуть, а место — кончиться. Молча ничего не
        // добавляем: сообщение об ошибке покажет экран библиотеки.
        target.delete()
        null
    }
}

/** Имя файла, как его показывает система. */
private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
