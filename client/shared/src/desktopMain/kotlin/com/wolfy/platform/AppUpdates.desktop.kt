package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

/**
 * Обновление настольного клиента.
 *
 * Обе платформы проверяют, скачивают и сверяют пакет одинаково — этим занят
 * [ReleaseDownloader]. Различие ровно одно и лежит в последнем шаге: Windows
 * умеет поставить пакет сама, Linux — нет. Windows при этом спрашивает пакет
 * своей архитектуры: см. [windowsPlatform].
 *
 * ## Почему Linux больше не молчит
 *
 * Раньше Linux получал заглушку: ни проверки, ни сообщения. Обоснование было
 * верным наполовину — DEB действительно нельзя ставить без root, и делать это
 * молча посреди чтения нельзя тем более. Но из «мы не ставим сами» не следует
 * «мы не говорим». Пользователь узнавал о новой версии никак: приложение знало
 * про неё и молчало, а он читал старой сборкой, пока не заходил на сайт.
 *
 * Теперь Linux проходит тот же путь до предпоследнего шага: проверяет, качает,
 * сверяет контрольную сумму — и отдаёт готовый DEB системному установщику.
 * Дальше решает человек, и это единственное место, где Linux отличается от
 * Windows.
 */
@Composable
actual fun rememberAppUpdateController(
    serverUrl: String,
    currentVersion: String,
): AppUpdateController = remember(serverUrl, currentVersion) {
    val directory = File(appDataDirectory(), "updates").apply { mkdirs() }
    if (isWindows()) {
        ReleaseDownloader(
            serverUrl = serverUrl,
            currentVersion = currentVersion,
            platform = windowsPlatform(),
            directory = directory,
            launchInstaller = { packageFile -> launchWindowsUpdater(directory, packageFile) },
        )
    } else {
        ReleaseDownloader(
            serverUrl = serverUrl,
            currentVersion = currentVersion,
            platform = "linux",
            directory = directory,
            launchInstaller = ::handToSystemInstaller,
        )
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Какой Windows-пакет просить у сервера.
 *
 * Установщики двух архитектур собираются давно, но платформа у клиента была
 * одна. Машина на ARM спрашивала x64-сборку и получала обновление, которое у
 * неё не встанет: MSI проверяет архитектуру и отказывается. Обновление
 * молча переставало работать ровно там, где его труднее всего заметить.
 *
 * Архитектуру берём у JVM, а не у Windows: `PROCESSOR_ARCHITECTURE` в
 * переменных окружения врёт 32-битному процессу, а `os.arch` описывает ту
 * самую JVM, поверх которой поставится новая сборка.
 */
private fun windowsPlatform(): String =
    if (System.getProperty("os.arch").orEmpty().lowercase() in ARM64_NAMES) "windows-arm64" else "windows"

/** Как разные JVM называют одну и ту же 64-битную ARM. */
private val ARM64_NAMES = setOf("aarch64", "arm64")

/**
 * Запуск обновлятора Windows.
 *
 * Каждый отказ теперь называет себя. Их здесь три, и все три раньше приходили
 * читателю одинаковым `false`: нажал «перезапустить» — ничего не случилось,
 * нажал ещё раз — снова ничего.
 */
private fun launchWindowsUpdater(directory: File, packageFile: File): InstallOutcome {
    val resources = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val bundled = sequenceOf(
        resources?.resolve(UPDATER_NAME),
        File("../server/build/$UPDATER_NAME"),
        File("server/build/$UPDATER_NAME"),
    ).filterNotNull().firstOrNull(File::isFile)
        ?: return InstallOutcome.Refused("установщик обновлений не найден в сборке")

    directory.mkdirs()
    // Имя с номером процесса, а не одно на всех. Прошлый запуск обновлятора
    // мог не завершиться — он по устройству и ждёт закрытия приложения, — и
    // тогда копирование поверх работающего .exe на Windows отказывает совсем,
    // а не перезаписывает. Обновление переставало ставиться до перезагрузки,
    // и причину этого нельзя было увидеть ниоткуда.
    val runner = File(directory, "wolfy-updater-${ProcessHandle.current().pid()}.exe")
    val copied = runCatching { bundled.copyTo(runner, overwrite = true) }
    if (copied.isFailure) {
        return InstallOutcome.Refused("не удалось подготовить установщик: нет места или доступа")
    }
    // Мусор от прошлых запусков: обновлятор себя не удаляет, потому что в
    // момент удаления он ещё работает.
    directory.listFiles()
        ?.filter { it != runner && it.name.startsWith("wolfy-updater-") && it.name.endsWith(".exe") }
        ?.forEach { it.delete() }

    val launcher = ProcessHandle.current().info().command().orElse("")
        .takeIf { it.endsWith("Wolfy.exe", ignoreCase = true) }
        .orEmpty()
    ProcessBuilder(
        runner.absolutePath,
        "--wait-pid", ProcessHandle.current().pid().toString(),
        "--msi", packageFile.absolutePath,
        "--launch", launcher,
    ).directory(directory).start()
    return InstallOutcome.Restarting
}

/**
 * Передача пакета системе на Linux.
 *
 * `xdg-open` открывает DEB тем, что в системе назначено обработчиком: GNOME
 * Software, Discover, GDebi. Он же спросит пароль — приложению права root не
 * нужны и брать их незачем.
 *
 * Приложение при этом не закрывается: установка ещё не началась, а закрыть
 * читалку в момент, когда человек только увидел окно установщика и может его
 * отменить, значило бы прервать чтение зря.
 */
private fun handToSystemInstaller(packageFile: File): InstallOutcome {
    val opener = sequenceOf("xdg-open", "gio").firstOrNull { command ->
        runCatching {
            ProcessBuilder(command, "--help").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor()
        }.isSuccess
    } ?: return InstallOutcome.Refused(
        "пакет скачан в ${packageFile.parent}, откройте его менеджером пакетов",
    )

    val command = if (opener == "gio") {
        listOf("gio", "open", packageFile.absolutePath)
    } else {
        listOf("xdg-open", packageFile.absolutePath)
    }
    ProcessBuilder(command).start()
    return InstallOutcome.HandedToSystem
}

private fun appDataDirectory(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getenv("XDG_DATA_HOME")
        ?: File(System.getProperty("user.home"), ".local/share").absolutePath
    return File(base, "Wolfy")
}

private const val UPDATER_NAME = "wolfy-updater.exe"
