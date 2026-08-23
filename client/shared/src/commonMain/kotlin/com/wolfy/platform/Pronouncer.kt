package com.wolfy.platform

import androidx.compose.runtime.Composable

/** Произносит английское слово системным голосом без сетевого сервиса. */
fun interface Pronouncer {
    fun speak(text: String)
}

/** Голос текущей платформы с жизненным циклом, привязанным к композиции. */
@Composable
expect fun rememberPronouncer(): Pronouncer
