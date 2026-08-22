package com.wolfy.data

import java.time.Instant
import java.time.format.DateTimeParseException

actual fun parseInstant(text: String): Long =
    try {
        if (text.isBlank()) 0L else Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        // Непонятная дата не должна ронять синхронизацию: карточка с нулевым
        // сроком просто покажется при ближайшем повторении.
        0L
    }

actual fun formatInstant(millis: Long): String =
    if (millis <= 0L) "" else Instant.ofEpochMilli(millis).toString()
