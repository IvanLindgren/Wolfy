package com.wolfy.data

import java.security.MessageDigest

actual class IncrementalSha256 actual constructor() {
    private val digest = MessageDigest.getInstance("SHA-256")
    actual fun update(bytes: ByteArray) { digest.update(bytes) }
    actual fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
}
