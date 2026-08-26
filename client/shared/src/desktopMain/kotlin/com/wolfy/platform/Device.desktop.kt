package com.wolfy.platform

actual fun deviceName(): String = System.getenv("COMPUTERNAME")
    ?.takeIf { it.isNotBlank() }
    ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
    ?: "Windows"

actual fun devicePlatform(): String = if (
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
) "windows" else "linux"
