package com.wolfy.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Windows SAPI через встроенный SpeechSynthesizer; отдельная модель не нужна. */
private class WindowsPronouncer : Pronouncer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null
    private var request = 0L

    override fun speak(text: String) {
        if (text.isBlank()) return
        val ticket = synchronized(this) {
            request += 1
            process?.destroy()
            process = null
            request
        }
        scope.launch {
            runCatching {
                val command = """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    ${'$'}english = ${'$'}voice.GetInstalledVoices() | ForEach-Object { ${'$'}_.VoiceInfo } | Where-Object { ${'$'}_.Culture.TwoLetterISOLanguageName -eq 'en' }
                    ${'$'}preferred = ${'$'}english | Sort-Object @{ Expression = { if (${'$'}_.Name -match 'Aria|Jenny|Sonia|Zira|Hazel|Natural') { 0 } else { 1 } } }, @{ Expression = { if (${'$'}_.Culture.Name -eq 'en-US') { 0 } else { 1 } } } | Select-Object -First 1
                    if (${'$'}preferred) { ${'$'}voice.SelectVoice(${'$'}preferred.Name) }
                    ${'$'}voice.Rate = -1
                    ${'$'}voice.Volume = 100
                    ${'$'}voice.Speak(${'$'}env:WOLFY_SPEAK_TEXT)
                    ${'$'}voice.Dispose()
                """.trimIndent()
                val launched = ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle",
                    "Hidden",
                    "-Command",
                    command,
                ).apply {
                    environment()["WOLFY_SPEAK_TEXT"] = text
                }.start()
                val current = synchronized(this@WindowsPronouncer) {
                    if (ticket == request) {
                        process = launched
                        true
                    } else {
                        false
                    }
                }
                if (!current) {
                    launched.destroy()
                    return@runCatching
                }
                launched.waitFor()
                synchronized(this@WindowsPronouncer) {
                    if (process === launched) process = null
                }
            }
        }
    }

    fun close() {
        synchronized(this) {
            request += 1
            process?.destroy()
            process = null
        }
        scope.cancel()
    }
}

@Composable
actual fun rememberPronouncer(): Pronouncer {
    val pronouncer = remember { WindowsPronouncer() }
    DisposableEffect(pronouncer) {
        onDispose(pronouncer::close)
    }
    return pronouncer
}
