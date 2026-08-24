package com.wolfy.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/** Состояние тихой фоновой загрузки обновления. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class Downloading(val progress: Float) : AppUpdateState
    data class Ready(val version: String) : AppUpdateState
    data class Failed(val reason: String) : AppUpdateState
}

/**
 * Обновлятор ничего не спрашивает во время загрузки. Единственное действие
 * читателя — нажать появившуюся кнопку перезапуска, когда пакет уже проверен.
 */
interface AppUpdateController {
    val state: StateFlow<AppUpdateState>
    suspend fun monitor()
    suspend fun install(): Boolean
}

@Composable
expect fun rememberAppUpdateController(
    serverUrl: String,
    currentVersion: String,
): AppUpdateController
