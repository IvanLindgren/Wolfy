package com.wolfy.ui.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.TranslateResult
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.data.dictionary.DictionaryManager
import com.wolfy.ffi.Chapter
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.PreparedChapter
import com.wolfy.ffi.Sentence
import com.wolfy.ffi.Token
import com.wolfy.ffi.WolfyCore
import com.wolfy.ui.card.TranslationState
import com.wolfy.ui.card.DefinitionState
import com.wolfy.ui.card.WordCardState
import com.wolfy.widgets.GraphLink
import com.wolfy.widgets.GraphWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние экрана чтения.
 *
 * Одна иммутабельная структура на весь экран: любое изменение — это новое
 * состояние целиком, и рассинхронизации между «какая глава открыта» и «что
 * нарисовано» не бывает по построению.
 */
@Immutable
data class ReaderState(
    val loading: Boolean = true,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    /**
     * Названия глав в порядке книги.
     *
     * Оглавление живёт здесь, а не запрашивается отдельно: ядро отдаёт его
     * вместе с книгой одним вызовом, и второй поход за тем же списком был бы
     * лишним.
     */
    val chapters: List<String> = emptyList(),
    /** Блоки главы вместе с разбором каждого текстового блока. */
    val blocks: List<ReaderBlock> = emptyList(),
    /** Начальные формы слов, уже сохранённых в колоду этой книги. */
    val savedLemmas: Set<String> = emptySet(),
    val card: WordCardState? = null,
    /**
     * Номер блока, в котором стоит открытое слово.
     *
     * Без него подсветку пришлось бы искать по смещениям, а смещения у каждого
     * блока свои: слово с позиции 10 есть и в третьем абзаце, и в седьмом, и
     * подсвечивались бы оба. Заодно это избавляет от перерисовки всех видимых
     * абзацев на каждый тап — меняется ровно тот, где нашлось слово.
     */
    val selectedBlock: Int = -1,
    /**
     * Доля главы, с которой надо начать показ.
     *
     * Нужна ровно один раз — при открытии книги, чтобы вернуть читателя туда,
     * где он остановился. Дальше прокруткой распоряжается он сам.
     */
    val startAt: Float = 0f,
    val error: String? = null,
) {
    val chapterCount: Int get() = chapters.size
    val hasPrevious: Boolean get() = chapterIndex > 0
    val hasNext: Boolean get() = chapterIndex + 1 < chapterCount
}

/**
 * Блок главы, готовый к отрисовке.
 *
 * Разбор на токены лежит рядом с текстом, а не считается при отрисовке: тап по
 * слову должен попадать в тот же разбор, который нарисовал подсветку.
 */
@Immutable
data class ReaderBlock(
    val kind: String,
    val text: String,
    val level: Int?,
    val parsed: ParsedText?,
    val imagePath: String?,
    val alt: String?,
)

/**
 * Экран чтения: открывает книгу, читает главы и отвечает на тапы по словам.
 *
 * Разделение работы жёсткое. Всё, что делает ядро — разбор книги, токенизация,
 * анализ слова, — уходит в [Dispatchers.Default]: это счёт, и в главном потоке
 * он съел бы кадры. Сеть живёт в своих корутинах и никогда не задерживает
 * открытие карточки.
 */
class ReaderViewModel(
    private val core: WolfyCore,
    private val api: WolfyApi,
    private val library: Library,
    private val dictionary: DictionaryManager,
    private val clock: () -> Long = { currentTimeMillis() },
) : ViewModel() {

    /** Книга, открытая сейчас. По ней читалка отчитывается библиотеке. */
    private var bookId: String? = null

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /** Номер открытой книги в ядре. Пока он есть, ядро держит её файл. */
    private var handle: Long? = null

    /** Запрос перевода для текущей карточки — новый тап отменяет предыдущий. */
    private var translationJob: Job? = null

    /** Поиск определения отделён от перевода: ни один не ждёт другого. */
    private var definitionJob: Job? = null

    /** Последняя записанная доля главы — по ней отсекается лишняя запись. */
    private var lastReportedPlace: Float? = null

    /** Момент последней записи и доля, которую ещё не записали. */
    private var lastWriteAt: Long = 0
    private var pendingPlace: Float? = null

    /** Подписка на колоду книги. Живёт, пока книга открыта. */
    private var deckJob: Job? = null

    /**
     * Открывает книгу библиотеки на том месте, где читатель остановился.
     *
     * Название и число глав ядро узнаёт только сейчас — при добавлении книги
     * их взять было неоткуда. Поэтому библиотека их и получает здесь, а не при
     * импорте: разбирать книгу дважды ради строчки в списке незачем.
     */
    fun open(book: LibraryBook) {
        closeCurrent()
        bookId = book.id

        // Колода читается из библиотеки подпиской, а не копией: слово можно
        // убрать и на экране повторений, и подсветка на странице обязана
        // погаснуть вместе с ним.
        deckJob = viewModelScope.launch {
            library.state.collect { current ->
                val deck = current.deck(book.id).map { it.lemma }.toSet()
                _state.update { it.copy(savedLemmas = deck) }
            }
        }

        viewModelScope.launch {
            _state.update {
                ReaderState(
                    loading = true,
                    savedLemmas = library.deck(book.id).map { it.lemma }.toSet(),
                    startAt = book.progress.withinChapter,
                )
            }
            try {
                val opened = withContext(Dispatchers.Default) { core.openBook(book.path) }
                handle = opened.handle
                val title = opened.info.title?.takeIf { it.isNotBlank() } ?: book.title
                _state.update {
                    it.copy(
                        bookTitle = title,
                        chapters = opened.info.chapters.mapIndexed { number, chapter ->
                            // У главы бывает не быть названия — в обычном
                            // тексте их нет вовсе. «Глава 4» лучше пустой
                            // строки: по пустому списку не выбрать.
                            chapter.title?.takeIf { it.isNotBlank() } ?: "Глава ${number + 1}"
                        },
                    )
                }
                library.describe(
                    id = book.id,
                    title = title,
                    author = opened.info.author,
                    chapters = opened.info.chapters.size,
                )
                loadChapter(book.progress.chapter.coerceIn(0, (opened.info.chapters.size - 1).coerceAtLeast(0)))
            } catch (e: CoreException) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "книга не открылась")
                }
            }
        }
    }

    /** Читает главу и разбирает её текст. */
    fun loadChapter(index: Int) {
        val handle = handle ?: return
        flushPlace()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, card = null) }
            try {
                val (title, blocks) = withContext(Dispatchers.Default) {
                    // Один тяжёлый переход вместо N токенизаций по блокам (§15)
                    try {
                        val prepared = core.preparedChapter(handle, index)
                        prepared.title to prepared.toReaderBlocks()
                    } catch (_: Throwable) {
                        val chapter = core.readChapter(handle, index)
                        chapter.title to chapter.toReaderBlocks(core)
                    }
                }
                _state.update {
                    it.copy(
                        loading = false,
                        chapterIndex = index,
                        chapterTitle = title.orEmpty(),
                        blocks = blocks,
                        error = null,
                    )
                }
                // Место в книге запоминается сразу, а не при выходе: приложение
                // на телефоне закрывают свайпом, и «сохраню потом» означает
                // «не сохраню».
                // Переход к другой главе начинает её с начала: место внутри
                // главы имеет смысл только для той главы, где его записали.
                val restore = _state.value.startAt.takeIf { it > 0f } ?: 0f
                lastReportedPlace = restore
                bookId?.let { library.rememberProgress(it, index, restore) }
            } catch (e: CoreException) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "глава не прочиталась")
                }
            }
        }
    }

    fun nextChapter() {
        if (_state.value.hasNext) loadChapter(_state.value.chapterIndex + 1)
    }

    fun previousChapter() {
        if (_state.value.hasPrevious) loadChapter(_state.value.chapterIndex - 1)
    }

    /**
     * Читатель нажал по слову.
     *
     * Карточка открывается тем же кадром: shell видна сразу, локальный разбор
     * догоняет в фоне одним [WolfyCore.inspectWord]. Перевод не задерживает
     * анимацию — он идёт параллельно и независимо.
     */
    fun onWordTap(block: Int, token: Token, parsed: ParsedText) {
        val context = parsed.sentenceAt(token.start)?.text ?: token.text

        // Shell сразу — анимация не ждёт даже локального разбора.
        val placeholder = com.wolfy.ffi.WordAnalysis(
            surface = token.text,
            lemma = token.text,
            pos = emptyList(),
            matchedPos = null,
            dominantPos = null,
            form = "unknown",
            facts = emptyList(),
            zipf = 0f,
            cefr = "C2",
            known = false,
        )
        _state.update {
            it.copy(
                selectedBlock = block,
                card = WordCardState(
                    token = token,
                    analysis = placeholder,
                    context = context,
                    grammar = emptyList(),
                    sentenceTokens = parsed.tokens,
                    chunks = emptyList(),
                    markers = emptyList(),
                    graphWords = emptyList(),
                    graphLinks = emptyList(),
                    translation = TranslationState.Loading,
                    definition = DefinitionState.Loading,
                    saved = token.text.lowercase() in it.savedLemmas,
                ),
            )
        }

        // Отменяем предыдущие запросы — новый тап перекрывает старый.
        definitionJob?.cancel()
        translationJob?.cancel()

        // Перевод стартует сразу, не дожидаясь локального разбора.
        translationJob = viewModelScope.launch {
            val word = async { api.translate(token.text) }
            val sentence = if (context == token.text) null else async { api.translate(context) }
            val wordResult = word.await()
            val sentenceResult = sentence?.await()
            _state.update { current ->
                val card = current.card ?: return@update current
                if (current.selectedBlock != block || card.token.start != token.start) return@update current
                current.copy(
                    card = card.copy(
                        translation = when (wordResult) {
                            is TranslateResult.Ready -> TranslationState.Ready(
                                word = wordResult.text,
                                sentence = (sentenceResult as? TranslateResult.Ready)?.text.orEmpty(),
                            )
                            is TranslateResult.Failed -> TranslationState.Failed(wordResult.message)
                        },
                    ),
                )
            }
        }

        // Локальный разбор одним переходом — в фоне, не блокируя анимацию.
        viewModelScope.launch {
            try {
                val inspected = withContext(Dispatchers.Default) {
                    try {
                        core.inspectWord(token.text, context)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (inspected != null) {
                    val sentenceTokens = inspected.toTokens(context)
                    _state.update { current ->
                        val card = current.card ?: return@update current
                        if (current.selectedBlock != block || card.token.start != token.start) return@update current
                        current.copy(
                            card = card.copy(
                                analysis = inspected.word,
                                grammar = inspected.findings,
                                sentenceTokens = sentenceTokens,
                                chunks = inspected.chunks,
                                markers = inspected.markers,
                                graphWords = inspected.graphWords.map { GraphWord(it.text, it.tag) },
                                graphLinks = inspected.graphLinks.map { GraphLink(it.from, it.to, it.label) },
                                saved = inspected.word.lemma in current.savedLemmas,
                            ),
                        )
                    }
                    // Определение — после того как узнали лемму.
                    definitionJob?.cancel()
                    definitionJob = viewModelScope.launch {
                        val entry = dictionary.define(inspected.word.lemma)
                        _state.update { current ->
                            val card = current.card ?: return@update current
                            if (current.selectedBlock != block || card.token.start != token.start) return@update current
                            current.copy(card = card.copy(definition = entry?.let(DefinitionState::Ready) ?: DefinitionState.Missing))
                        }
                    }
                } else {
                    // Fallback: старая развёртка если inspectWord отсутствует
                    val analysis = withContext(Dispatchers.Default) { core.analyzeWord(token.text) }
                    val grammar = withContext(Dispatchers.Default) { core.explain(context) }
                    val sentenceTokens = withContext(Dispatchers.Default) { core.tokenize(context).tokens }
                    _state.update { current ->
                        val card = current.card ?: return@update current
                        if (current.selectedBlock != block || card.token.start != token.start) return@update current
                        current.copy(
                            card = card.copy(
                                analysis = analysis,
                                grammar = grammar.findings,
                                sentenceTokens = sentenceTokens,
                                chunks = grammar.chunks,
                                markers = grammar.markers,
                                saved = analysis.lemma in current.savedLemmas,
                            ),
                        )
                    }
                    definitionJob = viewModelScope.launch {
                        val entry = dictionary.define(analysis.lemma)
                        _state.update { current ->
                            val card = current.card ?: return@update current
                            if (current.selectedBlock != block || card.token.start != token.start) return@update current
                            current.copy(card = card.copy(definition = entry?.let(DefinitionState::Ready) ?: DefinitionState.Missing))
                        }
                    }
                }
            } catch (_: Exception) {
                // Тихо — карточка уже видна с placeholder, сеть продолжает работу.
            }
        }
    }

    fun dismissCard() {
        translationJob?.cancel()
        definitionJob?.cancel()
        _state.update { it.copy(card = null, selectedBlock = -1) }
    }

    /**
     * Кладёт слово в колоду книги или забирает обратно.
     *
     * Именно переключателем, а не двумя действиями: слово попадает в колоду
     * одним касанием, и передумать нужно тем же касанием. Отдельная кнопка
     * «убрать» на карточке из четырёх строк стоила бы дороже, чем помогает.
     *
     * Колода живёт в библиотеке и переживает закрытие приложения. Подсветка на
     * странице обновляется тем же кадром: читатель должен видеть свой словарь
     * на полосе, а не после перезахода.
     */
    fun toggleWord() {
        val card = _state.value.card ?: return
        val lemma = card.analysis.lemma
        val id = bookId

        if (card.saved) {
            id?.let { library.removeWord(it, lemma) }
            _state.update {
                it.copy(
                    savedLemmas = it.savedLemmas - lemma,
                    card = card.copy(saved = false),
                )
            }
        } else {
            id?.let {
                library.saveWord(
                    bookId = it,
                    surface = card.analysis.surface,
                    lemma = lemma,
                    translation = (card.translation as? TranslationState.Ready)?.word.orEmpty(),
                    context = card.context,
                    pos = card.analysis.primaryPos.orEmpty(),
                    cefr = card.analysis.cefr,
                )
            }
            _state.update {
                it.copy(
                    savedLemmas = it.savedLemmas + lemma,
                    card = card.copy(saved = true),
                )
            }
        }
    }

    /**
     * Кладёт в колоду всё предложение.
     *
     * Фраза сохраняется вместе со своим переводом — тем самым, что уже пришёл
     * с сервера для контекста. Без перевода конструктор фраз показывать нечего:
     * задание в нём начинается с русской строки, и собрать английскую «по
     * памяти о том, что там было» нельзя.
     *
     * Поэтому кнопка и не предлагается, пока перевод предложения не приехал, —
     * см. [WordCardState.translation].
     */
    fun savePhrase() {
        val card = _state.value.card ?: return
        val id = bookId ?: return
        val translation = (card.translation as? TranslationState.Ready)?.sentence.orEmpty()
        if (translation.isBlank()) return

        library.savePhrase(bookId = id, sentence = card.context, translation = translation)
        _state.update { it.copy(card = card.copy(phraseSaved = true)) }
    }

    /**
     * Запоминает, докуда читатель долистал главу.
     *
     * Зовётся при прокрутке, то есть часто, и потому не пишет ничего, пока
     * доля не изменилась заметно. Без этого порога библиотека переписывалась
     * бы на каждый кадр прокрутки — сотню раз в секунду ради значения,
     * которое всё равно меняется медленно.
     */
    fun rememberPlace(withinChapter: Float) {
        val id = bookId ?: return
        val previous = lastReportedPlace
        if (previous != null && kotlin.math.abs(previous - withinChapter) < 0.02f) return
        lastReportedPlace = withinChapter

        // Запись библиотеки — это сериализация всего списка книг и карточек и
        // поход на диск. Во время быстрой прокрутки доля меняется десятки раз
        // в секунду, и запись на каждое изменение подвешивала бы палец на
        // ровном месте. Поэтому не чаще раза в три секунды, а отложенное
        // значение дописывается при закрытии книги и при смене главы.
        val now = clock()
        if (now - lastWriteAt < WRITE_EVERY) {
            pendingPlace = withinChapter
            return
        }
        lastWriteAt = now
        pendingPlace = null
        library.rememberProgress(id, _state.value.chapterIndex, withinChapter)
    }

    /** Дописывает отложенную долю главы: при смене главы и при закрытии книги. */
    private fun flushPlace() {
        val id = bookId ?: return
        val place = pendingPlace ?: return
        pendingPlace = null
        lastWriteAt = clock()
        library.rememberProgress(id, _state.value.chapterIndex, place)
    }

    /** Закрывает книгу, не закрывая экран, — перед открытием следующей. */
    fun closeCurrent() {
        flushPlace()
        deckJob?.cancel()
        deckJob = null
        handle?.let(core::closeBook)
        handle = null
        bookId = null
        lastReportedPlace = null
        pendingPlace = null
    }

    private companion object {
        /** Как редко место в книге доходит до диска. */
        const val WRITE_EVERY = 3_000L
    }

    override fun onCleared() {
        // Ядро держит файл книги, пока номер не закрыт.
        closeCurrent()
        super.onCleared()
    }
}

/** Переводит главу из ядра в блоки экрана, разбирая текст на токены. */
private fun Chapter.toReaderBlocks(core: WolfyCore): List<ReaderBlock> =
    blocks.map { block ->
        ReaderBlock(
            kind = block.kind,
            text = block.text.orEmpty(),
            level = block.level,
            // Разбираем только то, по чему можно тапнуть: у разделителя и
            // картинки текста нет, и звать ядро ради них незачем.
            parsed = block.text?.takeIf { it.isNotBlank() }?.let(core::tokenize),
            imagePath = block.path,
            alt = block.alt,
        )
    }

/**
 * Переводит подготовленную главу (один переход) в блоки экрана.
 *
 * Токены компактные (без текста) — текст режется по смещениям UTF-16.
 * Построение блоков повторяет `Chapter::plain_text` — пустая строка между
 * текстовыми блоками, и гарантирует те же индексы, что и прямая токенизация.
 */
private fun PreparedChapter.toReaderBlocks(): List<ReaderBlock> {
    val plain = plainText()
    // Глобальные токены с текстом, нарезанным из plain (UTF-16 совместимо)
    val globalTokens = tokens.map { c ->
        Token(
            kind = c.kind,
            start = c.start,
            end = c.end,
            text = plain.substring(c.start, c.end),
        )
    }
    var at = 0
    var firstText = true
    val out = mutableListOf<ReaderBlock>()
    for (block in blocks) {
        val text = block.text.orEmpty()
        val isText = !block.text.isNullOrBlank() && block.kind != "image" && block.kind != "divider"
        if (!isText) {
            out.add(
                ReaderBlock(
                    kind = block.kind,
                    text = text,
                    level = block.level,
                    parsed = null,
                    imagePath = block.path,
                    alt = block.alt,
                ),
            )
            continue
        }
        if (!firstText) at += 2
        firstText = false
        val blockStart = at
        val blockEnd = at + text.length
        // Токены, принадлежащие блоку (фильтруем по глобальному start)
        val indices = globalTokens.indices.filter { i ->
            val s = globalTokens[i].start
            s >= blockStart && s < blockEnd
        }
        val localTokens = indices.map { gi ->
            val g = globalTokens[gi]
            Token(
                kind = g.kind,
                start = g.start - blockStart,
                end = g.end - blockStart,
                text = g.text,
            )
        }
        // Предложения блока — те, чей start внутри блока
        val localSentences = sentences
            .filter { it.start >= blockStart && it.start < blockEnd }
            .map { s ->
                // Мапим token indexes
                val localFirst = indices.count { it < s.firstToken }
                val localLast = indices.count { it < s.lastToken }
                Sentence(
                    start = s.start - blockStart,
                    end = s.end - blockStart,
                    firstToken = localFirst,
                    lastToken = localLast,
                    text = plain.substring(s.start, s.end),
                )
            }
        val parsed = if (localTokens.isEmpty() && localSentences.isEmpty()) null else ParsedText(
            tokens = localTokens,
            sentences = localSentences,
        )
        out.add(
            ReaderBlock(
                kind = block.kind,
                text = text,
                level = block.level,
                parsed = parsed,
                imagePath = block.path,
                alt = block.alt,
            ),
        )
        at = blockEnd
    }
    return out
}
