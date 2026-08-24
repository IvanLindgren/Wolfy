package com.wolfy.platform

import android.os.Build

actual fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
    .filter { it.isNotBlank() }
    .joinToString(" ")
    .ifBlank { "Android" }

actual fun devicePlatform(): String = "android"
