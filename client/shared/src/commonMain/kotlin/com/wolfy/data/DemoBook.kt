package com.wolfy.data

/**
 * Книга для первого запуска.
 *
 * Библиотека пустая, пока пользователь ничего не добавил, а показать читалку
 * надо сразу — иначе первое впечатление от приложения это пустой экран с
 * предложением найти файл. Демо-глава проходит ровно тот же путь, что и
 * настоящая книга: записывается файлом, открывается ядром, разбирается на
 * токены. Никакого отдельного «режима примера» в читалке нет.
 */
const val DEMO_BOOK_TEXT: String = """CHAPTER III

The library smelled of dust, leather and old paper. Evelyn pushed the heavy door and stepped into the quiet hall, where the afternoon light fell in long stripes across the floor.

By the tall window stood a table where a serendipity of bookmarks lay scattered, notes left by readers long gone. She had been reading since dawn, and the margins were filled with her neat handwriting.

The catalogue drawers stood open like little altars, each card carrying a decade of hands. She chose three books and carried them to the reading desk, where a green lamp glowed.

Every unfamiliar word was underlined in pencil, waiting to be saved to her dictionary — a quiet ritual before the evening tea.

Somewhere between the shelves a clock ticked, unhurried. Evelyn smiled: the wolf on the bookplate, with his round spectacles and tweed coat, seemed to approve of her choice.
"""

/**
 * Кладёт демо-книгу в файл и возвращает путь к нему.
 *
 * Платформы отличаются только тем, где у них временный каталог.
 */
expect fun writeDemoBook(): String
