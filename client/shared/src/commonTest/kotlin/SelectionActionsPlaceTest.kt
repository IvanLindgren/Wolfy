import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.wolfy.ui.reader.actionsPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Куда встаёт панель действий над выделением.
 *
 * Экран телефона: 1080 на 2000 точек растра, панель — 620 на 120, поля 24.
 */
class SelectionActionsPlaceTest {
    private val room = IntSize(1080, 2000)
    private val panel = IntSize(620, 120)
    private val margin = 24

    @Test
    fun под_выделением_и_серединой_по_его_середине() {
        val taken = Rect(200f, 400f, 800f, 460f)
        val place = actionsPlace(taken, panel, room, margin)
        assertEquals(500 - 310, place.x)
        assertEquals(460 + 12, place.y)
    }

    @Test
    fun у_края_страницы_панель_не_вылезает_за_поле() {
        val left = actionsPlace(Rect(0f, 400f, 90f, 460f), panel, room, margin)
        assertEquals(margin, left.x)
        val right = actionsPlace(Rect(990f, 400f, 1080f, 460f), panel, room, margin)
        assertEquals(room.width - panel.width - margin, right.x)
    }

    @Test
    fun у_нижнего_края_панель_переходит_наверх() {
        // Выделение в самом низу: снизу места нет, и панель обязана встать над
        // ним. Иначе решение о выделении оказывается за краем экрана.
        val taken = Rect(200f, 1880f, 800f, 1940f)
        val place = actionsPlace(taken, panel, room, margin)
        assertTrue(place.y + panel.height <= taken.top, "панель осталась внизу: ${place.y}")
    }

    @Test
    fun у_абзаца_выше_экрана_панель_остаётся_на_странице() {
        // Верх выделения ушёл за верхний край: панель над ним оказалась бы в
        // отрицательных координатах.
        val taken = Rect(200f, -300f, 800f, 1990f)
        val place = actionsPlace(taken, panel, room, margin)
        assertTrue(place.y >= margin, "панель уехала выше страницы: ${place.y}")
        assertTrue(place.y + panel.height + margin <= room.height, "панель уехала ниже страницы")
    }

    @Test
    fun до_первого_замера_панель_никуда_не_ставится() {
        assertEquals(0, actionsPlace(Rect(0f, 0f, 10f, 10f), IntSize.Zero, room, margin).x)
        assertEquals(0, actionsPlace(Rect(0f, 0f, 10f, 10f), panel, IntSize.Zero, margin).y)
    }
}
