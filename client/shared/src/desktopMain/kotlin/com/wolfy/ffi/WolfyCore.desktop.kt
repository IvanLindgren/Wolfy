package com.wolfy.ffi

import java.io.File

/**
 * Ядро на Windows.
 *
 * Библиотека лежит в двух разных местах, и оба нормальные. При запуске из
 * исходников её кладёт задача `copyCoreLibrary`, и путь туда сборка передаёт
 * через `-Djna.library.path`. В установленном приложении она приезжает
 * ресурсом приложения, а куда именно распаковщик её положил — знает только сам
 * установленный экземпляр, и говорит он об этом системным свойством
 * `compose.application.resources.dir`.
 *
 * Поэтому путь не «зашит» ни там, ни тут: код читает то, что ему сказали, и
 * добавляет к поиску. Искать библиотеку самому, обходя каталоги, было бы
 * гаданием — а гадание однажды находит не ту.
 *
 * Экземпляр один на приложение: ядро держит внутри разобранный словарь на
 * семьдесят восемь тысяч слов, и загружать его дважды значило бы удвоить
 * расход памяти на ровном месте.
 */
private val core: WolfyCore by lazy {
    addBundledLibraryPath()
    loadCore()
}

actual fun createWolfyCore(): WolfyCore = core

/**
 * Добавляет к поиску каталог ресурсов установленного приложения.
 *
 * Свойство читается один раз и до первой загрузки: JNA смотрит на
 * `jna.library.path` в момент загрузки библиотеки, и менять его после уже
 * бессмысленно. Если свойства нет — приложение запущено из исходников, и путь
 * туда уже задан сборкой.
 */
private fun addBundledLibraryPath() {
    val resources = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }
        ?: return
    if (!File(resources).isDirectory) return

    val existing = System.getProperty("jna.library.path").orEmpty()
    val path = if (existing.isBlank()) resources else resources + File.pathSeparator + existing
    System.setProperty("jna.library.path", path)
}
