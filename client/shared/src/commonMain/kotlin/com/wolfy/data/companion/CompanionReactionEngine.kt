package com.wolfy.data.companion

/**
 * Локальный движок реплик обычного чтения.
 *
 * Работает без сети: на вход приходят событие чтения и контекст сессии, на
 * выходе либо реплика, либо ничего. Правила частоты жёсткие: не чаще одной
 * непрошеной реплики в семь минут, не больше пяти за час, никакого повтора
 * текста и никакого показа поверх активных жестов и карточек.
 *
 * Все решения детерминированы: [seed] выводится из профиля и дня, поэтому
 * одинаковая последовательность событий даёт одинаковые реплики. Это делает
 * движок проверяемым тестами с фальшивыми часами.
 */
class CompanionReactionEngine(
    private val pack: CompanionPhrasePack,
    private val clock: () -> Long,
    private val seed: Long,
) {
    /** Событие чтения, на которое движок может ответить. */
    sealed interface Event {
        /** Открытие читалки. */
        data object SessionStart : Event

        /** Возврат после паузы внутри сессии. */
        data object SessionResume : Event

        /** Ровное чтение: событие тикает раз в минуту. */
        data object SteadyReading : Event

        /** Дочитана страница. */
        data object PageCompleted : Event

        /** Дочитана глава. */
        data object ChapterCompleted : Event

        /** Сессия длинная, стоит мягко намекнуть на паузу. */
        data object LongSession : Event

        /** Возврат после большого перерыва между сессиями. */
        data object ReturnAfterBreak : Event

        /** Страница оказалась трудной по локальной оценке. */
        data class DifficultPage(val difficulty: Float) : Event

        /** Настроение страницы по локальной оценке. */
        data class Mood(val mood: String) : Event
    }

    /** Всё, что движок должен знать о моменте показа. */
    data class Context(
        /** Минуты с начала сессии. */
        val sessionMinutes: Int,
        /** Состояние блокировки: жест, карточка, диалог, сворачивание. */
        val overlayOpen: Boolean,
        /** Прокрутка активна или не успокоилась. */
        val scrolling: Boolean,
        /** Реплики включены читателем. */
        val reactionsEnabled: Boolean = true,
    )

    /** Решение: показать реплику или промолчать. */
    data class Decision(val phrase: CompanionPhrase?, val at: Long)

    private val recentIds = ArrayDeque<String>()
    private val recentTexts = ArrayDeque<String>()
    private var nextAllowedAt = 0L
    private val scenarioCooldownUntil = mutableMapOf<String, Long>()
    private var shownThisSession = 0
    private var rng = seed.toULong()

    /** История последних показов: движок переживает пересоздание при повороте. */
    fun restoreHistory(ids: List<String>, nextAllowedAt: Long, shownThisSession: Int) {
        recentIds.clear()
        recentIds.addAll(ids.takeLast(20))
        this.nextAllowedAt = nextAllowedAt
        this.shownThisSession = shownThisSession
    }

    fun historyIds(): List<String> = recentIds.toList()

    fun nextAllowedAt(): Long = nextAllowedAt

    fun shownThisSession(): Int = shownThisSession

    /**
     * Просит решение по событию.
     *
     * Ручные действия читателя (вопросы к книге) сюда не приходят: у них
     * отдельный путь и отдельный кулдаун.
     */
    fun decide(event: Event, context: Context): Decision {
        val now = clock()
        val silence = Decision(null, now)
        if (!context.reactionsEnabled) return silence
        if (context.overlayOpen || context.scrolling) return silence
        val scenario = scenarioOf(event) ?: return silence
        // Глобальная пауза: не чаще одной реплики в семь минут, кулдаун
        // конкретной реплики может тянуть дольше.
        if (now < nextAllowedAt) return silence
        if (now < (scenarioCooldownUntil[scenario] ?: 0L)) return silence
        val prompted = isPrompted(event)
        if (!prompted && shownThisSession >= MAX_PER_SESSION) return silence

        val candidates = pack.phrases.filter { it.scenario == scenario }
            .filter { it.id !in recentIds }
            .filter { it.text !in recentTexts }
            .filter { context.sessionMinutes >= it.minMinutes }
            .filter { it.moods.isEmpty() || (event is Event.Mood && it.moods.contains(event.mood)) }
        if (candidates.isEmpty()) return silence

        val chosen = candidates[pickIndex(candidates.size)]
        val cooldownMs = chosen.cooldownMinutes.coerceIn(0, MAX_COOLDOWN) * 60_000L
        nextAllowedAt = now + maxOf(UNPROMPTED_GAP_MS, cooldownMs)
        scenarioCooldownUntil[scenario] = now + cooldownMs
        if (!prompted) shownThisSession += 1
        remember(chosen.id, chosen.text)
        return Decision(chosen, now)
    }

    /** Отметка показа снаружи: ручные действия тоже держат тишину. */
    fun noteManualShow() {
        nextAllowedAt = clock() + UNPROMPTED_GAP_MS
    }

    /** Сброс сессионных счётчиков при новом открытии читалки. */
    fun newSession() {
        shownThisSession = 0
    }

    private fun isPrompted(event: Event): Boolean = when (event) {
        is Event.SessionStart, is Event.ChapterCompleted, is Event.ReturnAfterBreak -> true
        else -> false
    }

    private fun scenarioOf(event: Event): String? = when (event) {
        is Event.SessionStart -> "session_start"
        is Event.SessionResume -> "session_resume"
        is Event.SteadyReading -> "steady_reading"
        is Event.PageCompleted -> "page_completed"
        is Event.ChapterCompleted -> "chapter_completed"
        is Event.LongSession -> "long_session"
        is Event.ReturnAfterBreak -> "return_after_break"
        is Event.DifficultPage -> "difficult_page"
        is Event.Mood -> when (event.mood) {
            MoodScorer.JOY -> "mood_joy"
            MoodScorer.SADNESS -> "mood_sadness"
            MoodScorer.TENSION -> "mood_tension"
            MoodScorer.MYSTERY -> "mood_mystery"
            else -> null
        }
    }

    /** Детерминированный выбор: LCG вместо случайности платформы. */
    private fun pickIndex(size: Int): Int {
        rng = rng * 6364136223846793005UL + 1442695040888963407UL
        return (((rng shr 33).toLong() and Long.MAX_VALUE) % size).toInt()
    }

    private fun remember(id: String, text: String) {
        recentIds.addLast(id)
        if (recentIds.size > 20) recentIds.removeFirst()
        recentTexts.addLast(text)
        if (recentTexts.size > 10) recentTexts.removeFirst()
    }

    companion object {
        /** Минимальная пауза между непрошеными репликами. */
        const val UNPROMPTED_GAP_MS = 7 * 60_000L

        /** Больше пяти непрошеных реплик за сессию быть не должно. */
        const val MAX_PER_SESSION = 5

        /** Верхняя граница кулдауна реплики в минутах. */
        const val MAX_COOLDOWN = 120

        /** Seed из профиля и дня: дни меняют реплики, устройства согласованы. */
        fun seedFor(profileId: String, epochDay: Long): Long {
            var hash = 1469598103934665603UL
            for (ch in profileId) {
                hash = hash xor (ch.code.toLong().toULong() and 0xFFUL)
                hash *= 1099511628211UL
            }
            hash = hash xor (epochDay.toULong() * 31UL)
            hash *= 1099511628211UL
            return (hash and 0x7FFFFFFFFFFFFFFFUL).toLong()
        }
    }
}
