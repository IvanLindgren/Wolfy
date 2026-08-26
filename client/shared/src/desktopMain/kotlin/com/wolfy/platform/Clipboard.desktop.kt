package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Системный буфер Windows через AWT.
 *
 * Буфер занят другим приложением — обращение бросает исключение; для нас это
 * значит «не скопировалось», а не «упасть посреди чтения».
 */
@Composable
actual fun rememberClipboard(): Clipboard = remember {
    Clipboard { text, _ ->
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            true
        }.getOrDefault(false)
    }
}
