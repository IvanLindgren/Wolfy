package com.wolfy.platform

import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin

private val companionAudio = Executors.newSingleThreadExecutor { task ->
    Thread(task, "wolfy-companion-sound").apply { isDaemon = true }
}

actual fun playCompanionSound(sound: CompanionSound) {
    companionAudio.execute {
        runCatching {
            val notes = when (sound) {
                CompanionSound.Reveal -> listOf(392.0 to 55)
                CompanionSound.Reaction -> listOf(330.0 to 45, 440.0 to 55)
                CompanionSound.Ready -> listOf(392.0 to 55, 523.0 to 85)
            }
            for ((frequency, durationMs) in notes) playSoftTone(frequency, durationMs)
        }
    }
}

private fun playSoftTone(frequency: Double, durationMs: Int) {
    val rate = 22_050
    val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
    val info = DataLine.Info(SourceDataLine::class.java, format)
    val line = AudioSystem.getLine(info) as SourceDataLine
    line.open(format)
    line.start()
    val samples = rate * durationMs / 1_000
    val bytes = ByteArray(samples * 2)
    for (index in 0 until samples) {
        val envelope = minOf(index / 120.0, (samples - index) / 160.0, 1.0).coerceAtLeast(0.0)
        val sample = (sin(2.0 * PI * frequency * index / rate) * envelope * 2_400).toInt()
        bytes[index * 2] = sample.toByte()
        bytes[index * 2 + 1] = (sample shr 8).toByte()
    }
    line.write(bytes, 0, bytes.size)
    line.drain()
    line.close()
}
