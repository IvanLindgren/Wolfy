package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.net.URI

@Composable
actual fun rememberBrowserAuthLauncher(): BrowserAuthLauncher = remember {
    BrowserAuthLauncher { start ->
        launchLoopbackAuth(
            openBrowser = { address ->
                runCatching {
                    if (!Desktop.isDesktopSupported()) return@runCatching false
                    Desktop.getDesktop().browse(URI(address))
                    true
                }.getOrDefault(false)
            },
            start = start,
        )
    }
}
