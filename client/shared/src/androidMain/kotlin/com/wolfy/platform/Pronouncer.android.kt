package com.wolfy.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private class AndroidPronouncer(context: Context) : Pronouncer, TextToSpeech.OnInitListener {
    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val current = engine
        ready = status == TextToSpeech.SUCCESS &&
            (current?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0
        if (!ready || current == null) return

        // Выбираем самый качественный установленный английский голос. Сетевой
        // голос не берём: кнопка произношения обязана работать и в самолёте.
        current.voices
            ?.asSequence()
            ?.filter { it.locale.language == Locale.ENGLISH.language }
            ?.filterNot { it.isNetworkConnectionRequired }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency })
            ?.firstOrNull()
            ?.let { current.voice = it }
        current.setSpeechRate(0.90f)
        current.setPitch(1.02f)
    }

    override fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wolfy-word")
        }
    }

    fun close() {
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

@Composable
actual fun rememberPronouncer(): Pronouncer {
    val context = LocalContext.current
    val pronouncer = remember(context) { AndroidPronouncer(context) }
    DisposableEffect(pronouncer) {
        onDispose(pronouncer::close)
    }
    return pronouncer
}
