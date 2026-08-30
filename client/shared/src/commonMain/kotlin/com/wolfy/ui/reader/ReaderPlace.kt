package com.wolfy.ui.reader

/**
 * Где читатель находится — одной величиной за раз.
 *
 * ## Что здесь чинится
 *
 * В шапке стояли рядом два показания разного масштаба и без подписей:
 * волосяная линейка показывала долю **книги**, а число рядом — минуты до конца
 * **главы**. Понять, к чему относится каждое, было неоткуда, и вместе они
 * отвечали на вопрос, которого никто не задавал.
 *
 * Хуже было третье: когда минуты посчитать не удавалось, в том же месте
 * появлялись проценты книги. Один и тот же угол экрана молча менял и величину,
 * и масштаб — «7 мин» и «43 %» стояли в одной позиции и означали разное. Число,
 * смысл которого меняется сам, хуже отсутствующего числа.
 *
 * ## Правило
 *
 * Показание одно, и линейка всегда о том же, о чём подпись. Второй масштаб не
 * исчез — он переехал под касание: это отдельный вопрос читателя, а не второе
 * сообщение приложения. А там, где величины нет, не появляется ничего: пустое
 * место честнее подменённой единицы.
 */
internal enum class ReadingScope {
    /** Сколько осталось до конца главы. Читают до неё, а не до конца книги. */
    Chapter,

    /** Какая это глава из скольких. Не проценты: главы можно пересчитать. */
    Book,
}

/** Следующий масштаб по кругу: показание переключается касанием. */
internal fun ReadingScope.next(): ReadingScope =
    if (this == ReadingScope.Chapter) ReadingScope.Book else ReadingScope.Chapter

/**
 * Подпись показания.
 *
 * `null` означает «величины нет» и рисуется пустотой. Раньше на этом месте
 * подставлялась соседняя величина в другой единице — от этого показание и
 * перестало что-либо значить.
 *
 * @param minutesLeft минуты до конца главы; `0` — меньше минуты, `null` —
 *   глава ещё не разобрана и считать не по чему.
 */
internal fun readingPlaceLabel(
    scope: ReadingScope,
    minutesLeft: Int?,
    chapterIndex: Int,
    chapterCount: Int,
): String? = when (scope) {
    ReadingScope.Chapter -> minutesLeft?.let(::minutesLabel)
    ReadingScope.Book ->
        if (chapterCount > 0) "${chapterIndex + 1} из $chapterCount" else null
}

/**
 * Полное прочтение показания — для озвучки экрана и подсказки под курсором.
 *
 * Короткая подпись в шапке места на слова не оставляет, но «7 мин» без
 * пояснения понятно только тому, кто уже знает, о чём речь. Здесь то же самое
 * сказано целиком.
 */
internal fun readingPlaceDescription(
    scope: ReadingScope,
    minutesLeft: Int?,
    chapterIndex: Int,
    chapterCount: Int,
): String = when (scope) {
    ReadingScope.Chapter -> minutesLeft
        ?.let { "До конца главы примерно ${minutesLabel(it)}. Нажмите, чтобы увидеть место в книге." }
        ?: "Сколько осталось читать, пока неизвестно. Нажмите, чтобы увидеть место в книге."

    ReadingScope.Book -> if (chapterCount > 0) {
        "Глава ${chapterIndex + 1} из $chapterCount. Нажмите, чтобы увидеть, сколько осталось до конца главы."
    } else {
        "Место в книге пока неизвестно."
    }
}

/**
 * Доля для линейки — того же масштаба, что и подпись.
 *
 * Именно этого и не было: линейка жила своей жизнью и мерила книгу, пока
 * подпись рядом мерила главу.
 */
internal fun readingPlaceFraction(
    scope: ReadingScope,
    withinChapter: Float,
    chapterIndex: Int,
    chapterCount: Int,
): Float {
    val within = withinChapter.coerceIn(0f, 1f)
    return when (scope) {
        ReadingScope.Chapter -> within
        ReadingScope.Book ->
            if (chapterCount > 0) ((chapterIndex + within) / chapterCount).coerceIn(0f, 1f) else 0f
    }
}

/**
 * Минуты словами.
 *
 * Часы появляются с шестидесяти минут: «93 мин» читатель всё равно переводит в
 * голове, и переводить за него — вся работа этой строки.
 */
private fun minutesLabel(minutes: Int): String = when {
    minutes <= 0 -> "<1 мин"
    minutes < MINUTES_IN_HOUR -> "$minutes мин"
    minutes % MINUTES_IN_HOUR == 0 -> "${minutes / MINUTES_IN_HOUR} ч"
    else -> "${minutes / MINUTES_IN_HOUR} ч ${minutes % MINUTES_IN_HOUR} мин"
}

private const val MINUTES_IN_HOUR = 60
