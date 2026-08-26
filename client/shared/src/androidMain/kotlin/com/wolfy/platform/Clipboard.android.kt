package com.wolfy.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Ярлык буфера — «Цитата»: он виден в системной панели вставки, и «text/plain»
 * там читателю ничего не говорит.
 *
 * С Android 13 система сама показывает всплывающее подтверждение копирования,
 * поэтому своего сообщения приложение не рисует: два уведомления об одном
 * действии выглядят как сбой.
 */
@Composable
actual fun rememberClipboard(): Clipboard {
    val context = LocalContext.current
    return remember(context) {
        Clipboard { text, label ->
            runCatching {
                val service = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@runCatching false
                service.setPrimaryClip(ClipData.newPlainText(label, text))
                true
            }.getOrDefault(false)
        }
    }
}
