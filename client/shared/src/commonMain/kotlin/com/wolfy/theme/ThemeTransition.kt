package com.wolfy.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Плавная смена темы.
 *
 * Четыре темы описаны как разное время суток и разный свет, а переключались
 * мгновенной подменой токенов. Свет так не меняется: экран просто подменялся
 * другим экраном, и выбор темы читался как перезагрузка, а не как «стало
 * темнее».
 *
 * Анимируются одиннадцать ролей поверхности — те, из которых собран лист
 * бумаги. Палитры частей речи и семейств правил берутся у цели сразу: они
 * живут внутри строки текста пятнами в несколько миллиметров, и переход между
 * ними никто не увидит, а одиннадцать лишних анимаций на каждый кадр — увидит.
 *
 * При выключенном движении ([WolfyMotion.still]) возвращается цель без
 * анимации: `paced` в этом случае и так даёт `snap`, но лишний
 * `animateColorAsState` на каждую роль незачем заводить вовсе.
 *
 * Цена перехода известна и принята. Палитра — ключ кеша разметки в
 * `ReaderText`, поэтому пока идёт переход, видимые абзацы пересобирают свои
 * span-ы каждый кадр. Отсюда `quick`, а не `calm`: сто восемьдесят
 * миллисекунд вместо двухсот восьмидесяти. Момент для этого безопасный —
 * читатель держит палец на выборе темы, ничего не прокручивается, — но
 * анимировать палитру где-либо ещё нельзя.
 */
@Composable
internal fun rememberAnimatedColors(target: WolfyColors, motion: WolfyMotion): WolfyColors {
    if (motion.still) return target
    return target.copy(
        paper = target.paper.animated(motion, "paper"),
        ink = target.ink.animated(motion, "ink"),
        inkMuted = target.inkMuted.animated(motion, "inkMuted"),
        rule = target.rule.animated(motion, "rule"),
        surface = target.surface.animated(motion, "surface"),
        accent = target.accent.animated(motion, "accent"),
        gold = target.gold.animated(motion, "gold"),
        highlight = target.highlight.animated(motion, "highlight"),
        onAccent = target.onAccent.animated(motion, "onAccent"),
        inverse = target.inverse.animated(motion, "inverse"),
        onInverse = target.onInverse.animated(motion, "onInverse"),
    )
}

@Composable
private fun Color.animated(motion: WolfyMotion, label: String): Color =
    animateColorAsState(this, motion.paced(motion.quick), label = label).value
