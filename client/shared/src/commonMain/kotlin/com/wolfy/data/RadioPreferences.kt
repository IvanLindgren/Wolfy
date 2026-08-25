package com.wolfy.data

import com.wolfy.data.library.LibraryStore
import com.wolfy.platform.DefaultStations
import com.wolfy.platform.RadioStation
import com.wolfy.platform.ownStation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Выбор станции и громкость.
 *
 * Хранится на устройстве и **не синхронизируется**, в отличие от настроек
 * чтения. Это не недоделка: за компьютером человек сидит в тишине кабинета, а
 * в метро с телефоном — в чужом шуме, и громкость, подобранная там, за столом
 * оказывается либо неслышной, либо оглушительной. То же и со станцией.
 *
 * Лежит отдельной записью рядом с библиотекой: громкость трогают каждый вечер,
 * а список книг — раз в неделю, и переписывать его ради ползунка незачем.
 */
@Serializable
data class RadioPreferences(
    /** Номер станции по умолчанию либо `own` для своей. Пусто — ничего не выбрано. */
    val stationId: String = "",
    /** Адрес своей станции, если читатель её вводил. */
    val ownUrl: String = "",
    val volume: Float = 0.35f,
    /**
     * Включать ли радио сразу при открытии книги.
     *
     * По умолчанию нет: приложение, которое начинает звучать само, закрывают
     * быстрее, чем находят у него настройки.
     */
    val autoStart: Boolean = false,
) {
    /** Выбранная станция или `null`, если выбора ещё не было. */
    fun station(): RadioStation? = when {
        stationId.isBlank() -> null
        stationId == "own" -> ownStation(ownUrl)
        else -> DefaultStations.firstOrNull { it.id == stationId }
    }
}

private const val RECORD = "radio"

private val radioJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Читает настройки радио.
 *
 * Битая запись читается как пустая, и это тот редкий случай, когда молчаливое
 * умолчание уместно: цена ошибки — заново выбранная станция, а не потерянная
 * книга.
 */
fun LibraryStore.loadRadio(): RadioPreferences {
    val raw = runCatching { load(RECORD) }.getOrNull() ?: return RadioPreferences()
    if (raw.isBlank()) return RadioPreferences()
    return runCatching { radioJson.decodeFromString<RadioPreferences>(raw) }
        .getOrElse { RadioPreferences() }
}

fun LibraryStore.saveRadio(preferences: RadioPreferences) {
    runCatching { save(RECORD, radioJson.encodeToString(preferences)) }
}
