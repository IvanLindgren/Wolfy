package com.wolfy.platform

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseDownloaderTest {
    @Test
    fun downloadsVerifiesAndStagesPackage() = runBlocking {
        val bytes = "verified-msi-placeholder".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/update/latest") { exchange ->
            val json = """{"version":"1.0.8","url":"/v1/update/files/Wolfy-1.0.8.msi","sha256":"$sha","size":${bytes.size}}"""
            val body = json.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/update/files/Wolfy-1.0.8.msi") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val directory = createTempDirectory("wolfy-update-test").toFile()
            val downloader = ReleaseDownloader(
                serverUrl = "http://127.0.0.1:${server.address.port}",
                currentVersion = "1.0.7",
                platform = "windows",
                directory = directory,
                launchInstaller = { InstallOutcome.Restarting },
            )
            downloader.checkNow()
            val available = withTimeout(5_000) {
                downloader.state.first { it is AppUpdateState.Available }
            } as AppUpdateState.Available
            assertEquals("1.0.8", available.version)
            // Проверка не скачивает MSI сама: сеть и место на диске тратятся
            // только после явного решения пользователя.
            assertTrue(!directory.resolve("Wolfy-1.0.8.msi").exists())

            downloader.install()
            val ready = withTimeout(5_000) {
                downloader.state.first { it is AppUpdateState.Ready }
            } as AppUpdateState.Ready

            assertEquals("1.0.8", ready.version)
            assertTrue(directory.resolve("Wolfy-1.0.8.msi").readBytes().contentEquals(bytes))
            assertTrue(directory.resolve("ready-update.json").isFile)
            directory.deleteRecursively()
        } finally {
            server.stop(0)
        }
        Unit
    }

    /**
     * Отказ установщика доезжает до читателя.
     *
     * Раньше `install()` возвращал `false` и на повреждённом пакете, и на
     * ненайденном установщике, и на отказе прав — а состояние оставалось
     * `Ready`. Со стороны читателя это выглядело так: нажал «перезапустить»,
     * ничего не произошло, нажал ещё раз, снова ничего. Причина у приложения
     * была, и оно о ней молчало.
     */
    @Test
    fun отказ_установщика_становится_видимой_причиной() = runBlocking {
        val bytes = "verified-package".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/update/latest") { exchange ->
            val json = """{"version":"2.0.0","url":"/v1/update/files/pkg","sha256":"$sha","size":${bytes.size}}"""
            val body = json.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/update/files/pkg") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val directory = createTempDirectory("wolfy-update-refused").toFile()
            val downloader = ReleaseDownloader(
                serverUrl = "http://127.0.0.1:${server.address.port}",
                currentVersion = "1.0.0",
                platform = "linux",
                directory = directory,
                launchInstaller = { error("нет доступа к каталогу установки") },
            )
            downloader.checkNow()
            withTimeout(5_000) { downloader.state.first { it is AppUpdateState.Available } }
            // Первое нажатие только качает: пакет ещё не готов.
            downloader.install()
            withTimeout(5_000) { downloader.state.first { it is AppUpdateState.Ready } }
            // Linux получает DEB, а не MSI: платформа выбирает имя пакета.
            assertTrue(directory.resolve("Wolfy-2.0.0.deb").isFile)

            assertTrue(!downloader.install())
            val failed = withTimeout(5_000) {
                downloader.state.first { it is AppUpdateState.Failed }
            } as AppUpdateState.Failed
            assertTrue(
                failed.reason.contains("нет доступа"),
                "причина отказа потерялась: ${failed.reason}",
            )
            directory.deleteRecursively()
        } finally {
            server.stop(0)
        }
        Unit
    }

    /** Передача пакета системе не выдаётся за перезапуск и не гасит кнопку. */
    @Test
    fun передача_системе_оставляет_обновление_готовым() = runBlocking {
        val bytes = "handed-package".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/update/latest") { exchange ->
            val json = """{"version":"2.1.0","url":"/v1/update/files/pkg","sha256":"$sha","size":${bytes.size}}"""
            val body = json.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/update/files/pkg") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val directory = createTempDirectory("wolfy-update-handed").toFile()
            val downloader = ReleaseDownloader(
                serverUrl = "http://127.0.0.1:${server.address.port}",
                currentVersion = "1.0.0",
                platform = "linux",
                directory = directory,
                launchInstaller = { InstallOutcome.HandedToSystem },
            )
            downloader.checkNow()
            withTimeout(5_000) { downloader.state.first { it is AppUpdateState.Available } }
            downloader.install()
            withTimeout(5_000) { downloader.state.first { it is AppUpdateState.Ready } }

            // Установку ведёт менеджер пакетов, приложение не закрывается: окно
            // установщика ещё можно отменить, и обрывать чтение зря незачем.
            assertTrue(!downloader.install())
            assertTrue(downloader.state.value is AppUpdateState.Ready)
            directory.deleteRecursively()
        } finally {
            server.stop(0)
        }
        Unit
    }
}
