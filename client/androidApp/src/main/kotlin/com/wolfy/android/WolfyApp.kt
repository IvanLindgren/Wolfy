package com.wolfy.android

import android.app.Application
import com.wolfy.data.library.initializeStorage
import com.wolfy.platform.initializeReminders

/**
 * Приложение Android.
 *
 * Ядро на Rust здесь намеренно не трогается: оно грузится лениво, при первом
 * обращении. Загрузка библиотеки и разбор словаря на семьдесят восемь тысяч
 * слов заняли бы время старта, а до библиотеки пользователь дойдёт на кадр
 * позже.
 */
class WolfyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Каталог приложения доступен только через Context, а общий код его не
        // видит. Кладём путь один раз здесь — до первого обращения к
        // библиотеке ещё далеко, а тащить Context через всю общую часть
        // значило бы объяснять Windows, что это такое.
        initializeStorage(this)
        // Канал уведомлений и адрес приложения для будильника. Тоже один раз
        // и тоже здесь: приёмник напоминания просыпается в процессе, который
        // может не дойти ни до одного экрана.
        initializeReminders(this)
    }
}
