package com.wolfy.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Тихое радио под чтение.
 *
 * Зачем оно в читалке. Тишина помогает не всем: части людей ровный фоновый
 * звук нужен, чтобы перестать слышать всё остальное — соседей, улицу, себя.
 * Обычно это решается вторым приложением поверх первого, и тогда пауза,
 * громкость и выбор станции живут где-то ещё, а не там, где читают.
 *
 * Отсюда правила, которым подчинена вся эта часть.
 *
 * **Радио молчит по умолчанию.** Приложение, которое начинает звучать само,
 * закрывают быстрее, чем находят у него настройки.
 *
 * **Никаких слов.** Станции подобраны инструментальные: голос в фоне — это
 * второй текст, и читать под него нельзя. По той же причине здесь нет ни
 * новостей, ни подкастов, хотя технически это те же потоки.
 *
 * **Своя станция важнее наших.** У человека, который слушает под чтение,
 * почти наверняка уже есть любимый поток; список по умолчанию нужен тому, у
 * кого его нет.
 */

/** Радиостанция: имя, поток и одна строка о том, что это за звук. */
data class RadioStation(
    val id: String,
    val title: String,
    val hint: String,
    val url: String,
    /**
     * Кто вещает. Не украшение: у станций по умолчанию есть владелец, и
     * назвать его — условие, на котором ими можно пользоваться.
     */
    val source: String = "",
)

/** Что происходит с радио прямо сейчас. */
data class RadioState(
    val station: RadioStation? = null,
    /** Идёт ли звук. Между нажатием и первым звуком стоит [connecting]. */
    val playing: Boolean = false,
    val connecting: Boolean = false,
    /** Громкость от 0 до 1. Фон, а не музыка: по умолчанию тихо. */
    val volume: Float = 0.35f,
    /** Человеческое объяснение, почему звука нет. */
    val failure: String? = null,
)

/**
 * Проигрыватель потока.
 *
 * Один на приложение и живёт в его составе, а не в экране: радио не должно
 * замолкать оттого, что читатель ушёл в колоды.
 */
interface RadioPlayer {
    val state: StateFlow<RadioState>

    /** Включает станцию. Повторный вызов с той же станцией — это пауза. */
    fun play(station: RadioStation)

    fun stop()

    /** Громкость от 0 до 1; за пределами — прижимается к ним. */
    fun setVolume(volume: Float)

    /** Отпускает звуковое устройство. Зовётся при закрытии приложения. */
    fun release()
}

/**
 * Станции по умолчанию.
 *
 * Все — SomaFM: некоммерческое радио, которое разрешает слушать свои потоки
 * в чужих приложениях при условии, что названо имя станции и само SomaFM.
 * Имя стоит в [RadioStation.source] и показывается рядом со станцией.
 *
 * Пять штук, а не пятьдесят: список, который надо изучать, — это ещё одно
 * дело перед чтением, а радио заводили ровно для обратного.
 */
val DefaultStations: List<RadioStation> = listOf(
    RadioStation(
        id = "dronezone",
        title = "Drone Zone",
        hint = "Ровный эмбиент без событий, самый незаметный фон",
        url = "https://ice1.somafm.com/dronezone-128-mp3",
        source = "SomaFM",
    ),
    RadioStation(
        id = "groovesalad",
        title = "Groove Salad",
        hint = "Спокойный даунтемпо: чуть живее эмбиента",
        url = "https://ice1.somafm.com/groovesalad-128-mp3",
        source = "SomaFM",
    ),
    RadioStation(
        id = "deepspaceone",
        title = "Deep Space One",
        hint = "Тёмный космический эмбиент",
        url = "https://ice1.somafm.com/deepspaceone-128-mp3",
        source = "SomaFM",
    ),
    RadioStation(
        id = "lush",
        title = "Lush",
        hint = "Мечтательный поп с голосом, если он вам не мешает",
        url = "https://ice1.somafm.com/lush-128-mp3",
        source = "SomaFM",
    ),
    RadioStation(
        id = "fluid",
        title = "Fluid",
        hint = "Инструментальный хип-хоп, ровный ритм",
        url = "https://ice1.somafm.com/fluid-128-mp3",
        source = "SomaFM",
    ),
)

/** Станция читателя по адресу, который он ввёл сам. */
fun ownStation(url: String): RadioStation? {
    val clean = url.trim()
    if (!clean.startsWith("https://", ignoreCase = true)) return null
    return RadioStation(
        id = "own",
        title = "Своя станция",
        hint = clean,
        url = clean,
    )
}

expect fun createRadioPlayer(): RadioPlayer
