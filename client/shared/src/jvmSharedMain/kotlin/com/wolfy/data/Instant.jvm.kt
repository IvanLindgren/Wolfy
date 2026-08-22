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

/** Часовой пояс устройства. Берётся при каждом вызове: читатель может лететь. */
private fun zone(): ZoneId = ZoneId.systemDefault()

actual fun localDay(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate().toEpochDay()

actual fun localHour(millis: Long): Int =
    Instant.ofEpochMilli(millis).atZone(zone()).hour

actual fun atLocalHour(from: Long, hour: Int): Long {
    val moment = Instant.ofEpochMilli(from).atZone(zone())
    val today = moment.toLocalDate().atStartOfDay(zone()).plusHours(hour.toLong())
    val chosen = if (today.toInstant().toEpochMilli() >= from) today else today.plusDays(1)
    return chosen.toInstant().toEpochMilli()
}
