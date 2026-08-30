package com.wolfy.ui.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Правила погружения проверяются числами, а не глазами.
 *
 * Оснастка, которая прячется не вовремя, — худший вид ошибки в читалке: она
 * или мигает на каждом дрожании пальца, или не достаётся вовсе, и заметить это
 * можно только на устройстве, с книгой и терпением. Логика хода отделена от
 * Compose ровно ради того, чтобы её можно было спросить прямо.
 */
class ReadingImmersionTest {

    private fun immersion() = ReadingImmersion(hideTravelPx = 100f, revealTravelPx = 50f)

    @Test
    fun `чтение вперёд убирает оснастку`() {
        val immersion = immersion()
        assertTrue(immersion.chromeVisible, "книга открывается с видимой оснасткой")
        immersion.onScroll(-60f)
        assertTrue(immersion.chromeVisible, "полпорога — это ещё не намерение")
        immersion.onScroll(-60f)
        assertFalse(immersion.chromeVisible, "после порога оснастка обязана уйти")
    }

    @Test
    fun `возврат назад достаёт оснастку и делает это легче`() {
        val immersion = immersion()
        immersion.onScroll(-120f)
        assertFalse(immersion.chromeVisible)

        // Порог возврата вдвое короче порога ухода: спрятать нужно уверенно,
        // а достать — легко.
        immersion.onScroll(60f)
        assertTrue(immersion.chromeVisible, "короткого хода назад должно хватить")
    }

    @Test
    fun `дрожание пальца не копится в одну сторону`() {
        // Пять шагов вниз и пять вверх подряд — это стоящий на месте палец, а
        // не решение. Без обнуления на смене направления сумма модулей дошла
        // бы до порога и оснастка мигала бы на месте.
        val immersion = immersion()
        repeat(5) {
            immersion.onScroll(-30f)
            immersion.onScroll(30f)
        }
        assertTrue(immersion.chromeVisible, "оснастка не должна уходить от дрожания")
    }

    @Test
    fun `показать можно всегда и это обнуляет ход`() {
        val immersion = immersion()
        immersion.onScroll(-90f)
        immersion.reveal()
        assertTrue(immersion.chromeVisible)
        // Ход обнулён: оставшихся до порога десяти точек больше не хватает.
        immersion.onScroll(-90f)
        assertTrue(immersion.chromeVisible, "reveal обязан обнулить накопленный ход")
    }
}
