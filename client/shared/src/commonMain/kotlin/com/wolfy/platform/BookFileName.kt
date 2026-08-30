package com.wolfy.platform

/**
 * Имя файла книги и название книги по нему.
 *
 * ## Что здесь чинится
 *
 * На Android система отдаёт не файл, а ссылку, и имя у ссылки спрашивают
 * отдельным запросом к провайдеру. Провайдер имя возвращать не обязан. Файловый
 * менеджер и «Загрузки» возвращают, а Telegram, почта и часть облаков — нет, и
 * книга приезжала в библиотеку под словом `book`. Читатель, перенёсший в Wolfy
 * пять присланных ему PDF, получал пять одинаковых плиток.
 *
 * Спросить имя больше негде, но у ссылки есть хвост, и у части провайдеров он
 * и есть исходное имя файла. Поэтому источников три, и они пробуются по
 * убыванию доверия: имя от провайдера, хвост ссылки, и — если оба молчат —
 * честное «Книга» вместо служебного `book`.
 *
 * ## Почему расширение важнее имени
 *
 * Формат книги в Wolfy определяется по расширению: по нему выбирается разбор и
 * им заполняется поле `format`. Имя без расширения означает книгу, которая не
 * откроется, — и это хуже некрасивого названия. Поэтому расширение, если его не
 * оказалось в имени, добирается из MIME-типа ссылки: тип провайдеры отдают
 * почти всегда, даже когда имени у них нет.
 */

/** Форматы, которые понимает ядро. Список здесь тот же, что в пикере. */
private val KNOWN_EXTENSIONS = setOf("epub", "pdf", "txt")

/** Имя, под которым книга ляжет в хранилище. Всегда с рабочим расширением. */
internal fun bookFileName(
    displayName: String?,
    uriTail: String?,
    mimeType: String?,
): String {
    val fromProvider = cleanName(displayName)
    // Хвост ссылки берётся, только когда он уже похож на имя файла. У части
    // провайдеров там лежит `msf:1000000123` или `document/42`, и название
    // «1000000123» ничем не лучше «book», а выглядит как ошибка.
    val fromUri = cleanName(tailOf(uriTail))?.takeIf { extensionOf(it) in KNOWN_EXTENSIONS }
    val name = fromProvider ?: fromUri

    val extension = name?.let(::extensionOf)?.takeIf { it in KNOWN_EXTENSIONS }
        ?: extensionOfMime(mimeType)
        ?: FALLBACK_EXTENSION
    val stem = name?.let(::stemOf)?.takeIf { it.isNotBlank() } ?: FALLBACK_STEM
    return "$stem.$extension"
}

/**
 * Название книги по имени файла.
 *
 * Подчёркивания разворачиваются обратно в пробелы: `The_Picture_of_Dorian_Gray`
 * — это имя файла, а не название книги, и читателю показывают второе. Те же
 * правила действуют в вебе: одна и та же книга, добавленная на телефоне и в
 * браузере, обязана называться одинаково.
 */
internal fun bookTitle(fileName: String): String {
    val stem = stemOf(fileName).replace('_', ' ').replace(WHITESPACE, " ").trim()
    return stem.ifBlank { FALLBACK_STEM }
}

/**
 * Хвост ссылки без схемы и каталогов.
 *
 * Приезжает и как `document/42`, и как `raw:/storage/…/book.pdf`, поэтому
 * режется и по `/`, и по `:`. Имя от провайдера через эту резку не проходит:
 * «Dune: Messiah.pdf» — законное имя файла на Android, и от него осталось бы
 * « Messiah.pdf».
 */
private fun tailOf(uriTail: String?): String =
    uriTail.orEmpty().substringAfterLast('/').substringAfterLast(':')

/**
 * Имя без знаков, на которых спотыкается запись файла.
 *
 * Запрещённый знак заменяется пробелом, а не выбрасывается: без этого «том 1/2»
 * склеивается в «том 12», и получается другое число. Возвращает `null`, если
 * после чистки ничего не осталось: пустая строка здесь означает «источник
 * промолчал», и отличать её от настоящего имени приходится всем вызывающим.
 */
private fun cleanName(raw: String?): String? {
    val safe = raw.orEmpty()
        .map { if (it in FORBIDDEN || it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_NAME)
    return safe.ifBlank { null }
}

private fun extensionOf(fileName: String): String =
    fileName.substringAfterLast('.', "").lowercase()

private fun stemOf(fileName: String): String {
    val extension = extensionOf(fileName)
    // Точка снимается только вместе с известным расширением. «Т. 2. Война и
    // мир» — это название с точками, и обрезать его по последней значило бы
    // потерять половину.
    return if (extension in KNOWN_EXTENSIONS) fileName.substringBeforeLast('.') else fileName
}

private fun extensionOfMime(mimeType: String?): String? =
    when (mimeType.orEmpty().substringBefore(';').trim().lowercase()) {
        "application/epub+zip", "application/epub" -> "epub"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        else -> null
    }

private val WHITESPACE = Regex("\\s+")

/** Знаки, на которых спотыкается запись файла на любой из платформ. */
private const val FORBIDDEN = "\\/:*?\"<>|"

private const val MAX_NAME = 120

/**
 * Запасное имя.
 *
 * `epub` выбран не как самый вероятный формат, а как самый безобидный: имя без
 * расширения доезжает сюда только тогда, когда провайдер не назвал ни имени, ни
 * типа, и ошибиться в этом случае придётся в любом случае. Ошибка разбора
 * говорит читателю правду; отсутствие расширения не говорит ничего.
 */
private const val FALLBACK_EXTENSION = "epub"
private const val FALLBACK_STEM = "Книга"
