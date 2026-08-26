package com.wolfy.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Флаг окна, а не пробуждение силой.
 *
 * `FLAG_KEEP_SCREEN_ON` действует, только пока окно на экране, и снимается
 * системой само, если приложение свернули. Отдельного разрешения ему не нужно,
 * и разряженной батареи в свёрнутом виде из-за него не бывает — в отличие от
 * `WakeLock`, который живёт своей жизнью и требует и разрешения, и того, чтобы
 * его не забыли отпустить.
 */
@Composable
actual fun KeepScreenAwake() {
    val context = LocalContext.current
    DisposableEffect(context) {
        // Compose может передать ContextThemeWrapper, а не саму Activity.
        // Разворачиваем обёртки, иначе на части тем фича молча не работает.
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
