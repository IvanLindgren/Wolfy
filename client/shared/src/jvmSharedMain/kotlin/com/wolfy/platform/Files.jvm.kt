package com.wolfy.platform

import java.io.File

actual fun readBytes(path: String): ByteArray? {
    val file = File(path)
    return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
}
