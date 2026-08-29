package com.wolfy.data.annotations

import kotlinx.serialization.Serializable

/**
 * Отметка читателя в книге: выделение краской, заметка или то и другое.
 *
 * Поля повторяют серверный контракт `annotations.Item` слово в слово - список
 * уезжает наверх целиком и приходит обратно слитым, и любое расхождение имён
 * означало бы молча потерянное поле.
 *
 * Координаты - номера токенов главы, полуинтервал. Не символы: символы
 * поехали бы от одной только смены шрифта, а токены глава отдаёт сама.
 */
@Serializable
data class Annotation(
    val id: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    /** Краска маркера, 1..10. `null` - заметка без выделения. */
    val tone: Int? = null,
    /** Цитата из книги: по ней отметку узнают в списке, без открытия главы. */
    val quote: String = "",
    /** Что читатель об этом думает. */
    val note: String = "",
    /** Номер правки в счётчике Лампорта писателя. */
    val rev: Long = 0,
    /** Стабильный номер устройства, подписавшего правку. */
    val writer: String = "",
    /**
     * Поколение серверного снимка, которым запись проштампована. Ставит только
     * сервер; у местных, ещё не отправленных правок - ноль.
     */
    val generation: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /**
     * Пометка удаления.
     *
     * Удаление - это запись, а не отсутствие записи: иначе оно не доехало бы до
     * второго устройства, и отметка воскресла бы там из его же файла.
     * Содержимое при удалении стирается: хранить текст того, что читатель
     * вычеркнул, значит следить за ним самим.
     */
    val deleted: Boolean = false,
) {
    /** Есть ли у отметки видимое выделение. */
    val paints: Boolean get() = !deleted && tone != null && end > start

    /** Полуинтервал токенов главы. */
    val tokens: IntRange get() = start until end
}

/**
 * Краски маркера.
 *
 * Десять, как на сервере (`annotations.MaxTone`), и в том же порядке, что в
 * вебе: у читателя с двумя устройствами жёлтый обязан быть жёлтым на обоих.
 */
val TONES: List<Int> = (1..10).toList()

/** Человеческое имя краски - для подписи в выборе цвета. */
fun toneTitle(tone: Int): String = when (tone) {
    1 -> "Жёлтый"
    2 -> "Оранжевый"
    3 -> "Розовый"
    4 -> "Красный"
    5 -> "Фиолетовый"
    6 -> "Синий"
    7 -> "Голубой"
    8 -> "Зелёный"
    9 -> "Оливковый"
    else -> "Серый"
}

/**
 * Слияние двух списков отметок.
 *
 * Правило то же, что на сервере: побеждает большая пара (rev, writer). Часы
 * устройств врут, а счётчик Лампорта писателя вместе с его стабильным номером
 * дают полный детерминированный порядок - оба устройства придут к одному
 * ответу, не спрашивая друг у друга.
 *
 * Порядок аргументов ни на что не влияет, и это обязательное свойство: клиент
 * сливает своё с серверным, сервер - серверное с клиентским, и разойтись они
 * не имеют права.
 */
fun merge(first: List<Annotation>, second: List<Annotation>): List<Annotation> {
    val winners = LinkedHashMap<String, Annotation>(first.size + second.size)
    for (item in first) winners[item.id] = item
    for (item in second) {
        val current = winners[item.id]
        if (current == null || later(item, current)) winners[item.id] = item
    }
    return winners.values.sortedWith(
        compareBy({ it.chapter }, { it.start }, { it.end }, { it.id }),
    )
}

/** Кого из двух кандидатов оставить. */
private fun later(candidate: Annotation, current: Annotation): Boolean {
    if (candidate.rev != current.rev) return candidate.rev > current.rev
    if (candidate.writer != current.writer) return candidate.writer > current.writer
    // Полный порядок по содержимому: ничья по (rev, writer) означает две
    // правки одного устройства с одним номером, и выбор всё равно обязан быть
    // одинаковым на всех устройствах.
    return compare(candidate, current) > 0
}

private fun compare(a: Annotation, b: Annotation): Int {
    if (a.deleted != b.deleted) return if (a.deleted) 1 else -1
    if (a.updatedAt != b.updatedAt) return a.updatedAt.compareTo(b.updatedAt)
    if (a.tone != b.tone) return (a.tone ?: -1).compareTo(b.tone ?: -1)
    if (a.note != b.note) return a.note.compareTo(b.note)
    if (a.start != b.start) return a.start.compareTo(b.start)
    if (a.end != b.end) return a.end.compareTo(b.end)
    return a.quote.compareTo(b.quote)
}
