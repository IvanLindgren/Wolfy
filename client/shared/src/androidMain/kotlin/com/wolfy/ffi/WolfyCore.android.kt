package com.wolfy.ffi

/**
 * Ядро на Android.
 *
 * Библиотека лежит в пакете приложения (`jniLibs/<abi>/libwolfy_core.so`), и
 * JNA находит её там сама — искать путь вручную не нужно.
 *
 * Экземпляр один на приложение: ядро держит внутри разобранный словарь на
 * семьдесят восемь тысяч слов, и загружать его дважды значило бы удвоить
 * расход памяти на ровном месте.
 */
private val core: WolfyCore by lazy { loadCore() }

actual fun createWolfyCore(): WolfyCore = core
