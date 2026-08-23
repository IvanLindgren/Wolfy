package com.wolfy.data.library

import java.io.ByteArrayOutputStream
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

    private fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return bytes.toByteArray()
    }
}
