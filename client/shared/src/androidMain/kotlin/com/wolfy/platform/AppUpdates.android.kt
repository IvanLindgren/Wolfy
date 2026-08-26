package com.wolfy.platform

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.app.Activity
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
actual fun rememberAppUpdateController(
    serverUrl: String,
    currentVersion: String,
): AppUpdateController {
    val context = LocalContext.current
    return remember(serverUrl, currentVersion, context) {
        val activity = context as? Activity
        if (activity != null && installedFromPlay(context.packageManager, context.packageName)) {
            return@remember PlayUpdateController(activity)
        }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        ReleaseDownloader(
            serverUrl = serverUrl,
            currentVersion = currentVersion,
            platform = "android",
            directory = directory,
            launchInstaller = { packageFile ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                    false
                } else {
                    val uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".photos",
                        packageFile,
                    )
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                    true
                }
            },
        )
    }
}

/** Google Play сам проверяет подпись, скачивает пакет и завершает обновление. */
private class PlayUpdateController(
    private val activity: Activity,
) : AppUpdateController {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private val listener = InstallStateUpdatedListener { install ->
        when (install.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val total = install.totalBytesToDownload()
                mutableState.value = AppUpdateState.Downloading(
                    "Google Play",
                    if (total > 0) install.bytesDownloaded().toFloat() / total else 0f,
                )
            }
            InstallStatus.DOWNLOADED -> mutableState.value = AppUpdateState.Ready("из Google Play")
            InstallStatus.FAILED -> mutableState.value = AppUpdateState.Failed("Google Play не скачал обновление")
        }
    }

    override suspend fun monitor() {
        manager.registerListener(listener)
        try {
            delay(4_000)
            while (true) {
                checkNow()
                delay(6 * 60 * 60_000L)
            }
        } finally {
            manager.unregisterListener(listener)
        }
    }

    override suspend fun install(): Boolean {
        return when (val current = mutableState.value) {
            is AppUpdateState.Ready -> {
                withContext(Dispatchers.Main) { manager.completeUpdate() }
                true
            }
            is AppUpdateState.Available -> {
                val info = manager.appUpdateInfo.await()
                if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE ||
                    !info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    mutableState.value = AppUpdateState.Failed("обновление больше недоступно")
                    false
                } else {
                    withContext(Dispatchers.Main) {
                        manager.startUpdateFlowForResult(
                            info,
                            activity,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                            PLAY_UPDATE_REQUEST,
                        )
                    }
                    false
                }
            }
            else -> false
        }
    }

    override suspend fun checkNow() {
        runCatching { check() }.onFailure {
            mutableState.value = AppUpdateState.Failed("Google Play не проверил обновление")
        }
    }

    private suspend fun check() {
        val info = manager.appUpdateInfo.await()
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            mutableState.value = AppUpdateState.Ready("из Google Play")
            return
        }
        if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
        ) {
            // Проверка не должна внезапно открывать диалог Google Play:
            // приложение может лишь предложить обновление, решение за
            // читателем и отдельной кнопкой «установить».
            mutableState.value = AppUpdateState.Available("Google Play")
        }
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

@Suppress("DEPRECATION")
private fun installedFromPlay(packageManager: PackageManager, packageName: String): Boolean =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = packageManager.getInstallSourceInfo(packageName)
            source.initiatingPackageName == PLAY_PACKAGE || source.installingPackageName == PLAY_PACKAGE
        } else {
            packageManager.getInstallerPackageName(packageName) == PLAY_PACKAGE
        }
    }.getOrDefault(false)

private const val PLAY_PACKAGE = "com.android.vending"
private const val PLAY_UPDATE_REQUEST = 7312
