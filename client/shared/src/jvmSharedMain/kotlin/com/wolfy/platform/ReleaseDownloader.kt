package com.wolfy.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Чем кончилась попытка установки.
 *
 * Раньше здесь был `Boolean`, и `false` значил сразу три разные вещи: пакет
 * повреждён, установщик не нашёлся, установка передана системе. Все три вели в
 * одно и то же место — молчание. Читатель нажимал «перезапустить», ничего не
 * происходило, и узнать почему было неоткуда: состояние `Failed` интерфейс
 * вообще не показывал.
 */
internal sealed interface InstallOutcome {
    /** Установщик запущен, приложение обязано закрыться. */
    data object Restarting : InstallOutcome

    /**
     * Пакет отдан системному установщику, а приложение продолжает работать.
     *
     * Так устроен Linux: DEB ставит менеджер пакетов, ему нужен root, и
     * решение остаётся за человеком. Обновление остаётся `Ready` — если окно
     * установщика закроют, кнопка никуда не денется.
     */
    data object HandedToSystem : InstallOutcome

    /** Установка не началась, и вот почему. */
    data class Refused(val reason: String) : InstallOutcome
}

/** Общая JVM-реализация скачивания; установка остаётся платформенной. */
internal class ReleaseDownloader(
    private val serverUrl: String,
    private val currentVersion: String,
    private val platform: String,
    private val directory: File,
    private val launchInstaller: suspend (File) -> InstallOutcome,
) : AppUpdateController {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private var ready: StagedUpdate? = null
    private var available: UpdateManifest? = null

    override suspend fun monitor() {
        withContext(Dispatchers.IO) { restore() }
        // Первое сетевое обращение откладываем до первого кадра и запуска ядра.
        delay(INITIAL_DELAY_MS)
        while (true) {
            checkNow()
            delay(if (ready == null) RETRY_DELAY_MS else CHECK_DELAY_MS)
        }
    }

    override suspend fun checkNow() {
        if (ready == null) mutableState.value = AppUpdateState.Checking
        runCatching { withContext(Dispatchers.IO) { checkAvailability() } }
            .onFailure { error ->
                if (ready == null) {
                    mutableState.value = AppUpdateState.Failed(
                        error.message?.take(160) ?: "обновление не проверено",
                    )
                }
            }
    }

    override suspend fun install(): Boolean {
        val staged = ready
        if (staged == null) {
            val remote = available ?: return false
            return runCatching {
                withContext(Dispatchers.IO) { download(remote) }
                // Пакет теперь Ready; отдельное нажатие даст читателю шанс
                // выбрать момент перезапуска, а не оборвёт чтение самовольно.
                false
            }.getOrElse { error ->
                mutableState.value = AppUpdateState.Failed(
                    error.message?.take(160) ?: "обновление не скачалось",
                )
                false
            }
        }
        return withContext(Dispatchers.IO) {
            if (!staged.file.isFile || sha256(staged.file) != staged.manifest.sha256.lowercase()) {
                clearStaged()
                mutableState.value = AppUpdateState.Failed("скачанный пакет повреждён")
                return@withContext false
            }
            // Запуск установщика — это процесс, файловая система и права
            // доступа сразу. Отсюда прилетает и IOException, и отказ политики,
            // и «файл занят другим процессом», и раньше всё это молча гасилось
            // на вызывающей стороне.
            val outcome = runCatching { launchInstaller(staged.file) }.getOrElse { error ->
                InstallOutcome.Refused(error.message?.take(160) ?: "установщик не запустился")
            }
            when (outcome) {
                InstallOutcome.Restarting -> true
                InstallOutcome.HandedToSystem -> false
                is InstallOutcome.Refused -> {
                    mutableState.value = AppUpdateState.Failed(outcome.reason)
                    false
                }
            }
        }
    }

    private fun restore() {
        val metadata = File(directory, METADATA_FILE)
        if (!metadata.isFile) return
        val manifest = runCatching {
            json.decodeFromString(UpdateManifest.serializer(), metadata.readText(Charsets.UTF_8))
        }.getOrNull() ?: return clearStaged()
        val file = File(directory, manifest.fileName)
        if (!isNewer(manifest.version, currentVersion) ||
            !file.isFile || file.length() != manifest.size || sha256(file) != manifest.sha256.lowercase()
        ) {
            clearStaged()
            return
        }
        ready = StagedUpdate(manifest, file)
        mutableState.value = AppUpdateState.Ready(manifest.version)
    }

    private fun checkAvailability() {
        if (serverUrl.isBlank()) return
        val endpoint = serverUrl.trimEnd('/') + "/v1/update/latest?platform=" +
            encode(platform) + "&current=" + encode(currentVersion)
        val connection = open(endpoint)
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NO_CONTENT -> {
                    available = null
                    if (ready == null) mutableState.value = AppUpdateState.Idle
                    return
                }
                HttpURLConnection.HTTP_OK -> Unit
                else -> error("сервер обновлений ответил ${connection.responseCode}")
            }
            val manifest = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                json.decodeFromString(UpdateManifest.serializer(), it.readText())
            }
            require(isNewer(manifest.version, currentVersion)) { "сервер предложил старую версию" }
            require(manifest.size in 1..MAX_PACKAGE_SIZE) { "неверный размер обновления" }
            require(manifest.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "неверная контрольная сумма" }

            val existing = ready
            if (existing?.manifest?.version == manifest.version && existing.file.isFile) {
                available = null
                mutableState.value = AppUpdateState.Ready(manifest.version)
                return
            }
            available = manifest
            mutableState.value = AppUpdateState.Available(manifest.version)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(remote: UpdateManifest) {
        directory.mkdirs()
        val targetName = when (platform) {
            "windows" -> "Wolfy-${remote.version}.msi"
            "linux" -> "Wolfy-${remote.version}.deb"
            else -> "Wolfy-${remote.version}.apk"
        }
        val temporary = File(directory, "$targetName.part")
        val target = File(directory, targetName)
        temporary.delete()

        val resolved = URI(serverUrl.trimEnd('/') + "/").resolve(remote.url).toString()
        val connection = open(resolved)
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "пакет обновления недоступен (${connection.responseCode})"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        require(copied <= remote.size) { "пакет больше заявленного размера" }
                        mutableState.value = AppUpdateState.Downloading(
                            remote.version,
                            (copied.toDouble() / remote.size.toDouble()).toFloat().coerceIn(0f, 1f),
                        )
                    }
                }
            }
            require(copied == remote.size) { "обновление скачалось не полностью" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == remote.sha256.lowercase()) { "контрольная сумма обновления не совпала" }

            replace(temporary, target)
            val local = remote.copy(fileName = targetName)
            val metadata = File(directory, METADATA_FILE)
            val metadataTemporary = File(directory, "$METADATA_FILE.part")
            metadataTemporary.writeText(json.encodeToString(UpdateManifest.serializer(), local), Charsets.UTF_8)
            replace(metadataTemporary, metadata)
            ready = StagedUpdate(local, target)
            available = null
            mutableState.value = AppUpdateState.Ready(local.version)
            directory.listFiles()?.filter { it != target && it != metadata }?.forEach { old ->
                if (isPackage(old.name) || old.name.endsWith(".part")) old.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun clearStaged() {
        ready = null
        directory.listFiles()?.forEach { file ->
            if (file.name == METADATA_FILE || isPackage(file.name) || file.name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    private fun open(address: String): HttpURLConnection =
        (URI(address).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, application/octet-stream")
            setRequestProperty("User-Agent", "Wolfy/$currentVersion ($platform)")
        }

    private fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun isNewer(candidate: String, current: String): Boolean {
        fun parse(value: String): List<Int>? {
            val parts = value.split('.')
            if (parts.size != 3) return null
            return parts.map { it.toIntOrNull() ?: return null }
        }
        val left = parse(candidate) ?: return false
        val right = parse(current) ?: return false
        for (index in left.indices) {
            if (left[index] != right[index]) return left[index] > right[index]
        }
        return false
    }

    /**
     * Файл пакета обновления — любой из трёх платформ.
     *
     * Список расширений повторялся в двух местах, и появление третьего формата
     * это заметило: `.deb` остался бы неубранным в одном из них, а мусор в
     * каталоге обновлений — это гигабайты, которые никто не ищет.
     */
    private fun isPackage(name: String): Boolean =
        PACKAGE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    private data class StagedUpdate(val manifest: UpdateManifest, val file: File)

    companion object {
        private const val INITIAL_DELAY_MS = 4_000L
        private const val RETRY_DELAY_MS = 15 * 60_000L
        private const val CHECK_DELAY_MS = 6 * 60 * 60_000L
        private const val MAX_PACKAGE_SIZE = 512L * 1024 * 1024
        private const val METADATA_FILE = "ready-update.json"
        private val PACKAGE_SUFFIXES = listOf(".msi", ".apk", ".deb")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class UpdateManifest(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val fileName: String = "",
)
