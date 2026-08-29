package com.wolfy.ui.reader

import com.wolfy.data.annotations.Annotation
import com.wolfy.theme.highlightColor
import com.wolfy.widgets.TextMark

/**
 * Краски отметок, переведённые в смещения одного абзаца.
 *
 * Отметка живёт в номерах токенов главы: символы поехали бы от одной смены
 * шрифта, а токены глава отдаёт сама и не меняет. Рисовать же приходится по
 * символам абзаца - другого языка у разметки текста нет. Перевод и делается
 * здесь, один раз на абзац.
 *
 * Отметка спокойно накрывает несколько абзацев сразу: читатель выделил абзац с
 * половиной следующего. Тогда каждому достаётся своя часть, обрезанная по его
 * границам, а не выбрасывается вся отметка.
 *
 * Вынесено из разметки отдельной функцией, потому что арифметика границ - ровно
 * то место, где ошибаются на единицу, а проверить её глазами на экране нельзя:
 * промах в один токен выглядит как «краска чуть-чуть не туда».
 */
internal fun marksFor(block: ReaderBlock, painted: List<Annotation>): List<TextMark> {
    val parsed = block.parsed ?: return emptyList()
    val first = block.firstToken
    if (first < 0 || parsed.tokens.isEmpty() || painted.isEmpty()) return emptyList()
    val count = parsed.tokens.size

    val marks = ArrayList<TextMark>(painted.size)
    for (item in painted) {
        val tone = item.tone ?: continue
        // Пересечение отметки с полосой токенов этого абзаца.
        val from = (item.start - first).coerceAtLeast(0)
        val to = (item.end - first).coerceAtMost(count)
        if (to <= from) continue
        val head = parsed.tokens.getOrNull(from) ?: continue
        val tail = parsed.tokens.getOrNull(to - 1) ?: continue
        if (tail.end <= head.start) continue
        marks += TextMark(head.start until tail.end, highlightColor(tone))
    }
    return marks
}

/**
 * Обратный перевод: кусок абзаца в символах - в номера токенов главы.
 *
 * Нужен в момент, когда читатель отпустил палец: выделение он вёл по буквам, а
 * храниться оно обязано в токенах, иначе первая же смена шрифта сдвинет все
 * его краски.
 *
 * Возвращает `null`, если в куске не оказалось ни одного токена: выделение
 * внутри пробела между словами - не отметка, а промах.
 */
internal fun chapterTokensOf(block: ReaderBlock, range: IntRange): IntRange? {
    val parsed = block.parsed ?: return null
    val first = block.firstToken
    if (first < 0) return null
    var from = -1
    var to = -1
    var hasWord = false
    parsed.tokens.forEachIndexed { index, token ->
        if (token.end <= range.first || token.start > range.last) return@forEachIndexed
        if (from < 0) from = index
        to = index
        if (token.tappable) hasWord = true
    }
    // Пробелы внутри отметки нужны - без них выделение фразы шло бы полосками
    // по словам. А отметка, в которой одни пробелы, это промах пальца, и
    // заводить её значит копить мусор в файле книги.
    if (from < 0 || !hasWord) return null
    return (first + from) until (first + to + 1)
}

/** Номер токена главы под касанием, если он есть. */
internal fun chapterTokenAt(block: ReaderBlock, token: com.wolfy.ffi.Token): Int? {
    val parsed = block.parsed ?: return null
    val first = block.firstToken
    if (first < 0) return null
    val index = parsed.tokens.indexOfFirst { it.start == token.start && it.end == token.end }
    return if (index < 0) null else first + index
}

/** Цитата для отметки: тот кусок абзаца, который читатель обвёл. */
internal fun quoteOfRange(block: ReaderBlock, range: IntRange): String {
    val from = range.first.coerceIn(0, block.text.length)
    val to = (range.last + 1).coerceIn(from, block.text.length)
    return block.text.substring(from, to).trim()
}
