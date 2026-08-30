package com.wolfy.ui.companion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.still
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Что персонаж делает прямо сейчас.
 *
 * Список повторяет серверный `allowedMotions` из `companionai`: набор реплик
 * приходит с полем `motion` у каждой фразы, и оно до сих пор никем не
 * читалось. Модель его сочиняла, сервер проверял, клиент разбирал и клал в
 * поле, на которое никто не смотрел, — персонаж стоял неподвижно на любой
 * своей реплике.
 */
enum class CompanionMotion(val code: String) {
    None("none"),
    Wave("wave"),
    Nod("nod"),
    Peek("peek"),
    Think("think"),
    Speak("speak");

    companion object {
        /** Незнакомый код означает покой, а не отказ показать реплику. */
        fun of(code: String?): CompanionMotion =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: None
    }
}

/**
 * Поза фигуры на этом кадре.
 *
 * Не картинка и не набор спрайтов: пак даёт по семь пар глаз и по два рта на
 * всех, и покадровой анимации из него не собрать. Зато слои рисуются
 * поодиночке, а значит каждому можно назначить своё преобразование — веки
 * сжимаются по вертикали, рот раскрывается, корпус дышит. Так персонаж
 * оживает без единого нового ассета.
 */
@Immutable
data class CompanionPose(
    /** Дыхание: доля, на которую корпус вытягивается вверх. */
    val breath: Float = 0f,
    /** Насколько сомкнуты веки: 0 — открыты, 1 — закрыты. */
    val lids: Float = 0f,
    /** Насколько открыт рот. */
    val mouth: Float = 0f,
    /** Наклон фигуры, градусы. */
    val tilt: Float = 0f,
    /** Смещение по горизонтали, доля ширины. */
    val slide: Float = 0f,
    /** Смещение по вертикали, доля высоты; отрицательное — вверх. */
    val rise: Float = 0f,
) {
    companion object {
        /** Неподвижная поза: ею же рисуется фигура в редакторе и в списке. */
        val Still = CompanionPose()
    }
}

/**
 * Живая поза персонажа.
 *
 * Три независимых слоя движения, и каждый гасится настройкой «уменьшить
 * движение» по-своему: бесконечные дыхание и моргание в тишине не заводятся
 * вовсе (заведённый переход из нуля в ноль продолжал бы считать кадры до
 * закрытия книги), а жест на реплику вырождается в мгновенную установку.
 *
 * @param gesture жест, который просит текущая реплика.
 * @param trigger счётчик показов. Именно счётчик, а не флаг: две одинаковые
 *   реплики подряд обязаны сыграть жест дважды.
 * @param alive дышит и моргает. Выключается там, где фигура не персонаж, а
 *   иллюстрация: в списке компаньонов и в предпросмотре редактора.
 * @param seed зерно случайных пауз между морганиями. Разные компаньоны на
 *   одном экране не должны моргать в такт, как гирлянда.
 */
@Composable
fun rememberCompanionPose(
    gesture: CompanionMotion = CompanionMotion.None,
    trigger: Int = 0,
    alive: Boolean = true,
    seed: Int = 0,
): CompanionPose {
    val motion = WolfyTheme.motion

    var breath = 0f
    if (alive && !motion.still) {
        // Дыхание — единственное бесконечное движение фигуры. Его период не
        // связан с темпом переходов интерфейса и потому длинный: персонаж
        // дышит, а не вибрирует.
        val idle = rememberInfiniteTransition(label = "companion-breath")
        breath = idle.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(motion.flight * 5, easing = Curves.Paper),
                RepeatMode.Reverse,
            ),
            label = "companion-breath-value",
        ).value
    }

    val lids = remember { Animatable(0f) }
    LaunchedEffect(alive, motion.still, seed) {
        if (!alive || motion.still) {
            lids.snapTo(0f)
            return@LaunchedEffect
        }
        val random = Random(seed * 31 + 17)
        while (true) {
            delay(BLINK_MIN_MILLIS + random.nextLong(BLINK_SPREAD_MILLIS))
            lids.animateTo(1f, motion.paced(motion.instant))
            lids.animateTo(0f, motion.paced(motion.instant))
            // Иногда персонаж моргает дважды подряд. Ровный интервал читается
            // как метроном, а не как живой взгляд.
            if (random.nextInt(4) == 0) {
                delay(BLINK_DOUBLE_GAP_MILLIS)
                lids.animateTo(1f, motion.paced(motion.instant))
                lids.animateTo(0f, motion.paced(motion.instant))
            }
        }
    }

    val mouth = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }
    val slide = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val squint = remember { Animatable(0f) }

    LaunchedEffect(trigger, gesture) {
        // Жест начинается с покоя: прерванный предыдущий не должен оставить
        // персонажа с наклонённой головой навсегда.
        mouth.snapTo(0f)
        squint.snapTo(0f)
        when (gesture) {
            CompanionMotion.None -> {
                tilt.animateTo(0f, motion.paced(motion.quick))
                rise.animateTo(0f, motion.paced(motion.quick))
                slide.animateTo(0f, motion.paced(motion.quick))
            }

            CompanionMotion.Wave -> {
                // Рук у фигуры нет, поэтому машет она всем корпусом. Затухающие
                // качания, а не ровные: ровные выглядят как метроном.
                for (angle in listOf(9f, -7f, 5f, -3f, 0f)) {
                    tilt.animateTo(angle, motion.paced(motion.quick, Curves.Toss))
                }
            }

            CompanionMotion.Nod -> {
                for (depth in listOf(0.06f, 0f, 0.045f, 0f)) {
                    rise.animateTo(depth, motion.paced(motion.quick, Curves.Toss))
                }
            }

            CompanionMotion.Peek -> {
                // Выглядывает сбоку: заходит из-за правого края и встаёт на
                // место. Дальше края уезжать некуда — фигура и так у границы.
                slide.snapTo(0.45f)
                slide.animateTo(0f, motion.paced(motion.calm))
            }

            CompanionMotion.Think -> {
                // Наклон и прищур держатся, пока читатель успевает заметить,
                // что персонаж задумался, а не сломался.
                squint.animateTo(0.4f, motion.paced(motion.quick))
                tilt.animateTo(-8f, motion.paced(motion.calm))
                delay(THINK_HOLD_MILLIS)
                tilt.animateTo(0f, motion.paced(motion.calm))
                squint.animateTo(0f, motion.paced(motion.quick))
            }

            CompanionMotion.Speak -> {
                // Рот открывается неровно: ровные такты читаются как жующая
                // рыба, а не как речь.
                for (open in listOf(0.9f, 0.15f, 0.7f, 0.1f, 0.85f, 0.2f, 0.5f, 0f)) {
                    mouth.animateTo(open, motion.paced(motion.instant))
                }
            }
        }
    }

    return CompanionPose(
        breath = breath * BREATH_DEPTH,
        // Прищур и моргание складываются, но веки не смыкаются сильнее
        // закрытых: у задумавшегося персонажа глаза не должны исчезать.
        lids = (lids.value + squint.value).coerceAtMost(1f),
        mouth = mouth.value,
        tilt = tilt.value,
        slide = slide.value,
        rise = rise.value,
    )
}

/** На сколько корпус вытягивается на вдохе. Доля высоты фигуры. */
private const val BREATH_DEPTH = 0.022f

private const val BLINK_MIN_MILLIS = 2_600L
private const val BLINK_SPREAD_MILLIS = 4_200L
private const val BLINK_DOUBLE_GAP_MILLIS = 140L
private const val THINK_HOLD_MILLIS = 900L
