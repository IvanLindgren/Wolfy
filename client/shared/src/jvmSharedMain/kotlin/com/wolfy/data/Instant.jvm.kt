package com.wolfy.data

import java.time.Instant
import java.time.ZoneId
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

/**
 * Сдвиг местного времени от UTC в минутах.
 *
 * Пояс берётся при каждом вызове, а не запоминается: читатель может лететь,
 * и переход на летнее время тоже никто не отменял. Сдвиг зависит от момента —
 * в июле и в январе он разный, — поэтому и спрашивается для конкретного `at`.
 */
actual fun utcOffsetMinutes(at: Long): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds / 60
