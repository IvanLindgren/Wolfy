package com.wolfy.data

import java.io.File

/**
 * Демо-книга на JVM.
 *
 * Файл переписывается при каждом запуске: текст живёт в коде и может
 * поменяться вместе с версией приложения, а устаревшая копия на диске
 * показывала бы вчерашний вариант.
 */
actual fun writeDemoBook(): String {
    val file = File(System.getProperty("java.io.tmpdir"), "wolfy-demo.txt")
    file.writeText(DEMO_BOOK_TEXT, Charsets.UTF_8)
    return file.absolutePath
}
