package com.wolfy.srs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.Settings
import com.wolfy.data.library.Card
import com.wolfy.data.library.Library
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.ffi.Exercise
import com.wolfy.ffi.WolfyCore
import com.wolfy.platform.cancelReviewReminder
import com.wolfy.platform.scheduleReviewReminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Одна колода хаба: сколько ждёт, сколько всего, сколько выучено. */
@Immutable
data class DeckStatus(
    val deck: Deck,
    /** Карточек, которым пора. Это и есть число на карточке колоды. */
    val due: Int,
    val total: Int,
    val learned: Int,
) {
    /** Доля выученного — полоска под названием колоды. */
    val progress: Float get() = if (total == 0) 0f else learned.toFloat() / total
}

/** Что показывает хаб повторений. */
@Immutable
data class SrsUiState(
    val streakDays: Int = 0,
    val bestStreak: Int = 0,
    val intensity: Intensity = Intensity.Normal,
    val decks: List<DeckStatus> = emptyList(),
) {
    val due: Int get() = decks.sumOf { it.due }
}

/** Чем кончился ответ. */
@Immutable
data class Verdict(
    val right: Boolean,
    /** Верный ответ — показывается и при верном ответе тоже: так он запомнится. */
    val answer: String,
    /** Объяснение движка, если задание было грамматическим. */
    val explanation: String = "",
)

/** Состояние идущей тренировки. */
@Immutable
data class TrainingState(
    val deck: Deck? = null,
    val drill: Drill? = null,
    /** Номер карточки в сегодняшней порции, с единицы. */
    val position: Int = 0,
    val total: Int = 0,
    /** Прочность текущей карточки — те самые сердца над заданием. */
    val hp: Int = Scheduler.FULL_HP,
    /** Откуда слово: название книги. Пусто у правил и общих карточек. */
    val source: String = "",
    val verdict: Verdict? = null,
    /** Порция кончилась: показываем итог, а не следующее задание. */
    val finished: Boolean = false,
) {
    val running: Boolean get() = deck != null
}

/**
 * Тренировка повторений.
 *
 * Держит очередь на сегодня и ведёт по ней. Само расписание живёт в
 * [Scheduler], задания собирает [Drills], а здесь — только порядок: что
 * спросить сейчас, что после и когда остановиться.
 *
 * Очередь набирается один раз в начале порции и дальше не пересобирается.
 * Пересчитывать её после каждого ответа было бы «честнее», но карточка, на
 * которой читатель ошибся, созревает снова через десять минут и встала бы
 * следующей же — тренировка превратилась бы в спор с одним словом.
 */
class TrainingViewModel(
    private val library: Library,
    private val settings: Settings,
    private val core: WolfyCore,
    private val now: () -> Long = { currentTimeMillis() },
) : ViewModel() {

    /**
     * Упражнения по грамматике — из ядра, один раз за запуск.
     *
     * Их больше сотни, считаются они за доли миллисекунды, но пересчитывать их
     * на каждый кадр экрана незачем.
     */
    private val exercises: List<Exercise> by lazy {
        runCatching { core.exercises() }.getOrElse { emptyList() }
    }

    val hub: StateFlow<SrsUiState> = combine(
        library.state,
        settings.state,
    ) { library, saved ->
        val moment = now()
        SrsUiState(
            streakDays = saved.streakDays,
            bestStreak = saved.bestStreak,
            intensity = saved.reviewIntensity,
            decks = Deck.entries.map { status(it, library.cards, moment) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SrsUiState())

    private val _training = MutableStateFlow(TrainingState())
    val training: StateFlow<TrainingState> = _training.asStateFlow()

    /** Очередь на сегодня: идентификаторы карточек по порядку. */
    private var queue: List<String> = emptyList()

    /**
     * Правила, которые сегодня спрашивают.
     *
     * Держатся рядом с очередью, потому что карточки правил заводятся лениво:
     * пока читатель не ответил, никакой карточки в библиотеке нет, а очереди
     * уже нужен ключ.
     */
    private var pending: Map<String, Exercise> = emptyMap()

    private fun status(deck: Deck, cards: List<Card>, at: Long): DeckStatus {
        val mine = cards.filter { !it.deleted && it.kind == deck.kind }
        val due = Scheduler.due(mine, at).size
        val learned = Scheduler.learned(mine).size

        if (deck != Deck.Rules) {
            return DeckStatus(deck, due = due, total = mine.size, learned = learned)
        }

        // Грамматика — единственная колода, которая наполняется сама: правила
        // уже написаны, и ждать, пока читатель их наберёт, незачем. Но и
        // вываливать шесть десятков разом нельзя, поэтому новые подмешиваются
        // порцией в день.
        val started = mine.map { it.lemma }.toSet()
        val fresh = exercises.map { it.rule }.distinct().count { it !in started }
        return DeckStatus(
            deck = deck,
            due = due + minOf(fresh, NEW_RULES),
            total = mine.size + fresh,
            learned = learned,
        )
    }

    fun setIntensity(intensity: Intensity) {
        settings.setIntensity(intensity)
        // Уже назначенные сроки не переставляются: отобрать у читателя вечер,
        // который он себе распланировал, — плохая плата за смену настройки.
        // Меняется только то, когда о них напомнят.
        reschedule()
    }

    /** Набирает порцию и показывает первое задание. */
    fun start(deck: Deck) {
        val moment = now()
        val cards = library.state.value.cards.filter { !it.deleted && it.kind == deck.kind }
        val due = Scheduler.due(cards, moment).map { it.id }

        pending = if (deck == Deck.Rules) freshRules(cards) else emptyMap()
        queue = (due + pending.keys).take(PORTION)

        if (queue.isEmpty()) {
            _training.value = TrainingState(deck = deck, total = 0, finished = true)
            return
        }
        _training.value = TrainingState(deck = deck, position = 1, total = queue.size)
        show(0)
    }

    /** Новые правила порцией: ключ — имя правила, оно же ключ будущей карточки. */
    private fun freshRules(cards: List<Card>): Map<String, Exercise> {
        val started = cards.map { it.lemma }.toSet()
        return exercises
            .filter { it.rule !in started }
            .distinctBy { it.rule }
            .take(NEW_RULES)
            .associateBy { it.rule }
    }

    fun stop() {
        queue = emptyList()
        pending = emptyMap()
        _training.value = TrainingState()
    }

    /**
     * Проверяет ответ.
     *
     * Сравнение по словам, а не по строкам: читатель собирает фразу из плиток,
     * между которыми пробелы ставит интерфейс, а в книге у той же фразы есть
     * ещё и запятая.
     */
    fun answer(given: String) {
        val state = _training.value
        val drill = state.drill ?: return
        // Второй ответ на то же задание не считается: иначе двойное нажатие
        // забирало бы у карточки два срока сразу.
        if (state.verdict != null) return

        val right = Chunks.same(given, drill.answer)
        val moment = now()

        val card = card(drill.cardId)
        if (card != null) {
            library.updateCard(card.id) {
                Scheduler.review(
                    card = it,
                    right = right,
                    intensity = settings.current.reviewIntensity,
                    ease = settings.current.ease,
                    now = moment,
                )
            }
        }
        settings.recordAnswer(right, moment)
        reschedule()

        _training.value = state.copy(
            verdict = Verdict(
                right = right,
                answer = drill.answer,
                explanation = drill.explanation,
            ),
        )
    }

    /** Следующее задание — или итог, если порция кончилась. */
    fun next() {
        val state = _training.value
        val index = state.position
        if (index >= queue.size) {
            _training.value = state.copy(drill = null, verdict = null, finished = true)
            return
        }
        _training.value = state.copy(position = index + 1, verdict = null)
        show(index)
    }

    private fun show(index: Int) {
        val id = queue.getOrNull(index) ?: return
        val exercise = pending[id]

        if (exercise != null) {
            // Карточка правила заводится в этот момент, а не заранее: пока
            // правило не спросили, его нет ни в колоде, ни на сервере.
            val card = library.ruleCard(exercise.rule, exercise.question.ifBlank { exercise.rule })
            _training.value = _training.value.copy(
                drill = Drills.forRule(exercise, card.id),
                hp = card.hp,
                source = "Грамматика",
            )
            return
        }

        val card = card(id) ?: return
        val cards = library.state.value.cards
        val drill = when (card.kind) {
            Deck.Phrases.kind -> Drills.forPhrase(
                card = card,
                blocks = blocks(card),
                extra = strangers(card, cards),
            )

            else -> Drills.forWord(card, cards.filter { it.kind == Deck.Words.kind })
        }

        _training.value = _training.value.copy(
            drill = drill,
            hp = card.hp,
            source = library.book(card.bookId)?.title.orEmpty(),
        )
    }

    /**
     * Блоки фразы для конструктора.
     *
     * Считаются в момент показа, а не при сохранении: разбивка зависит от
     * разбора, разбор — от движка, а движок меняется от версии к версии.
     * Сохранённые однажды блоки через полгода разошлись бы с тем, что
     * показывает читалка на той же фразе.
     */
    private fun blocks(card: Card): List<String> {
        val sentence = card.surface
        val parsed = runCatching { core.tokenize(sentence) }.getOrNull() ?: return emptyList()
        val findings = runCatching { core.explain(sentence) }.getOrElse { emptyList() }
        return Chunks.split(sentence, parsed, findings)
    }

    /**
     * Лишние блоки в банк слов — из чужих фраз той же колоды.
     *
     * Чужой блок правдоподобен ровно потому, что он настоящий: «has read» из
     * соседнего предложения выглядит уместно рядом с «have been reading», а
     * сочетание, выдуманное приложением, — нет.
     */
    private fun strangers(card: Card, cards: List<Card>): List<String> = cards
        .filter { it.kind == Deck.Phrases.kind && it.id != card.id && !it.deleted }
        .flatMap { blocks(it) }
        .distinct()
        .filter { it.isNotBlank() }
        .take(3)

    private fun card(id: String): Card? =
        library.state.value.cards.firstOrNull { it.id == id && !it.deleted }

    /**
     * Переставляет напоминание.
     *
     * Зовётся после каждого ответа: срок карточки только что изменился, и
     * момент, когда о ней стоит напомнить, изменился вместе с ним. Дешевле,
     * чем кажется, — считается арифметика по списку в памяти, а системе
     * отдаётся один будильник.
     */
    private fun reschedule() {
        val cards = library.state.value.cards.filter { !it.deleted }
        val moment = now()
        val at = Scheduler.reminderAt(cards, settings.current.reviewIntensity, moment)
        if (at == null) {
            cancelReviewReminder()
            return
        }
        // Число в уведомлении — сколько созреет к тому моменту, а не сколько
        // ждёт сейчас: читатель прочтёт его тогда, а не теперь.
        scheduleReviewReminder(at, Scheduler.due(cards, at).size)
    }

    private companion object {
        /**
         * Сколько карточек за раз.
         *
         * Двадцать — примерно пять минут, и это тот размер, после которого
         * тренировку закрывают довольными, а не уставшими. Остальные дождутся
         * следующего захода: они никуда не денутся.
         */
        const val PORTION = 20

        /** Сколько новых правил подмешивать в день. */
        const val NEW_RULES = 5
    }
}
