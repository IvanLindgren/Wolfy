package com.wolfy.theme

import androidx.compose.animation.core.CubicBezierEasing
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
