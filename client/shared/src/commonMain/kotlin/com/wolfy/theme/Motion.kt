package com.wolfy.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/** Единый темп всех осмысленных движений интерфейса. */
@Immutable
data class WolfyMotion(
    val instant: Int = 90,
    val quick: Int = 180,
    val calm: Int = 280,
    val flight: Int = 560,
    val stagger: Int = 40,
)

object Curves {
    val Paper = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
    val Toss = CubicBezierEasing(0.35f, 0f, 0.1f, 1f)
}

val NoMotion = WolfyMotion(0, 0, 0, 0, 0)

/**
 * Читатель попросил покоя.
 *
 * Проверяется по темпу, а не отдельным флагом: [NoMotion] — это и есть
 * «движения нет», и второе поле, которое можно забыть переставить вместе с
 * темпом, здесь только мешало бы.
 */
val WolfyMotion.still: Boolean get() = calm == 0

/**
 * Ход в общем темпе.
 *
 * Нужен там, где длительность иначе пришлось бы писать числом. Настройка
 * «уменьшить движение» работает ровно настолько, насколько её слушаются
 * анимации: `tween(300)` внутри виджета не знает ни о какой настройке и едет
 * всегда. Поэтому единственный способ завести длительность — отсюда.
 */
fun <T> WolfyMotion.paced(
    millis: Int,
    easing: Easing = Curves.Paper,
): FiniteAnimationSpec<T> = if (still || millis <= 0) snap() else tween(millis, easing = easing)

/**
 * Пружина, которую тоже можно остановить.
 *
 * У пружины нет длительности, и обнулить её темпом нельзя — а движения от
 * этого не становится меньше: карточка слова выезжает снизу именно пружиной, и
 * при выключенном движении она выезжала бы по-прежнему. Здесь пружина сама
 * знает, что делать в тишине: появиться на месте.
 */
fun <T> WolfyMotion.settling(
    damping: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMediumLow,
): FiniteAnimationSpec<T> =
    if (still) snap() else spring(dampingRatio = damping, stiffness = stiffness)
