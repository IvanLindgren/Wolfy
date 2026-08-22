package com.wolfy.srs

import com.wolfy.data.library.Card
import com.wolfy.ffi.Exercise

/** Три колоды хаба повторений. */
enum class Deck(val title: String, val subtitle: String) {
    Words("Слова", "из книг, которые вы читаете"),
    Phrases("Фразы", "устойчивые обороты"),
    Rules("Грамматика", "правила и конструкции"),
}

/**
 * Каким способом спрашивают.
 *
 * Способы стоят не рядом, а друг за другом: сперва узнать, потом собрать,
 * потом вспомнить с нуля. Узнавание — самое лёгкое, и начинать с ввода по
 * памяти значит требовать от читателя того, чего он ещё не умеет; а
 * заканчивать узнаванием значит не проверить ничего, потому что выбрать
 * верный перевод из четырёх можно, не зная слова.
 */
enum class DrillKind {
    /** Выбрать один вариант из четырёх. */
    Choice,

    /** Собрать слово из букв, часть которых уже стоит на месте. */
    Letters,

    /** Ввести слово по памяти. */
    Typing,

    /** Собрать фразу из блоков. */
    Builder,

    /** Поставить форму в пропуск. */
    Gap,
}

/**
 * Одно задание тренировки.
 *
 * Плоская запись без наследования: у пяти способов спросить общего гораздо
 * больше, чем различного, — вопрос, ответ и набор кусочков есть у каждого, — а
 * пять классов заставили бы экран разбирать их по типам вместо того, чтобы
 * просто нарисовать.
 */
data class Drill(
    /** Карточка, к которой относится ответ. */
    val cardId: String,
    val kind: DrillKind,
    /**
     * Что показывают крупно: перевод слова, русская фраза, название правила.
     */
    val question: String,
    /**
     * Строка помельче под вопросом: предложение из книги с пропуском на месте
     * слова, пример правила, подпись «соберите слово».
     */
    val subject: String = "",
    /** С чем сверяют ответ. */
    val answer: String,
    /** Варианты, буквы или блоки — смотря что за способ. */
    val pieces: List<String> = emptyList(),
    /**
     * Буквы, открытые заранее, — номера позиций в [answer].
     *
     * Открывать часть букв не поблажка: слово из двенадцати букв, собранное
     * по одной, превращается в головоломку про перебор, а проверяется в ней
     * терпение, а не память.
     */
    val given: Set<Int> = emptySet(),
    /** Правило, если задание грамматическое. */
    val rule: String = "",
    val formula: String = "",
    /** Что показать после ответа. */
    val explanation: String = "",
)

/** Сборка заданий по карточке. */
object Drills {
    /** Ниже этой прочности слово уже узнают — пора собирать его самому. */
    private const val ASSEMBLE_BELOW = 75

    /** А ниже этой — вспоминать с нуля. */
    private const val RECALL_BELOW = 45

    /** Сколько букв держать открытыми, долей от длины слова. */
    private const val REVEALED = 0.55f

    /** Размер поля букв в макете — четыре в ряд. */
    private const val POOL = 12

    /**
     * Задание по слову.
     *
     * Способ выбирается по прочности карточки: пока она высокая, слово
     * узнают, дальше собирают, под конец вспоминают. Выбор из четырёх
     * возможен, только если в колоде есть чужие переводы, — придумывать
     * правдоподобно неверный перевод приложению нечем, а «дом / стол / бегать»
     * рядом с «библиотека» не проверяют ничего.
     */
    fun forWord(card: Card, deck: List<Card>, seed: Int = card.id.hashCode()): Drill {
        val prompt = card.translation.ifBlank { card.lemma }
        val sentence = blanked(card)

        val others = deck
            .filter { it.id != card.id && !it.deleted && it.translation.isNotBlank() }
            .map { it.translation }
            .distinct()

        val kind = when {
            card.hp >= ASSEMBLE_BELOW && card.translation.isNotBlank() && others.size >= 3 ->
                DrillKind.Choice

            card.hp >= RECALL_BELOW -> DrillKind.Letters
            else -> DrillKind.Typing
        }

        return when (kind) {
            DrillKind.Choice -> Drill(
                cardId = card.id,
                kind = DrillKind.Choice,
                question = card.surface.ifBlank { card.lemma },
                subject = sentence,
                answer = card.translation,
                pieces = shuffled(listOf(card.translation) + others.take(3), seed),
            )

            DrillKind.Letters -> {
                val word = card.lemma
                val given = revealed(word, seed)
                Drill(
                    cardId = card.id,
                    kind = DrillKind.Letters,
                    question = prompt,
                    subject = sentence,
                    answer = word,
                    pieces = pool(word, given, seed),
                    given = given,
                )
            }

            else -> Drill(
                cardId = card.id,
                kind = DrillKind.Typing,
                question = prompt,
                subject = sentence,
                answer = card.lemma,
            )
        }
    }

    /**
     * Задание по фразе: собрать английскую из блоков по русской.
     *
     * Блоки режет [chunks] — по глагольным цепочкам и служебным словам, а не
     * по одному слову: «have been reading» это одна мысль, и рассыпать её на
     * три плитки значит превратить упражнение о времени в упражнение о
     * порядке слов.
     */
    fun forPhrase(
        card: Card,
        blocks: List<String>,
        extra: List<String> = emptyList(),
        seed: Int = card.id.hashCode(),
    ): Drill = Drill(
        cardId = card.id,
        kind = DrillKind.Builder,
        question = card.translation.ifBlank { "Соберите фразу" },
        subject = card.context,
        answer = card.surface,
        pieces = shuffled(blocks + extra.take(3), seed),
    )

    /** Задание по правилу — целиком из ядра. */
    fun forRule(exercise: Exercise, cardId: String): Drill = Drill(
        cardId = cardId,
        kind = if (exercise.task == "form") DrillKind.Gap else DrillKind.Choice,
        question = exercise.question.ifBlank { "Что здесь за правило?" },
        subject = exercise.sentence,
        answer = exercise.options.getOrElse(exercise.answer) { "" },
        pieces = exercise.options,
        rule = exercise.rule,
        formula = exercise.formula,
        explanation = exercise.explanation,
    )

    /**
     * Предложение из книги с пропуском на месте слова.
     *
     * Без пропуска предложение выдало бы ответ: читатель собирает слово из
     * букв, а оно стоит строкой выше.
     */
    fun blanked(card: Card): String {
        val sentence = card.context.trim()
        if (sentence.isEmpty()) return ""

        val target = card.surface.ifBlank { card.lemma }
        val at = sentence.indexOf(target, ignoreCase = true)
        if (at < 0) return sentence

        return sentence.substring(0, at) + "…" + sentence.substring(at + target.length)
    }

    /** Какие буквы стоят на месте с самого начала. */
    private fun revealed(word: String, seed: Int): Set<Int> {
        if (word.length <= 2) return emptySet()

        val hide = (word.length * (1f - REVEALED)).toInt().coerceIn(2, 6)
        val random = Lcg(seed)
        val positions = word.indices.toMutableList()
        // Перемешиваем и прячем первые: так спрятанные буквы разбросаны по
        // слову, а не собраны в хвосте.
        for (i in positions.indices.reversed()) {
            val j = random.next(i + 1)
            positions[i] = positions[j].also { positions[j] = positions[i] }
        }
        return word.indices.toSet() - positions.take(hide).toSet()
    }

    /** Поле букв: спрятанные вперемешку с лишними. */
    private fun pool(word: String, given: Set<Int>, seed: Int): List<String> {
        val hidden = word.indices.filter { it !in given }.map { word[it].toString() }
        val random = Lcg(seed + 1)
        val fillers = buildList {
            while (hidden.size + size < POOL) {
                add(FILLERS[random.next(FILLERS.length)].toString())
            }
        }
        return shuffled(hidden + fillers, seed + 2)
    }

    /**
     * Перемешивает одинаково при каждом показе.
     *
     * Не случайно: то же задание при повторе обязано выглядеть так же, иначе
     * читатель запоминает не слово, а расположение плиток — и «вспоминает»
     * его ровно до первой перестановки.
     */
    fun <T> shuffled(items: List<T>, seed: Int): List<T> {
        val out = items.toMutableList()
        val random = Lcg(seed)
        for (i in out.indices.reversed()) {
            val j = random.next(i + 1)
            out[i] = out[j].also { out[j] = out[i] }
        }
        return out
    }

    /** Буквы для лишних плиток — по убыванию частоты в английском. */
    private const val FILLERS = "etaoinshrdlcumwfgypbvk"
}

/**
 * Линейный конгруэнтный генератор.
 *
 * Свой, а не [kotlin.random.Random]: перемешивание обязано повторяться от
 * запуска к запуску и одинаково на телефоне и на компьютере, а гарантии
 * стандартного генератора на это не распространяются.
 */
internal class Lcg(seed: Int) {
    private var state: Int = if (seed == 0) 1 else seed

    /** Число от нуля до `bound` не включая. */
    fun next(bound: Int): Int {
        if (bound <= 1) return 0
        state = state * 1_103_515_245 + 12_345
        return ((state ushr 16) and 0x7FFF) % bound
    }
}
