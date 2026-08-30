package com.wolfy.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Погружение в чтение: оснастка уходит, пока читают.
 *
 * ## Зачем
 *
 * Вокруг текста стояли четыре горизонтальные полосы сразу — шапка книги,
 * полоса внимания, полка инструментов и нижняя навигация приложения. На
 * телефоне это около трети экрана, и треть эта занята не книгой. Хуже, что
 * нижняя навигация — глобальная: «Книги, Полки, Лента, Карточки, Ещё» посреди
 * романа не помогают читать, а предлагают перестать.
 *
 * Читалка, которая занимает собой треть страницы, не выполняет свою
 * единственную обязанность. Поэтому по умолчанию видно только книгу, а
 * оснастка приезжает, когда за ней тянутся.
 *
 * ## Почему прокруткой, а не касанием
 *
 * Касание по странице уже занято: тап по слову открывает разбор, и это главное
 * действие продукта. Отдавать ему же показ шапки значит либо отнять разбор,
 * либо заставить читателя целиться в поля.
 *
 * Прокрутка свободна и говорит о намерении прямо: вниз — читаю дальше, оснастка
 * не нужна; вверх — остановился и что-то ищу, оснастка нужна. Порог возврата
 * вдвое короче порога ухода: спрятать нужно уверенно, а достать — легко.
 *
 * Отдельно оговорено начало главы. Там оснастка видна всегда: читатель только
 * что открыл книгу или перешёл к новой главе, ему нужно понять, где он и как
 * отсюда выйти, и прятать от него выход в этот момент — недружелюбно.
 */
@Stable
internal class ReadingImmersion internal constructor(
    private val hideTravelPx: Float,
    private val revealTravelPx: Float,
) {
    /** Видна ли оснастка. Всё, что снаружи, смотрит только сюда. */
    var chromeVisible by mutableStateOf(true)
        private set

    /**
     * Пройденное расстояние в одну сторону.
     *
     * Обычное поле, а не состояние: его читает только сам обработчик, и снимок
     * на каждом кадре протяжки не нужен никому.
     */
    private var travel = 0f

    /** Показать немедленно: открылась карточка, сменилась глава, пришёл конец. */
    fun reveal() {
        travel = 0f
        chromeVisible = true
    }

    internal fun onScroll(delta: Float) {
        // Смена направления обнуляет счёт. Иначе «немного вниз, немного вверх»
        // копилось бы в одну сторону и срабатывало на дрожании пальца.
        if (delta < 0f && travel > 0f) travel = 0f
        if (delta > 0f && travel < 0f) travel = 0f
        travel += delta
        when {
            travel <= -hideTravelPx -> {
                chromeVisible = false
                travel = 0f
            }
            travel >= revealTravelPx -> {
                chromeVisible = true
                travel = 0f
            }
        }
    }

    /** Слушатель прокрутки тела книги. Ставится на общий контейнер экрана. */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            onScroll(available.y)
            // Ничего не поглощаем: это наблюдатель, а не участник прокрутки.
            return Offset.Zero
        }
    }
}

/**
 * @param scroll прокрутка тела главы: по ней узнаётся начало главы.
 * @param locked оснастка обязана быть видна — поверх страницы что-то открыто.
 * @param chapterKey смена главы; на новой оснастка показывается заново.
 */
@Composable
internal fun rememberReadingImmersion(
    scroll: LazyListState,
    locked: Boolean,
    chapterKey: Int,
    hideTravel: Dp = HIDE_TRAVEL,
    revealTravel: Dp = REVEAL_TRAVEL,
): ReadingImmersion {
    val density = LocalDensity.current
    val immersion = remember(density, hideTravel, revealTravel) {
        with(density) { ReadingImmersion(hideTravel.toPx(), revealTravel.toPx()) }
    }

    // Начало главы. Проверяется по состоянию списка, а не по счётчику
    // прокруток: читатель попадает сюда и открытием книги, и переходом по
    // оглавлению, и возвратом к началу с клавиатуры.
    //
    // Через derivedStateOf, а не прямым чтением: позиция прокрутки меняется
    // на каждом кадре, и прямое чтение перезапускало бы композицию всей
    // читалки всю дорогу вниз по главе. Здесь же наружу выходит один
    // логический признак, и он меняется дважды за главу.
    val atTop by remember(scroll) {
        derivedStateOf {
            scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(atTop, chapterKey, locked) {
        if (atTop || locked) immersion.reveal()
    }
    return immersion
}

/**
 * Пороги хода.
 *
 * Уход — около двух строк набора: столько читатель проезжает, когда уже начал
 * читать, а не примеривается. Возврат вдвое короче: за оснасткой тянутся
 * намеренно, и заставлять для этого прокручивать полстраницы назад незачем.
 */
private val HIDE_TRAVEL = 56.dp
private val REVEAL_TRAVEL = 24.dp
