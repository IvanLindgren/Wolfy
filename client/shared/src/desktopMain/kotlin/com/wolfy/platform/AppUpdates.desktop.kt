package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

@Composable
actual fun rememberAppUpdateController(
    serverUrl: String,
    currentVersion: String,
): AppUpdateController = remember(serverUrl, currentVersion) {
    // На Linux Wolfy распространяется как DEB. Автоматический запуск dpkg
    // требовал бы sudo и мог оборвать чтение, поэтому здесь не притворяемся
    // Windows-updater'ом: пакет отдаёт GitHub Actions, а установку решает сам
    // пользователь через менеджер пакетов.
    if (!isWindows()) return@remember ManualLinuxUpdates
    val directory = File(appDataDirectory(), "updates").apply { mkdirs() }
    ReleaseDownloader(
        serverUrl = serverUrl,
        currentVersion = currentVersion,
        platform = "windows",
        directory = directory,
        launchInstaller = { packageFile -> launchWindowsUpdater(directory, packageFile) },
    )
}

private object ManualLinuxUpdates : AppUpdateController {
    override val state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override suspend fun monitor() = Unit
    override suspend fun checkNow() = Unit
    override suspend fun install(): Boolean = false
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun launchWindowsUpdater(directory: File, packageFile: File): Boolean {
    val resources = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val bundled = sequenceOf(
        resources?.resolve(UPDATER_NAME),
        File("../server/build/$UPDATER_NAME"),
        File("server/build/$UPDATER_NAME"),
    ).filterNotNull().firstOrNull(File::isFile) ?: return false

    directory.mkdirs()
    val runner = File(directory, "wolfy-updater-runner.exe")
    bundled.copyTo(runner, overwrite = true)
    val launcher = ProcessHandle.current().info().command().orElse("")
        .takeIf { it.endsWith("Wolfy.exe", ignoreCase = true) }
        .orEmpty()
    ProcessBuilder(
        runner.absolutePath,
        "--wait-pid", ProcessHandle.current().pid().toString(),
        "--msi", packageFile.absolutePath,
        "--launch", launcher,
    ).directory(directory).start()
    return true
}

private fun appDataDirectory(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getenv("XDG_DATA_HOME")
        ?: File(System.getProperty("user.home"), ".local/share").absolutePath
    return File(base, "Wolfy")
}

private const val UPDATER_NAME = "wolfy-updater.exe"
