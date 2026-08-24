package com.wolfy.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberBrowserAuthLauncher(): BrowserAuthLauncher {
    val context = LocalContext.current
    return remember(context) {
        BrowserAuthLauncher { start ->
            launchLoopbackAuth(
                openBrowser = { address ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                },
                start = start,
            )
        }
    }
}
