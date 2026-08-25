package com.wolfy.platform

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

/**
 * Радио на Windows.
 *
 * В JDK нет декодера MP3: `javax.sound.sampled` читает WAV и AIFF, а
 * радиопоток — это MP3. Поэтому кадры разбирает jlayer, а звук отдаётся
 * системе уже разжатым, через обычную звуковую линию.
 *
 * Поток читается вручную, кадр за кадром, в своей корутине на
 * [Dispatchers.IO]. Готового «проиграй этот адрес» на настольной JVM нет, и
 * попытка изобразить его через `AudioSystem.getAudioInputStream` кончилась бы
 * `UnsupportedAudioFileException` на первом же байте.
 *
 * Громкость меняется на линии, а не в декодере: у линии для этого есть
 * системный регулятор, и он работает мгновенно, не трогая уже разжатые кадры.
 */
private class DesktopRadio : RadioPlayer {
    private val _state = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stream: Job? = null

    /**
     * Линия, на которую сейчас идёт звук.
     *
     * Держится отдельно от корутины, потому что громкость меняют из другого
     * потока: ждать, пока поток дойдёт до следующего кадра, читателю незачем.
     */
    @Volatile
    private var line: SourceDataLine? = null

    override fun play(station: RadioStation) {
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

        stream = scope.launch {
            try {
                open(station.url).use { source -> pump(source) }
            } catch (error: Throwable) {
                // Отмена — это не сбой: читатель сам выключил радио.
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.value = _state.value.copy(
                    playing = false,
                    connecting = false,
                    failure = "Станция не отвечает. Проверьте сеть или выберите другую.",
                )
            } finally {
                closeLine()
            }
        }
    }

    override fun stop() {
        stream?.cancel()
        stream = null
        closeLine()
        _state.value = _state.value.copy(playing = false, connecting = false)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.value = _state.value.copy(volume = clamped)
        applyVolume(line, clamped)
    }

    override fun release() {
        stop()
        scope.cancel()
    }

    /** Открывает поток. Пять перенаправлений — предел: дальше это петля. */
    private fun open(url: String): InputStream {
        var address = URI(url).toURL()
        repeat(6) {
            val connection = address.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            // Icecast без этого заголовка вставляет в поток метаданные трека,
            // и декодер спотыкается о них как о повреждённый кадр.
            connection.setRequestProperty("Icy-MetaData", "0")
            connection.setRequestProperty("User-Agent", "Wolfy/1.0")

            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                if (next.isNullOrBlank()) error("станция перенаправила в никуда")
                address = URI(next).toURL()
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                error("станция ответила $code")
            }
            return BufferedInputStream(connection.inputStream, 64 * 1024)
        }
        error("слишком много перенаправлений")
    }

    /** Разбирает кадры и отдаёт их звуковой линии, пока корутина жива. */
    private suspend fun pump(source: InputStream) {
        val bitstream = Bitstream(source)
        val decoder = Decoder()

        while (true) {
            currentCoroutineContextEnsureActive()
            val header = bitstream.readFrame() ?: break
            val samples = decoder.decodeFrame(header, bitstream) as SampleBuffer

            val target = line ?: openLine(samples.sampleFrequency, samples.channelCount).also {
                line = it
                applyVolume(it, _state.value.volume)
                _state.value = _state.value.copy(playing = true, connecting = false)
            }

            // Кадр приходит массивом `short`; линия ждёт байты в порядке
            // little-endian — тот же, что объявлен в формате линии.
            val buffer = samples.buffer
            val bytes = ByteArray(samples.buffer.size * 2)
            for (at in 0 until samples.buffer.size) {
                val value = buffer[at].toInt()
                bytes[at * 2] = (value and 0xFF).toByte()
                bytes[at * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            target.write(bytes, 0, bytes.size)
            bitstream.closeFrame()
        }
    }

    private suspend fun currentCoroutineContextEnsureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }

    private fun openLine(rate: Int, channels: Int): SourceDataLine {
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val opened = AudioSystem.getLine(info) as SourceDataLine
        opened.open(format)
        opened.start()
        return opened
    }

    private fun closeLine() {
        val current = line ?: return
        line = null
        runCatching { current.stop() }
        runCatching { current.flush() }
        runCatching { current.close() }
    }

    /**
     * Ставит громкость на линию.
     *
     * Регулятор линии считает децибелы, а не доли: половина громкости — это
     * не половина мощности, и линейная шкала на слух даёт почти неслышную
     * первую половину ползунка. Поэтому доля переводится в децибелы, а ноль
     * уводится в самый низ, какой линия допускает.
     */
    private fun applyVolume(target: SourceDataLine?, volume: Float) {
        val control = target?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }
            ?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
        val decibels = if (volume <= 0f) {
            control.minimum
        } else {
            (20.0 * kotlin.math.log10(volume.toDouble())).toFloat()
        }
        control.value = decibels.coerceIn(control.minimum, control.maximum)
    }
}

actual fun createRadioPlayer(): RadioPlayer = DesktopRadio()
