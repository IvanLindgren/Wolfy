import com.wolfy.platform.bookFileName
import com.wolfy.platform.bookTitle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Имя книги, приехавшей по ссылке.
 *
 * Проверяется не «что-то получилось», а каждый источник по отдельности и
 * порядок между ними. Ошибка здесь тихая: книга добавляется, открывается и
 * читается, просто называется не так — и заметить это можно только глазами, на
 * плитке, уже после импорта.
 */
class BookFileNameTest {

    @Test
    fun имя_от_провайдера_важнее_всех_остальных_источников() {
        assertEquals(
            "Dorian Gray.epub",
            bookFileName(
                displayName = "Dorian Gray.epub",
                uriTail = "msf:1000000123",
                mimeType = "application/pdf",
            ),
            "имя от провайдера обязано побеждать и хвост, и тип",
        )
    }

    @Test
    fun молчащий_провайдер_не_превращает_книгу_в_book() {
        // Telegram, почта и часть облаков имени не отдают. Раньше все они
        // приезжали в библиотеку одним словом «book».
        assertEquals(
            "Книга.pdf",
            bookFileName(displayName = null, uriTail = "document/42", mimeType = "application/pdf"),
        )
        assertEquals(
            "Книга.txt",
            bookFileName(displayName = "   ", uriTail = "msf:1000000123", mimeType = "text/plain"),
        )
    }

    @Test
    fun хвост_ссылки_годится_только_когда_он_уже_имя_файла() {
        // Часть провайдеров кладёт в хвост исходное имя — его и берём.
        assertEquals(
            "My Book.pdf",
            bookFileName(displayName = null, uriTail = "media/external/My Book.pdf", mimeType = null),
        )
        // А «1000000123» ничем не лучше «book» и выглядит как ошибка.
        assertEquals(
            "Книга.epub",
            bookFileName(displayName = null, uriTail = "1000000123", mimeType = "application/epub+zip"),
        )
    }

    @Test
    fun расширение_добирается_из_типа_когда_его_нет_в_имени() {
        // Формат книги определяется по расширению. Имя без него — это книга,
        // которая не откроется, и это хуже некрасивого названия.
        assertEquals(
            "Гэтсби.epub",
            bookFileName(displayName = "Гэтсби", uriTail = null, mimeType = "application/epub+zip"),
        )
        assertEquals(
            "Гэтсби.epub",
            bookFileName(displayName = "Гэтсби", uriTail = null, mimeType = null),
            "без имени и без типа остаётся самое безобидное расширение",
        )
    }

    @Test
    fun запрещённый_знак_становится_пробелом_а_не_исчезает() {
        // Двоеточие в имени файла на Android законно, и резать имя по нему
        // нельзя: от «Dune: Messiah.pdf» осталось бы « Messiah.pdf».
        assertEquals(
            "Dune Messiah.pdf",
            bookFileName(displayName = "Dune: Messiah.pdf", uriTail = null, mimeType = null),
        )
        // А выброшенный знак склеивает «том 1/2» в «том 12» — другое число.
        assertEquals(
            "том 1 2.epub",
            bookFileName(displayName = "том 1/2.epub", uriTail = null, mimeType = null),
        )
    }

    @Test
    fun название_книги_не_имя_файла() {
        assertEquals("The Picture of Dorian Gray", bookTitle("The_Picture_of_Dorian_Gray.epub"))
        // Точка снимается только вместе с известным расширением: «Т. 2» — это
        // название с точками, и резать его по последней значило бы потерять
        // половину.
        assertEquals("Т. 2. Война и мир", bookTitle("Т. 2. Война и мир"))
        assertEquals("Книга", bookTitle(".epub"))
    }
}
