package com.wolfy.srs

import com.wolfy.data.atLocalHour
import com.wolfy.data.library.Card
import com.wolfy.data.localHour
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Расписание повторений.
 *
 * Чистые функции без состояния: карточка на входе, карточка на выходе. Так
 * расписание можно проверить тестом на любой истории ответов, не заводя ни
 * библиотеки, ни времени, ни устройства, — а расписание, которое нельзя
 * проверить, обязательно окажется неверным.
 *
 * ## Очки здоровья
 *
 * У карточки есть запас прочности: сто очков в начале, ноль — когда слово
 * выучено. Верный ответ снимает очки, ошибка возвращает. Метафора не
 * украшение: она объясняет, зачем повторять слово, которое «и так знаешь» —
 * потому что у него ещё осталась прочность.
 *
 * Четырёх верных ответов подряд хватает, чтобы свести прочность к нулю. Каждый
 * следующий снимает больше предыдущего: первый — двадцать, четвёртый —
 * тридцать пять. Так награда за серию видна, а одна случайная удача слово не
 * «выучивает».
 *
 * ## Сроки
 *
 * Лесенка: сутки, трое, неделя, две с половиной, месяц, два с половиной,
 * полгода. Ошибка сбрасывает на начало и возвращает карточку через десять
 * минут — в ту же тренировку, потому что слово, которое только что не
 * вспомнилось, повторять через сутки бессмысленно.
 *
 * Всю лесенку растягивает [Intensity] и подправляет [ease] — то, как читатель
 * отвечает на самом деле.
 */
object Scheduler {
    /** Прочность новой карточки. */
    const val FULL_HP = 100

    /**
     * Сроки в минутах.
     *
     * Первая ступень — сутки: повторить назавтра то, что впервые встретил
     * сегодня. Последняя — полгода; дальше растягивать нечего, слово к этому
     * моменту либо в языке, либо не нужно.
     */
    private val LADDER = listOf(
        1L * DAY, 3L * DAY, 7L * DAY, 16L * DAY, 35L * DAY, 75L * DAY, 160L * DAY,
    )

    /** Через сколько вернуть карточку, на которой ошиблись. */
    private const val RETRY_MINUTES = 10L

    /** Сколько прочности возвращает ошибка. */
    private const val MISS = 15

    /**
     * Целевая вероятность вспомнить в назначенный срок.
     *
     * Девяносто процентов — обычная цель интервального повторения: ниже
     * слишком много мучений, выше слишком много лишних показов.
     */
    const val TARGET_RECALL = 0.9f

    /**
     * Во сколько раз период полузабывания длиннее назначенного срока.
     *
     * Из того же: если к сроку помнится девять из десяти, то `2^(-k) = 0,9`,
     * откуда `k ≈ 0,152`.
     */
    private const val DECAY = 0.152f

    /** Сколько ответов нужно, прежде чем подстраивать сроки под читателя. */
    private const val MIN_SAMPLE = 30

    /** Часы, в которые уместно напоминать. */
    private const val WAKE = 9
    private const val SLEEP = 22

    /**
     * Учитывает ответ.
     *
     * @param right вспомнил ли читатель. Промежуточных оценок нет намеренно:
     *   упражнения здесь объективные — собрать слово из букв можно верно или
     *   неверно, — и просить читателя ещё и оценить себя значило бы спрашивать
     *   то, чего он не знает.
     * @param ease поправка на то, как читатель отвечает на самом деле, из
     *   [ease].
     */
    fun review(
        card: Card,
        right: Boolean,
        intensity: Intensity,
        ease: Float = 1f,
        now: Long,
    ): Card {
        if (!right) {
            return card.copy(
                hp = min(FULL_HP, card.hp + MISS),
                streak = 0,
                intervalDays = 0,
                dueAt = now + RETRY_MINUTES * MINUTE,
                reviewedAt = now,
                dirty = true,
            )
        }

        val streak = card.streak + 1
        val step = LADDER[min(streak - 1, LADDER.lastIndex)]
        val minutes = max(RETRY_MINUTES, (step * intensity.stretch * ease).toLong())

        return card.copy(
            hp = max(0, card.hp - damage(card.streak)),
            streak = streak,
            intervalDays = (minutes / DAY).toInt(),
            dueAt = now + minutes * MINUTE,
            reviewedAt = now,
            dirty = true,
        )
    }

    /** Сколько прочности снимает верный ответ при такой серии. */
    private fun damage(streak: Int): Int = 20 + 5 * streak

    /**
     * Вероятность вспомнить слово прямо сейчас — от нуля до единицы.
     *
     * Кривая забывания в простейшем виде: помнится вдвое хуже за каждый период
     * полузабывания. Период считается из назначенного срока самой карточки, а
     * он у каждой свой, — отсюда и «индивидуальный график».
     *
     * Ноль для карточки, которую ещё не повторяли: она не забыта, её просто
     * никогда и не знали.
     */
    fun retention(card: Card, at: Long): Float {
        val span = card.dueAt - card.reviewedAt
        if (card.reviewedAt <= 0 || span <= 0) return 0f

        val elapsed = at - card.reviewedAt
        if (elapsed <= 0) return 1f

        val halfLife = span / DECAY
        return 2f.pow(-elapsed.toFloat() / halfLife).coerceIn(0f, 1f)
    }

    /** Когда карточка забудется наполовину. */
    fun halfForgottenAt(card: Card): Long? {
        val span = card.dueAt - card.reviewedAt
        if (card.reviewedAt <= 0 || span <= 0) return null
        return card.reviewedAt + (span / DECAY).toLong()
    }

    /** Карточки, которым пора. */
    fun due(cards: List<Card>, at: Long): List<Card> =
        cards.filter { !it.deleted && it.dueAt <= at }.sortedBy { it.dueAt }

    /** Выученные: прочность сведена к нулю. */
    fun learned(cards: List<Card>): List<Card> = cards.filter { !it.deleted && it.hp <= 0 }

    /**
     * Когда напомнить о повторении.
     *
     * Два условия, и срабатывает то, что наступит раньше.
     *
     * Первое — накопилось: созрела [Intensity.forgotten]-я карточка. Ради
     * одного слова человека не трогают, ради десятка — уже стоит.
     *
     * Второе — что-то забывается всерьёз: одна из карточек дошла до половины
     * своей кривой. Слово, лежащее просроченным месяц, заслуживает напоминания
     * даже в одиночку, иначе редкая колода не напомнит о себе никогда.
     *
     * Оба условия читаются с той же кривой, по которой назначены сроки, —
     * поэтому напоминание и приходит тогда, когда читатель на самом деле
     * начинает забывать, а не через равные сутки.
     *
     * `null` — напоминать не о чем.
     */
    fun reminderAt(cards: List<Card>, intensity: Intensity, now: Long): Long? {
        val active = cards.filter { !it.deleted && it.dueAt > 0 }
        if (active.isEmpty()) return null

        // Порог не может быть больше самой колоды: с тремя карточками ждать
        // восьмой означало бы не напомнить никогда.
        val target = min(intensity.forgotten, max(1, ceil(active.size / 2f).toInt()))

        val ripe = active.map { it.dueAt }.sorted()
        val batch = ripe.getOrNull(target - 1)
        val urgent = active.mapNotNull { halfForgottenAt(it) }.minOrNull()

        val at = listOfNotNull(batch, urgent).minOrNull() ?: return null
        return waking(max(at, now))
    }

    /**
     * Сдвигает момент в приличное время.
     *
     * Напоминание в четыре утра — не забота, а раздражение, и выключают после
     * него все уведомления сразу.
     */
    fun waking(at: Long): Long =
        if (localHour(at) in WAKE until SLEEP) at else atLocalHour(at, WAKE)

    /**
     * Поправка на то, как читатель отвечает на самом деле.
     *
     * Расписание рассчитано на девять верных ответов из десяти. Тот, кто
     * отвечает лучше, повторяет лишнее; тот, кто хуже, — не успевает
     * закрепить. Поправка растягивает или сжимает всю лесенку под него.
     *
     * До тридцати ответов поправки нет: по десятку ответов «точность» — это
     * шум, и подстраиваться под него значит гонять читателя туда-сюда.
     */
    fun ease(answers: Int, right: Int): Float {
        if (answers < MIN_SAMPLE) return 1f
        val accuracy = right.toFloat() / answers
        return (1f + (accuracy - TARGET_RECALL) * 3f).coerceIn(0.6f, 1.8f)
    }

    /** Через сколько дней покажется карточка — для подписи в интерфейсе. */
    fun daysAhead(card: Card, from: Long): Int =
        max(0, ((card.dueAt - from) + DAY * MINUTE - 1) / (DAY * MINUTE)).toInt()
}

private const val MINUTE = 60_000L
private const val DAY = 1440L
