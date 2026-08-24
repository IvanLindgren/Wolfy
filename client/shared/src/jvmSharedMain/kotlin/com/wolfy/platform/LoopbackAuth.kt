package com.wolfy.platform

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun launchLoopbackAuth(
    openBrowser: (String) -> Boolean,
    start: suspend (String) -> String,
): BrowserAuthResult = withContext(Dispatchers.IO) {
    ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
        server.soTimeout = AUTH_TIMEOUT_MILLIS
        val secretPath = "/wolfy/oauth/${UUID.randomUUID()}"
        val returnUrl = "http://127.0.0.1:${server.localPort}$secretPath"
        val authorizationUrl = start(returnUrl)
        check(openBrowser(authorizationUrl)) { "Не удалось открыть системный браузер." }

        repeat(6) {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine().orEmpty()
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val target = parts.getOrNull(1).orEmpty()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, 65_536) ?: 0
                    }
                }
                val uri = runCatching { URI(target) }.getOrNull()
                if (uri?.path != secretPath) {
                    respond(socket, 404, "Страница не найдена.")
                    return@use
                }
                val parameters = when (method) {
                    "GET" -> decodeForm(uri.rawQuery.orEmpty())
                    "POST" -> {
                        val body = CharArray(contentLength)
                        var offset = 0
                        while (offset < body.size) {
                            val read = reader.read(body, offset, body.size - offset)
                            if (read < 0) break
                            offset += read
                        }
                        decodeForm(String(body, 0, offset))
                    }
                    else -> emptyMap()
                }
                respond(socket, 200, "Вход завершён. Можно закрыть эту вкладку и вернуться в Wolfy.")
                return@withContext BrowserAuthResult(parameters)
            }
        }
        error("Браузер не вернул результат входа.")
    }
}

private fun decodeForm(value: String): Map<String, String> = value
    .split('&')
    .filter(String::isNotBlank)
    .associate { part ->
        val key = URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8)
        val content = URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8)
        key to content
    }

private fun respond(socket: java.net.Socket, status: Int, message: String) {
    val body = "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><title>Wolfy</title>" +
        "<body style=\"font-family:system-ui,sans-serif;padding:3rem\"><h1>Wolfy</h1><p>$message</p></body></html>"
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    val headers = buildString {
        append("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
        append("Content-Type: text/html; charset=utf-8\r\n")
        append("Content-Length: ${bytes.size}\r\n")
        append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    socket.getOutputStream().use { output ->
        output.write(headers)
        output.write(bytes)
        output.flush()
    }
}

private const val AUTH_TIMEOUT_MILLIS = 180_000
