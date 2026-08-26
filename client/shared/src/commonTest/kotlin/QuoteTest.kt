import com.wolfy.ui.card.quoteOf
import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteTest {
    @Test
    fun фраза_и_перевод_готовы_к_вставке() {
        assertEquals("«Stay hungry.»\nОставайся голодным.", quoteOf(" Stay hungry. ", " Оставайся голодным. "))
    }

    @Test
    fun без_сети_копируется_хотя_бы_сама_фраза() {
        assertEquals("«Stay hungry.»", quoteOf("Stay hungry.", "  "))
    }

    @Test
    fun пустой_текст_не_кладётся_в_буфер() {
        assertEquals("", quoteOf(" \n ", "перевод"))
    }
}
