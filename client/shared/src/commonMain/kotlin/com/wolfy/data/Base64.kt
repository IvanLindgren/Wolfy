package com.wolfy.data

/**
 * Base64 для снимка страницы.
 *
 * Своя реализация вместо платформенной: `java.util.Base64` есть на обеих
 * платформах, но на Android он появился только в API 26 и живёт под другим
 * именем в старых версиях, а `kotlin.io.encoding` до сих пор помечен как
 * экспериментальный. Двадцать строк надёжнее оговорок в двух местах.
 */
private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun encodeBase64(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0

    while (index + 2 < bytes.size) {
        val chunk = (bytes[index].toInt() and 0xFF shl 16) or
            (bytes[index + 1].toInt() and 0xFF shl 8) or
            (bytes[index + 2].toInt() and 0xFF)
        out.append(ALPHABET[chunk shr 18 and 0x3F])
        out.append(ALPHABET[chunk shr 12 and 0x3F])
        out.append(ALPHABET[chunk shr 6 and 0x3F])
        out.append(ALPHABET[chunk and 0x3F])
        index += 3
    }

    // Хвост: один или два байта. Недостающие позиции добиваются знаком «=»,
    // иначе принимающая сторона не поймёт, где кончились данные.
    when (bytes.size - index) {
        1 -> {
            val chunk = bytes[index].toInt() and 0xFF shl 16
            out.append(ALPHABET[chunk shr 18 and 0x3F])
            out.append(ALPHABET[chunk shr 12 and 0x3F])
            out.append("==")
        }

        2 -> {
            val chunk = (bytes[index].toInt() and 0xFF shl 16) or
                (bytes[index + 1].toInt() and 0xFF shl 8)
            out.append(ALPHABET[chunk shr 18 and 0x3F])
            out.append(ALPHABET[chunk shr 12 and 0x3F])
            out.append(ALPHABET[chunk shr 6 and 0x3F])
            out.append('=')
        }
    }

    return out.toString()
}
