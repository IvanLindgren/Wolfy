package com.wolfy.srs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.Settings
import com.wolfy.data.library.Card
import com.wolfy.data.library.CoreSession
import com.wolfy.data.library.Library
import com.wolfy.data.library.command
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.data.utcOffsetMinutes
import com.wolfy.platform.cancelReviewReminder
import com.wolfy.platform.scheduleReviewReminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.put

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
    val hp: Int = FULL_HP,
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
 * Ведёт читателя по сегодняшней порции: что показать сейчас, что после и
 * когда остановиться. Больше ничего.
 *
 * Всё, что можно посчитать неправильно, считает ядро на Rust: какие карточки
 * созрели, какие новые правила подмешать, каким способом спросить, верен ли
 * ответ и какой у карточки следующий срок. Раньше это было написано здесь во
 * второй раз — и расходилось бы с Android при первой же правке.
 *
 * Очередь набирается один раз в начале порции и дальше не пересобирается.
 * Пересчитывать её после каждого ответа было бы «честнее», но карточка, на
 * которой читатель ошибся, созревает снова через десять минут и встала бы
 * следующей же — тренировка превратилась бы в спор с одним словом.
 */
class TrainingViewModel(
    private val library: Library,
    private val settings: Settings,
    private val session: CoreSession,
    private val now: () -> Long = { currentTimeMillis() },
) : ViewModel() {

    val hub: StateFlow<SrsUiState> = combine(
        library.state,
        settings.state,
    ) { _, saved ->
        val moment = now()
        SrsUiState(
            streakDays = saved.streakDays,
            bestStreak = saved.bestStreak,
            intensity = saved.reviewIntensity,
            decks = Deck.entries.map { status(it, moment) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SrsUiState())

    private val _training = MutableStateFlow(TrainingState())
    val training: StateFlow<TrainingState> = _training.asStateFlow()

    /** Очередь на сегодня: ключи заданий по порядку. */
    private var queue: List<String> = emptyList()

    /**
     * Правила, которые сегодня спрашивают впервые.
     *
     * Держатся рядом с очередью, потому что карточки правил заводятся лениво:
     * пока читатель не ответил, никакой карточки в библиотеке нет, а очереди
     * уже нужен ключ. Ключ у них — имя правила.
     */
    private var pending: Map<String, FreshRule> = emptyMap()

    private fun status(deck: Deck, at: Long): DeckStatus {
        val counted = session.run(
            command("deckStatus") {
                put("kind", deck.kind)
                put("now", at)
            },
        ).status ?: DeckCount(deck.kind)

        return DeckStatus(
            deck = deck,
            due = counted.due,
            total = counted.total,
            learned = counted.learned,
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
        val portion = session.run(
            command("trainingQueue") {
                put("kind", deck.kind)
                put("now", moment)
            },
        ).queue ?: Queue()

        queue = portion.keys
        pending = portion.rules.associateBy { it.rule }

        if (queue.isEmpty()) {
            _training.value = TrainingState(deck = deck, total = 0, finished = true)
            return
        }
        _training.value = TrainingState(deck = deck, position = 1, total = queue.size)
        show(0)
    }

    fun stop() {
        queue = emptyList()
        pending = emptyMap()
        _training.value = TrainingState()
    }

    /**
     * Проверяет ответ.
     *
     * Сравнение по словам, а не по строкам, — но делает его ядро: читатель
     * собирает фразу из плиток, между которыми пробелы ставит интерфейс, а в
     * книге у той же фразы есть ещё и запятая.
     */
    fun answer(given: String) {
        val state = _training.value
        val drill = state.drill ?: return
        // Второй ответ на то же задание не считается: иначе двойное нажатие
        // забирало бы у карточки два срока сразу.
        if (state.verdict != null) return

        val right = session.run(
            command("sameText") {
                put("assembled", given)
                put("expected", drill.answer)
            },
        ).right ?: false

        val moment = now()
        // Одной командой: расписание карточки и серия дней — это одно событие.
        // Двумя оно разъезжалось бы — ответ засчитан в серию, а карточка не
        // пересчитана, или наоборот.
        session.run(
            command("review") {
                put("cardId", drill.cardId)
                put("right", right)
                put("now", moment)
                put("offsetMinutes", utcOffsetMinutes(moment))
            },
        )
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
        val key = queue.getOrNull(index) ?: return
        val rule = pending[key]

        if (rule != null) {
            // Карточка правила заводится в этот момент, а не заранее: пока
            // правило не спросили, его нет ни в колоде, ни на сервере.
            val card = library.ruleCard(rule.rule, rule.title)
            val drill = session.run(
                command("ruleDrill") {
                    put("rule", rule.rule)
                    put("cardId", card.id)
                },
            ).drill ?: return
            _training.value = _training.value.copy(
                drill = drill,
                hp = card.hp,
                source = "Грамматика",
            )
            return
        }

        val card = card(key) ?: return
        val drill = session.run(command("drillFor") { put("cardId", key) }).drill ?: return

        _training.value = _training.value.copy(
            drill = drill,
            hp = card.hp,
            source = library.book(card.bookId)?.title.orEmpty(),
        )
    }

    private fun card(id: String): Card? =
        library.state.value.cards.firstOrNull { it.id == id && !it.deleted }

    /**
     * Переставляет напоминание.
     *
     * Зовётся после каждого ответа: срок карточки только что изменился, и
     * момент, когда о ней стоит напомнить, изменился вместе с ним. Дешевле,
     * чем кажется, — считается арифметика по списку в ядре, а системе
     * отдаётся один будильник.
     */
    private fun reschedule() {
        val moment = now()
        val at = session.run(
            command("reminderAt") {
                put("now", moment)
                put("offsetMinutes", utcOffsetMinutes(moment))
            },
        ).at

        if (at == null) {
            cancelReviewReminder()
            return
        }
        // Число в уведомлении — сколько созреет к тому моменту, а не сколько
        // ждёт сейчас: читатель прочтёт его тогда, а не теперь.
        val ripe = session.run(command("due") { put("now", at) }).cards.orEmpty().size
        scheduleReviewReminder(at, ripe)
    }
}
