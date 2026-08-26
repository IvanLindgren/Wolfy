package com.wolfy.data.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FileLibraryStoreTest {
    @Test
    fun словарь_распаковывается_атомарно_и_проверяет_заголовок() {
        val directory = createTempDirectory("wolfy-store").toFile()
        try {
            val store = FileLibraryStore(directory)
            val body = "# wolfy english dictionary v2\n" +
                "library\tˈlaɪˌbɹɛɹi\tt|библиотека\tn|a room where books are kept\n"
            val path = store.installDictionary(gzip(body))

            assertEquals(path, store.dictionaryPath())
            assertTrue(java.io.File(path).readText().contains("library"))

            assertFails { store.installDictionary(gzip("not a wolfy dictionary")) }
            assertEquals(path, store.dictionaryPath(), "битая загрузка затёрла исправный файл")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fallback_rename_не_обнуляет_сохранённый_json() {
        val directory = createTempDirectory("wolfy-store-fallback").toFile()
        try {
            val expected = "{\"books\":[{\"id\":\"book-1\"}],\"revision\":7}"
            val store = FileLibraryStore(
                directory = directory,
                moveFile = { _, _, _ -> throw IOException("forced move failure") },
                renameFile = { source, target -> source.renameTo(target) },
            )

            store.save("library", expected)

            val saved = java.io.File(directory, "library.json")
            assertTrue(saved.isFile)
            assertEquals(expected, saved.readText(Charsets.UTF_8))
            assertTrue(saved.readBytes().contentEquals(expected.toByteArray(Charsets.UTF_8)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun снимок_дописывается_без_перезаписи_прежнего_текста() {
        val directory = createTempDirectory("wolfy-store-append").toFile()
        try {
            val store = FileLibraryStore(directory)
            store.writeText("snapshots.txt", "Первая страница")
            val path = store.appendText("snapshots.txt", "\n\nВторая страница")

            assertEquals("Первая страница\n\nВторая страница", java.io.File(path).readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return bytes.toByteArray()
    }
}
