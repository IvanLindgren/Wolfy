package com.wolfy.ffi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ядро на Rust: разбор слов, токенизация и чтение книг.
 *
 * Всё, что здесь есть, считается на устройстве и не ждёт сети — ради этого
 * ядро и линкуется в приложение. Единственное, что приходит из интернета, —
 * контекстный перевод, и он живёт в другом слое.
 *
 * Реализация зависит от платформы: на Android библиотека грузится через
 * `System.loadLibrary`, на Windows — через JNA. Общий код различия не видит.
 */
interface WolfyCore {
    /** Версия ядра — клиент сверяет её со своей при запуске. */
    fun version(): String

    /**
     * Разбирает слово: начальная форма, части речи, объяснение формы,
     * частотность, уровень.
     *
     * Быстрая операция — единицы микросекунд, вызывать можно прямо по тапу.
     */
    fun analyzeWord(word: String): WordAnalysis

    /** Разбивает текст на кликабельные токены и предложения. */
    fun tokenize(text: String): ParsedText

    /**
     * Разбирает грамматику предложения: время, залог, модальность, условие.
     *
     * На вход идёт предложение целиком, а не слово: разбор смотрит на соседей.
     * Границы предложений даёт [tokenize].
     *
     * Быстрая операция — доли миллисекунды, вызывать можно прямо по тапу.
     */
    fun explain(sentence: String): List<Finding>

    /**
     * Справочник грамматики целиком.
     *
     * Названия, формулы и объяснения приходят от тех же детекторов, что
     * разбирают книгу, — поэтому справочник не может разойтись с тем, что
     * читатель видит в карточке слова.
     */
    fun reference(): List<Article>

    /**
     * Микро-упражнения по грамматике.
     *
     * Верный ответ в них — то, что находит в примере тот же разбор, что
     * работает в читалке. Поэтому упражнение не может научить одному, а
     * разбор в книге показать другое.
     */
    fun exercises(): List<Exercise>

    /**
     * Открывает книгу и возвращает её описание вместе с номером.
     *
     * Номер обязателен к закрытию через [closeBook]: пока книга открыта, ядро
     * держит её файл.
     */
    fun openBook(path: String): OpenBook

    /**
     * Читает главу.
     *
     * Единственная тяжёлая операция ядра — вызывать только из фонового потока
     * (`Dispatchers.Default`), иначе просядет кадр.
     */
    fun readChapter(handle: Long, index: Int): Chapter

    /** Закрывает книгу и отпускает файл. */
    fun closeBook(handle: Long)

    /**
     * Открывает сессию — библиотеку и настройки читателя.
     *
     * Состояние держит ядро, а не клиент. Соблазн отдавать его туда-сюда
     * велик — переходы-то чистые, — но библиотека это десятки килобайт, а
     * прогресс чтения пишется при каждой прокрутке: гонять весь список книг
     * через границу по десять раз в секунду значит повторить ту ошибку, из-за
     * которой читалка и тормозила.
     *
     * Аргументы — записи, прочитанные с диска, или `null`, если их ещё нет.
     * Битую запись ядро молча заменяет пустой: падение на старте не оставило
     * бы читателю ничего.
     *
     * Номер обязателен к закрытию через [closeSession].
     */
    fun openSession(library: String?, settings: String?): Long

    /**
     * Выполняет команду над библиотекой или настройками.
     *
     * Одна функция вместо двадцати намеренно: каждая функция границы
     * описывается трижды — в ядре, в заголовке и здесь, — и двадцать операций
     * это шестьдесят мест, которые обязаны сойтись, а расходятся они молча.
     * Проверка при этом не потеряна: ядро разбирает команду в типизированное
     * перечисление и на незнакомую отвечает ошибкой с её именем.
     *
     * Команды и ответы собирает [com.wolfy.data.library.CoreSession] — руками
     * этот JSON писать не нужно.
     */
    fun runCommand(handle: Long, command: String): String

    /** Библиотека целиком — то, что клиент пишет на диск. */
    fun sessionLibrary(handle: Long): String

    /** Настройки целиком. */
    fun sessionSettings(handle: Long): String

    /**
     * Отмечает, что состояние записано на диск.
     *
     * Отдельным вызовом, а не внутри чтения: между «отдай мне библиотеку» и
     * «файл лёг на диск» запись может не удаться, и снимать пометку до того,
     * как это подтвердилось, значит однажды потерять главу.
     */
    fun sessionSaved(handle: Long, library: Boolean, settings: Boolean)

    /** Закрывает сессию. Несохранённое теряется. */
    fun closeSession(handle: Long)
}

/** Ошибка ядра: битая книга, закрытый номер, неподдерживаемый формат. */
class CoreException(message: String) : Exception(message)

/** Разбор слова для карточки. */
@Serializable
data class WordAnalysis(
    /** Слово так, как оно стоит в тексте. */
    val surface: String,
    /** Начальная форма — по ней слово ищется в колоде. */
    val lemma: String,
    /** Части речи в universal tagset: NOUN, VERB, ADJ… */
    val pos: List<String> = emptyList(),
    /**
     * Часть речи, по которой слово разобралось.
     *
     * У «glowed» это `VERB`, хотя лемма «glow» бывает и существительным.
     * `null` у слова, которое и есть начальная форма.
     */
    val matchedPos: String? = null,
    /**
     * Часть речи, которой слово чаще всего оказывается в живом тексте.
     *
     * У «green» это прилагательное, хотя оно бывает и существительным, и
     * глаголом. Ядро считает её по размеченному корпусу; `null` у слова,
     * которое в корпусе почти не встречается.
     */
    val dominantPos: String? = null,
    /** `lemma`, `regular`, `irregular` или `unknown`. */
    val form: String,
    /** Объяснения формы для карточки: «Число: множественное». */
    val facts: List<Fact> = emptyList(),
    /** Частотность по шкале Zipf: 6 — «the», 4 — обычное книжное слово. */
    val zipf: Float,
    /** Уровень по европейской шкале. */
    val cefr: String,
    /** Нашлось ли слово в словаре. */
    val known: Boolean,
) {
    /**
     * Часть речи для шапки карточки.
     *
     * Порядок ответов неслучаен. Разбор формы точнее всего: «glowed» — глагол,
     * даже если «glow» бывает и существительным. Дальше идёт преобладание по
     * корпусу — оно отвечает на вопрос про начальную форму, которую разбирать
     * не в чем: «green» это прилагательное. И только если молчат оба, берётся
     * первое значение из [pos], потому что порядок в нём ничего не значит и
     * выбирать там не из чего.
     */
    val primaryPos: String? get() = matchedPos ?: dominantPos ?: pos.firstOrNull()
}

/**
 * Что грамматический движок нашёл в предложении.
 *
 * Объяснение приходит от ядра готовым, а не собирается здесь: одно и то же
 * правило обязано объясняться одинаково и в карточке, и в справочнике, и в
 * тренировке, а держать формулировки в трёх местах — значит однажды их
 * рассогласовать.
 */
@Serializable
data class Finding(
    /** Устойчивое имя правила: `present-perfect`. По нему открывается справка. */
    val rule: String,
    /** Название для человека: «Present Perfect». */
    val title: String,
    /** Схема формулы: «have/has + V3». */
    val formula: String,
    val explanation: String,
    /** Первый токен разбора — индекс в [ParsedText.tokens]. */
    val start: Int,
    /** Токен за последним — полуинтервал. */
    val end: Int,
)

@Serializable
internal data class GrammarResult(val findings: List<Finding> = emptyList())

/**
 * Статья справочника.
 *
 * Пример и его перевод хранятся в ядре рядом с правилом: пример, оторванный
 * от правила, устаревает первым.
 */
@Serializable
data class Article(
    val rule: String,
    /** Раздел: `tenses`, `voice`, `modals`, `verbals`, `conditionals`. */
    val topic: String,
    val topicTitle: String,
    val title: String,
    val formula: String,
    val explanation: String,
    val example: String,
    val translation: String,
    /** Когда правило уместно — то, чего нет в разборе готовой фразы. */
    val usage: String,
)

@Serializable
internal data class ReferenceResult(val articles: List<Article> = emptyList())

/**
 * Микро-упражнение по грамматике.
 *
 * Задания два. `form` — правило названо, в предложении пропуск `___`, надо
 * поставить форму. `name` — предложение целиком, надо назвать правило; это
 * ровно то, что делает читалка, когда читатель тыкает в предложение.
 */
@Serializable
data class Exercise(
    val rule: String,
    val topic: String,
    /** `form` или `name`. */
    val task: String,
    val sentence: String,
    val translation: String,
    /** Название правила в задании на форму; в задании на узнавание пусто. */
    val question: String,
    val options: List<String>,
    val answer: Int,
    val formula: String,
    val explanation: String,
)

@Serializable
internal data class ExercisesResult(val exercises: List<Exercise> = emptyList())

/** Факт о форме слова: «Число» — «множественное, окончание -s». */
@Serializable
data class Fact(
    val label: String,
    val value: String,
)

/** Разобранный текст страницы. */
@Serializable
data class ParsedText(
    val tokens: List<Token> = emptyList(),
    val sentences: List<Sentence> = emptyList(),
) {
    /**
     * Предложение, внутри которого стоит символ с этой позицией.
     *
     * Это и есть контекст для перевода: читатель ткнул в слово, а переводить
     * надо фразу вокруг него.
     */
    fun sentenceAt(offset: Int): Sentence? =
        sentences.firstOrNull { offset >= it.start && offset < it.end }

    /** Токен под позицией касания. */
    fun tokenAt(offset: Int): Token? =
        tokens.firstOrNull { offset >= it.start && offset < it.end }
}

/**
 * Кусок текста с позицией.
 *
 * [start] и [end] — индексы в строке Kotlin, то есть в единицах UTF-16. Ядро
 * отдаёт их именно так, чтобы клиенту не пересчитывать смещения на каждый
 * кадр отрисовки.
 */
@Serializable
data class Token(
    /** `word`, `number`, `punctuation` или `space`. */
    val kind: String,
    val start: Int,
    val end: Int,
    val text: String,
) {
    /** Можно ли по токену тапнуть ради карточки. */
    val tappable: Boolean get() = kind == "word"
}

/** Предложение внутри текста. */
@Serializable
data class Sentence(
    val start: Int,
    val end: Int,
    @SerialName("firstToken") val firstToken: Int,
    @SerialName("lastToken") val lastToken: Int,
    val text: String,
)

/** Книга сразу после открытия. */
@Serializable
data class BookInfo(
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    /** Путь к обложке внутри книги. */
    val cover: String? = null,
    val chapters: List<ChapterInfo> = emptyList(),
)

@Serializable
data class ChapterInfo(val title: String? = null)

/** Открытая книга: её описание и номер для последующих обращений. */
data class OpenBook(
    val handle: Long,
    val info: BookInfo,
)

/** Глава книги. */
@Serializable
data class Chapter(
    val title: String? = null,
    val blocks: List<Block> = emptyList(),
) {
    /**
     * Весь текст главы одной строкой — то, что уходит в токенизатор.
     *
     * Блоки разделяются пустой строкой, потому что для ядра граница абзаца
     * это ещё и граница предложения: иначе заголовок главы прилип бы к первой
     * фразе и уехал в контекст перевода вместе с ней.
     */
    fun plainText(): String = blocks
        .mapNotNull { it.text }
        .joinToString("\n\n")
}

/**
 * Блок главы.
 *
 * Поля плоские, а не запечатанная иерархия: так их отдаёт ядро, и городить
 * поверх этого разбор в sealed-класс значило бы описать одно и то же дважды.
 */
@Serializable
data class Block(
    /** `heading`, `paragraph`, `quote`, `listItem`, `image` или `divider`. */
    val kind: String,
    val text: String? = null,
    /** Уровень заголовка: 1 — часть, 2 — глава. */
    val level: Int? = null,
    /** Путь к иллюстрации внутри книги. */
    val path: String? = null,
    val alt: String? = null,
)

/**
 * Создаёт ядро для текущей платформы.
 *
 * Загрузка библиотеки — единственное, что Android и Windows делают
 * по-разному: первый берёт `.so` из пакета приложения, второй — `.dll` рядом
 * с исполняемым файлом.
 */
expect fun createWolfyCore(): WolfyCore
