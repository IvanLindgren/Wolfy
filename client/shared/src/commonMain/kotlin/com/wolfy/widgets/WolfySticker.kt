package com.wolfy.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wolfy.resources.Res
import com.wolfy.resources.wolfy_celebrate
import com.wolfy.resources.wolfy_happywave
import com.wolfy.resources.wolfy_heart
import com.wolfy.resources.wolfy_scroll
import com.wolfy.resources.wolfy_sleep
import com.wolfy.resources.wolfy_surprised
import com.wolfy.resources.wolfy_sword
import com.wolfy.resources.wolfy_thinking
import com.wolfy.resources.wolfy_wave
import org.jetbrains.compose.resources.painterResource

/**
 * Вульфи — единственная иллюстрация в приложении.
 *
 * Он появляется там, где читать нечего: на пустой библиотеке, в конце
 * тренировки, при ошибке. На странице с текстом его нет и не будет — газетная
 * полоса не терпит соседства с рисунком, а чтение не терпит соседства с
 * чем угодно.
 *
 * Настроение выбирается по месту, а не случайно: одно и то же событие обязано
 * встречать читателя одним и тем же выражением, иначе Вульфи перестаёт быть
 * персонажем и становится набором картинок.
 */
enum class Sticker {
    /** Приветствие: пустая библиотека, первый запуск. */
    Wave,

    /** Радостное приветствие: возвращение после перерыва. */
    HappyWave,

    /** Задумчивость: подсказка в тренировке, разбор трудного слова. */
    Thinking,

    /** Свиток: справочник грамматики. */
    Scroll,

    /** С мечом: начало тренировки. */
    Sword,

    /** Удивление: ошибка, неожиданный ответ. */
    Surprised,

    /** Сон: на сегодня повторений нет. */
    Sleep,

    /** Сердце: слово добавлено в колоду. */
    Heart,

    /** Праздник: колода закрыта, серия продолжена. */
    Celebrate,
}

/**
 * Показывает Вульфи.
 *
 * Размер задаётся стороной квадрата: у всех стикеров пропорции близки к
 * единице, и подгонять каждый по отдельности незачем.
 */
@Composable
fun WolfySticker(
    sticker: Sticker,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    val resource = when (sticker) {
        Sticker.Wave -> Res.drawable.wolfy_wave
        Sticker.HappyWave -> Res.drawable.wolfy_happywave
        Sticker.Thinking -> Res.drawable.wolfy_thinking
        Sticker.Scroll -> Res.drawable.wolfy_scroll
        Sticker.Sword -> Res.drawable.wolfy_sword
        Sticker.Surprised -> Res.drawable.wolfy_surprised
        Sticker.Sleep -> Res.drawable.wolfy_sleep
        Sticker.Heart -> Res.drawable.wolfy_heart
        Sticker.Celebrate -> Res.drawable.wolfy_celebrate
    }

    Image(
        painter = painterResource(resource),
        // Описание для доступности намеренно пустое: стикер сопровождает текст,
        // который рядом уже всё сказал, и озвучивать «волк машет лапой» значит
        // мешать тому, кто слушает экран.
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}
