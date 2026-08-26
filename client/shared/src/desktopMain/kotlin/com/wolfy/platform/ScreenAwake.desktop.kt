package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.sun.jna.Library
import com.sun.jna.Native

/**
 * Просит Windows не гасить экран, пока открыта книга.
 *
 * Читают и с ноутбука, и там таймер гашения обычно короче телефонного. Способ
 * тот же, которым пользуются проигрыватели видео: сказать системе, что экран
 * нужен, и не трогать ни мышь, ни таймеры. Хранителю экрана и засыпанию это
 * мешает ровно на то время, пока книга открыта.
 *
 * Состояние привязано к потоку, который его выставил. Оба обращения идут из
 * одного эффекта композиции, а он живёт на потоке интерфейса, — значит, снимет
 * запрет тот же поток, который его поставил.
 *
 * Ничего не вышло — читаем дальше. Экран, который гаснет по системным
 * правилам, это неудобство, а не повод не открыть книгу; на не-Windows системах
 * такой функции нет вовсе.
 */
@Composable
actual fun KeepScreenAwake() {
    DisposableEffect(Unit) {
        val kernel = kernel32
        kernel?.SetThreadExecutionState(ES_CONTINUOUS or ES_DISPLAY_REQUIRED)
        onDispose { kernel?.SetThreadExecutionState(ES_CONTINUOUS) }
    }
}

private interface Kernel32Awake : Library {
    @Suppress("FunctionNaming")
    fun SetThreadExecutionState(flags: Int): Int
}

/**
 * Библиотека грузится один раз и только на Windows.
 *
 * `by lazy` здесь не про скорость: `Native.load` на чужой системе бросает
 * исключение, и повторять эту попытку при каждом открытии книги незачем.
 */
private val kernel32: Kernel32Awake? by lazy {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy null
    runCatching { Native.load("kernel32", Kernel32Awake::class.java) }.getOrNull()
}

/** Запрос остаётся в силе, пока его не снимут. */
private const val ES_CONTINUOUS = -0x80000000

/** Нужен именно экран: спать нельзя и гаснуть нельзя. */
private const val ES_DISPLAY_REQUIRED = 0x00000002
