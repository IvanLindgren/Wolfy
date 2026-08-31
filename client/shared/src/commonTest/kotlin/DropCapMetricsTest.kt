import com.wolfy.widgets.dropCapPlan
import com.wolfy.widgets.frauncesHasLetter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Арифметика буквицы.
 *
 * Числа взяты из настоящего набора читалки: EB Garamond кеглем 19 с
 * межстрочным 28.5, высота прописной 0.650 кегля, первая базовая линия — та,
 * что даёт Compose при пропорциональном распределении интерлиньяжа.
 */
class DropCapMetricsTest {
    private val fontSize = 19f
    private val lineHeight = 28.5f
    private val capHeight = fontSize * 0.650f
    private val baseline = 21.99f

    @Test
    fun верх_буквицы_стоит_на_линии_прописных_первой_строки() {
        val plan = dropCapPlan(baseline, capHeight, lineHeight, lines = 3, capHeightRatio = 0.700f)
        assertTrue(abs(plan.inkTop - (baseline - capHeight)) < 0.01f, "верх: ${plan.inkTop}")
    }

    @Test
    fun низ_буквицы_приходит_на_базовую_линию_последней_строки() {
        val plan = dropCapPlan(baseline, capHeight, lineHeight, lines = 3, capHeightRatio = 0.700f)
        val lastBaseline = baseline + lineHeight * 2
        assertTrue(abs(plan.inkTop + plan.inkHeight - lastBaseline) < 0.01f, "низ: ${plan.inkTop + plan.inkHeight}")
    }

    @Test
    fun кегль_выводится_из_высоты_литеры_а_не_назначается_ей_равным() {
        val plan = dropCapPlan(baseline, capHeight, lineHeight, lines = 3, capHeightRatio = 0.700f)
        assertTrue(abs(plan.fontSize * 0.700f - plan.inkHeight) < 0.01f, "кегль: ${plan.fontSize}")
    }

    @Test
    fun прежний_отсчёт_от_верха_коробки_давал_литеру_крупнее_положенной() {
        // Прежний код брал высоту литеры равной baseline + lineHeight * (n-1).
        // Разница — надстрочное поле шрифта плюс половина интерлиньяжа: около
        // девяти точек при кегле 19, то есть примерно десятая часть буквицы.
        val plan = dropCapPlan(baseline, capHeight, lineHeight, lines = 3, capHeightRatio = 0.700f)
        val before = baseline + lineHeight * 2
        assertTrue(before > plan.inkHeight, "прежняя высота обязана быть больше")
        assertTrue(before - plan.inkHeight > 8f, "разница: ${before - plan.inkHeight}")
    }

    @Test
    fun буквица_в_одну_строку_ростом_с_прописную_буквой_набора() {
        val plan = dropCapPlan(baseline, capHeight, lineHeight, lines = 1, capHeightRatio = 0.700f)
        assertEquals(capHeight, plan.inkHeight)
    }

    @Test
    fun верх_не_уходит_выше_абзаца_даже_при_чужой_метрике() {
        // Крупная прописная и низкая базовая линия — набор, которого у нас нет,
        // но настройка кегля и интервала у читателя своя.
        val plan = dropCapPlan(bodyBaseline = 10f, bodyCapHeight = 18f, lineHeight, 3, 0.700f)
        assertEquals(0f, plan.inkTop)
    }

    @Test
    fun кириллице_нужен_другой_шрифт() {
        assertTrue(frauncesHasLetter('T'))
        assertTrue(frauncesHasLetter('Å'))
        assertFalse(frauncesHasLetter('В'))
        assertFalse(frauncesHasLetter('Θ'))
    }
}
