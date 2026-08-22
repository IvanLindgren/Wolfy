package com.wolfy.data.library

import android.content.Context
import java.io.File

/**
 * Хранилище на Android.
 *
 * Каталог приложения доступен только через `Context`, а `Context` не достать
 * из общего кода — поэтому его кладёт сюда `WolfyApp` при старте. Способ
 * простой до неприличия, но альтернативы хуже: тащить `Context` параметром
 * через всю общую часть значит объяснять Windows, что это такое, а внедрять
 * ради одного значения библиотеку внедрения зависимостей — стрелять из пушки.
 */
private var applicationDirectory: File? = null

/** Вызывается один раз при старте приложения. */
fun initializeStorage(context: Context) {
    applicationDirectory = context.applicationContext.filesDir
}

actual fun createLibraryStore(): LibraryStore {
    val directory = applicationDirectory
        ?: error("хранилище не готово: вызовите initializeStorage() в Application.onCreate")
    return FileLibraryStore(directory)
}
