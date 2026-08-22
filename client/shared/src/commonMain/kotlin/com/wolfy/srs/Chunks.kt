package com.wolfy.srs

import com.wolfy.ffi.Finding
import com.wolfy.ffi.ParsedText

/**
 * Разбивка фразы на блоки для конструктора.
 *
 * Блок — это кусок, который в голове хранится целиком: «have been reading»,
 * «for a month», «this book». Рассыпать фразу по одному слову было бы проще
 * всего, но тогда упражнение перестаёт быть про язык и становится про
 * терпение: десять плиток из «I have been reading this book for a month»
 * собираются перебором, а четыре — пониманием.
 *
 * Границы берутся из двух источников. Глагольные цепочки приходят от того же
 * разбора, что работает в читалке, — он уже знает, что «have been reading» это
 * одно сказуемое. Остальное склеивается служебными словами: предлог и артикль
 * не стоят сами по себе, они всегда чему-то предшествуют.
 */
object Chunks {
    /**
     * Сколько блоков — предел для конструктора.
     *
     * Больше — и упражнение из книжной фразы превращается в мозаику. Такие
     * фразы просто не предлагаются в колоду.
     */
    const val MAX_BLOCKS = 9

    /**
     * Служебные слова, которые тянут за собой следующее.
     *
     * Список закрытый и короткий: это не разбор языка, а вёрстка плиток.
     * Ошибиться здесь не страшно — блок получится длиннее или короче, но
     * ответ от этого не изменится.
     */
    private val GLUE = setOf(
        "a", "an", "the",
        "my", "your", "his", "her", "its", "our", "their",
        "this", "that", "these", "those",
        "of", "in", "on", "at", "to", "for", "with", "from", "by", "into",
        "about", "over", "under", "after", "before", "through", "between",
        "no", "some", "any", "every", "each", "another",
    )

    /**
     * Режет предложение на блоки.
     *
     * @param parsed разбор предложения токенами — из него берутся сами слова
     *   и их место в строке.
     * @param findings грамматические разборы этого же предложения: их границы
     *   и есть границы сказуемых.
     */
    fun split(sentence: String, parsed: ParsedText, findings: List<Finding>): List<String> {
        val words = parsed.tokens.withIndex().filter { it.value.tappable }
        if (words.isEmpty()) return emptyList()

        // Цепочки сказуемого: короткие разборы, которые не растягиваются на
        // всё предложение. Условное правило покрывает обе половины фразы, и
        // склеивать по нему — значит собрать её в один блок.
        val glued = findings
            .filter { it.end - it.start in 1..4 }
            .map { it.start until it.end }

        val blocks = mutableListOf<MutableList<String>>()
        var open = false

        for ((index, token) in words) {
            val chain = glued.firstOrNull { index in it }
            val text = sentence.substring(token.start, token.end)

            when {
                // Слово внутри цепочки продолжает её блок, а первое —
                // открывает свой.
                chain != null && index > chain.first && blocks.isNotEmpty() ->
                    blocks.last().add(text)

                chain != null -> {
                    blocks.add(mutableListOf(text))
                    open = false
                }

                open && blocks.isNotEmpty() -> {
                    blocks.last().add(text)
                    open = text.lowercase() in GLUE
                }

                else -> {
                    blocks.add(mutableListOf(text))
                    open = text.lowercase() in GLUE
                }
            }
        }

        return blocks.map { it.joinToString(" ") }
    }

    /**
     * Годится ли фраза для конструктора.
     *
     * Проверяется перед тем, как предложить сохранить её в колоду: обещать
     * тренировку, а потом не смочь её собрать — хуже, чем не обещать.
     */
    fun trainable(blocks: List<String>): Boolean = blocks.size in 2..MAX_BLOCKS

    /**
     * Сходятся ли собранное и исходное.
     *
     * Сравниваются слова, а не строки: читатель собирает из плиток, между
     * которыми пробелы ставит интерфейс, а в исходной фразе есть ещё и знаки
     * препинания, которых на плитках нет.
     */
    fun same(assembled: String, expected: String): Boolean =
        normalize(assembled) == normalize(expected)

    private fun normalize(text: String): String =
        text.lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it == '\'' }
            .split(' ', '\n', '\t')
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
