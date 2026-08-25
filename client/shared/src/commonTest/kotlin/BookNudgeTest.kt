import com.wolfy.data.bookNudge
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.Progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookNudgeTest {
    private fun книга(
        title: String = "Dracula",
        chapters: Int = 10,
        chapter: Int = 4,
        within: Float = 0.1f,
        openedAt: Long = 1_700_000_000_000,
        deleted: Boolean = false,
    ) = LibraryBook(
        id = "one",
        title = title,
        chapters = chapters,
        progress = Progress(chapter = chapter, withinChapter = within, openedAt = openedAt),
        deleted = deleted,
    )

    @Test
    fun звать_некуда_когда_книги_нет() {
        assertNull(bookNudge(null, day = 20_000))
        assertNull(bookNudge(книга(deleted = true), day = 20_000))
    }

    @Test
    fun приглашение_называет_книгу_и_место() {
        val nudge = assertNotNull(bookNudge(книга(), day = 20_000))
        assertTrue(nudge.title.contains("Dracula"), nudge.title)
        assertEquals("Вы остановились на 41%.", nudge.place)
        assertTrue(nudge.teaser.isNotBlank())
    }

    // Виджет перерисовывается системой когда ей вздумается. Подмигивающий
    // разными фразами прямоугольник на рабочем столе — это не приглашение.
    @Test
    fun фраза_за_день_не_меняется() {
        val first = assertNotNull(bookNudge(книга(), day = 20_000))
        val second = assertNotNull(bookNudge(книга(), day = 20_000))
        assertEquals(first.teaser, second.teaser)
    }

    @Test
    fun у_разных_книг_фразы_различаются_хотя_бы_иногда() {
        val titles = listOf("Dracula", "Moby-Dick", "Emma", "Hamlet", "Ulysses", "Walden")
        val phrases = titles.map { assertNotNull(bookNudge(книга(title = it), day = 20_000)).teaser }
        assertTrue(phrases.toSet().size > 1, "все книги получили одну фразу: $phrases")
    }

    @Test
    fun неоткрытая_книга_не_получает_упрёка() {
        val nudge = assertNotNull(bookNudge(книга(openedAt = 0, chapter = 0, within = 0f), day = 1))
        assertEquals("Вы её ещё не открывали.", nudge.place)
    }

    @Test
    fun дочитанная_книга_говорит_о_последней_странице() {
        val nudge = assertNotNull(bookNudge(книга(chapter = 10, within = 0f), day = 1))
        assertEquals("Осталась последняя страница.", nudge.place)
    }

    @Test
    fun строка_для_трея_собирается_целиком() {
        val nudge = assertNotNull(bookNudge(книга(), day = 20_000))
        val line = nudge.oneLine()
        assertTrue(line.contains(nudge.title) && line.contains(nudge.place), line)
    }
}
