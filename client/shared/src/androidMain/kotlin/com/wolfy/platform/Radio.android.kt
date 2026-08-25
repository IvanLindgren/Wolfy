package com.wolfy.platform

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Радио на Android.
 *
 * `MediaPlayer`, а не ExoPlayer: поток — это один MP3-адрес, который надо
 * открыть и не трогать. Всё, ради чего берут ExoPlayer, — адаптивный битрейт,
 * DASH, буферизация с прокруткой — здесь не нужно вовсе, а тянуть за собой
 * несколько мегабайт ради `setDataSource` не стоит.
 *
 * Атрибуты звука объявлены явно: без них система считает поток
 * «уведомлением», и он звучит поверх музыки читателя, вместо того чтобы
 * делить с ней громкость.
 */
private class AndroidRadio : RadioPlayer {
    private val _state = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = _state.asStateFlow()

    private var player: MediaPlayer? = null

    override fun play(station: RadioStation) {
        // Повторное нажатие на играющую станцию — это пауза. Отдельной кнопки
        // паузы нет нарочно: у фона два состояния, и второй элемент управления
        // только удлинил бы список.
        if (_state.value.station?.id == station.id && _state.value.playing) {
            stop()
            return
        }

        stop()
        _state.value = _state.value.copy(
            station = station,
            connecting = true,
            playing = false,
            failure = null,
        )

        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            next.setDataSource(station.url)
            val volume = _state.value.volume
            next.setVolume(volume, volume)
            next.setOnPreparedListener {
                // Станция могла смениться, пока эта соединялась: старый поток
                // не должен зазвучать поверх нового.
                if (player !== next) {
                    it.release()
                    return@setOnPreparedListener
                }
                it.start()
                _state.value = _state.value.copy(playing = true, connecting = false)
            }
            next.setOnErrorListener { _, _, _ ->
                fail("Станция не отвечает. Проверьте сеть или выберите другую.")
                true
            }
            next.prepareAsync()
        } catch (error: Throwable) {
            fail(error.message ?: "Станцию не удалось открыть.")
        }
    }

    override fun stop() {
        val current = player ?: run {
            _state.value = _state.value.copy(playing = false, connecting = false)
            return
        }
        player = null
        runCatching { current.stop() }
        runCatching { current.release() }
        _state.value = _state.value.copy(playing = false, connecting = false)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.value = _state.value.copy(volume = clamped)
        runCatching { player?.setVolume(clamped, clamped) }
    }

    override fun release() = stop()

    private fun fail(message: String) {
        runCatching { player?.release() }
        player = null
        _state.value = _state.value.copy(
            playing = false,
            connecting = false,
            failure = message,
        )
    }
}

actual fun createRadioPlayer(): RadioPlayer = AndroidRadio()
