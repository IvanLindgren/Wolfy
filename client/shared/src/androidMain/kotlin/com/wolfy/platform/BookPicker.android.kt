package com.wolfy.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            picked?.let(callback.value)
        }
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
    // Три источника имени по убыванию доверия. Провайдер имя отдавать не
    // обязан, и Telegram, почта и часть облаков его не отдают: раньше все
    // присланные книги приезжали в библиотеку под одним словом «book».
    val name = bookFileName(
        displayName = displayName(context, uri),
        uriTail = uri.lastPathSegment,
        mimeType = context.contentResolver.getType(uri),
    )
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

/**
 * Имя файла, как его показывает система.
 *
 * Провайдер вправе не иметь такой колонки вовсе — тогда запрос падает, а не
 * возвращает пустой курсор. Отсутствие имени не повод не добавить книгу:
 * запасные источники разберутся, а исключение отсюда убило бы весь импорт.
 */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()
