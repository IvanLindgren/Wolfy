package com.wolfy.platform

import androidx.compose.runtime.Composable

/** Результат, который браузер вернул только локальному приложению. */
data class BrowserAuthResult(val parameters: Map<String, String>) {
    val error: String? get() = parameters["error"]?.takeIf(String::isNotBlank)
}

fun interface BrowserAuthLauncher {
    /**
     * Поднимает одноразовый слушатель на 127.0.0.1, передаёт его адрес серверу
     * и открывает системный браузер. Пароли и токены не проходят через WebView
     * и не попадают в адресную строку приложения.
     */
    suspend fun launch(start: suspend (returnUrl: String) -> String): BrowserAuthResult
}

@Composable
expect fun rememberBrowserAuthLauncher(): BrowserAuthLauncher
