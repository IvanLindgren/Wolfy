package com.wolfy.data.library

import java.io.File

/**
 * Хранилище на Windows.
 *
 * Каталог берётся из `LOCALAPPDATA` — туда системы кладут данные приложений,
 * которые не нужно синхронизировать между машинами. Книги как раз такие:
 * файлы весят мегабайты, а перелётный профиль тащил бы их по сети при каждом
 * входе.
 *
 * Если переменной нет — а это бывает на других системах, — падать нельзя, и
 * каталог создаётся в домашнем.
 */
actual fun createLibraryStore(): LibraryStore {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getenv("XDG_DATA_HOME")
        ?: File(System.getProperty("user.home"), ".local/share").absolutePath

    val directory = File(base, "Wolfy")
    directory.mkdirs()
    return FileLibraryStore(directory)
}
