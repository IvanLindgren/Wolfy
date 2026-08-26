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
                launchInstaller = { true },
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
}
