package com.wolfy.platform

import android.media.AudioManager
import android.media.ToneGenerator

private val companionTones by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 18) }

actual fun playCompanionSound(sound: CompanionSound) {
    runCatching {
        val tone = when (sound) {
            CompanionSound.Reveal -> ToneGenerator.TONE_PROP_BEEP
            CompanionSound.Reaction -> ToneGenerator.TONE_PROP_ACK
            CompanionSound.Ready -> ToneGenerator.TONE_PROP_BEEP2
        }
        companionTones.startTone(tone, if (sound == CompanionSound.Ready) 110 else 70)
    }
}
