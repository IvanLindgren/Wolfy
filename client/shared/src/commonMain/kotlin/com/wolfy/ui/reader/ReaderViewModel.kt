package com.wolfy.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.TranslateResult
import com.wolfy.data.WolfyApi
import com.wolfy.data.AiRecap
import com.wolfy.data.AiRecapResult
import com.wolfy.data.AiPhraseResult
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.data.dictionary.DictionaryManager
import com.wolfy.ffi.Chapter
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.ReadingSegment
import com.wolfy.ffi.readableText
import com.wolfy.platform.refreshBookNudge
import com.wolfy.platform.decodeImage
import com.wolfy.ffi.PreparedChapter
import com.wolfy.ffi.Sentence
import com.wolfy.ffi.Token
import com.wolfy.ffi.WolfyCore
import com.wolfy.ui.card.TranslationState
import com.wolfy.ui.card.DefinitionState
import com.wolfy.ui.card.WordCardState
import com.wolfy.ui.card.BetaPhraseState
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /**
     * Отрезок чтения: докуда читаем сейчас.
     *
     * Границу считает ядро — она обязана совпадать с браузером, иначе одна и
     * та же закладка дала бы на двух устройствах разные отрезки. `null` —
     * отрезки выключены или ядро их не умеет.
     */
    val segment: ReadingSegment? = null,
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
    val recap: StoryRecapState = StoryRecapState.Idle,
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
    /** Исходник MathML/TeX; текст уже содержит readable fallback. */
    val source: String? = null,
    /** Строки таблицы для сохранения её структуры в UI. */
    val rows: List<List<String>>? = null,
    /**
     * Докуда набирать слово полужирным: по числу на токен блока.
     *
     * Список, а не `IntArray`: массив сравнивается по ссылке, и блок с теми
     * же якорями считался бы изменившимся при каждой перерисовке — а `@Immutable`
     * обещает Compose ровно обратное.
     *
     * Пустой список — выделение выключено или ядро его не умеет.
     */
    val anchors: List<Int> = emptyList(),
    /**
     * Номер первого токена блока в нумерации всей главы.
     *
     * Токены внутри блока пересчитаны от его начала — так тап по слову
     * попадает в свой блок. Но отрезок чтения считает ядро по главе целиком,
     * и ему нужен именно сквозной номер.
     *
     * `-1` — блок без текста: у разделителя и картинки токенов нет.
     */
    val firstToken: Int = -1,
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

    /**
     * Номер сессии читалки. Нативное чтение не всегда успевает отмениться
     * мгновенно, поэтому одного `Job.cancel()` недостаточно: запоздавшему
     * результату запрещено публиковаться или закрывать новую книгу.
     */
    private var generation = 0L

    /** Отдельный номер запроса карточки: один и тот же токен могут нажать дважды. */
    private var cardGeneration = 0L

    private var openJob: Job? = null
    private var chapterJob: Job? = null
    private var analysisJob: Job? = null
    private var anchorJob: Job? = null
    private var segmentJob: Job? = null
    /** Отложенная запись места: тяжёлая сессия библиотеки не трогает Main. */
    private var progressJob: Job? = null
    private val progressMutex = Mutex()

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
     * Прогресс прокрутки намеренно живёт отдельно от [ReaderState]: обновлять
     * тяжёлый список блоков на каждом scroll frame означало бы пересобирать
     * всю страницу. Верхняя полоса подписывается только на это маленькое
     * состояние.
     */
    private val _withinChapterProgress = MutableStateFlow(0f)
    val withinChapterProgress: StateFlow<Float> = _withinChapterProgress.asStateFlow()

    /** Готовые картинки текущей книги. Пустой map не держит главу в памяти. */
    private val _images = MutableStateFlow<Map<String, ImageBitmap?>>(emptyMap())
    val images: StateFlow<Map<String, ImageBitmap?>> = _images.asStateFlow()
    private val imageCache = mutableMapOf<String, CachedImage>()
    private val imageOrder = mutableListOf<String>()
    private val imageJobs = mutableMapOf<String, Job>()
    private var imageCacheBytes = 0L

    private data class CachedImage(val bitmap: ImageBitmap?, val bytes: Long)

    private fun owns(session: Long, id: String, expectedHandle: Long? = null): Boolean =
        session == generation && bookId == id && (expectedHandle == null || handle == expectedHandle)

    private fun ownsCard(session: Long, id: String, request: Long, block: Int, token: Token): Boolean {
        val current = _state.value
        val card = current.card ?: return false
        return owns(session, id) &&
            cardGeneration == request &&
            current.selectedBlock == block &&
            card.token.start == token.start &&
            card.token.end == token.end
    }

    /**
     * Подгружает ровно видимую иллюстрацию. Ресурс и декодирование идут вне
     * Main, а небольшой LRU не позволяет длинной иллюстрированной книге
     * удержать все растрированные страницы разом.
     */
    fun loadImage(path: String) {
        if (path.isBlank()) return
        imageCache[path]?.let {
            touchImage(path)
            return
        }
        if (imageJobs.containsKey(path)) return
        val id = bookId ?: return
        val openedHandle = handle ?: return
        val session = generation
        imageJobs[path] = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { core.bookResource(openedHandle, path) }
                    .getOrNull()
                    ?.let(::decodeImage)
            }
            imageJobs.remove(path)
            if (!owns(session, id, openedHandle)) return@launch
            rememberImage(path, bitmap)
        }
    }

    private fun touchImage(path: String) {
        imageOrder.remove(path)
        imageOrder.add(path)
    }

    private fun rememberImage(path: String, bitmap: ImageBitmap?) {
        imageCache.remove(path)?.let { imageCacheBytes -= it.bytes }
        touchImage(path)
        // После безопасной platform-decode считаем уже готовый растр. Это
        // реальная память, а не размер сжатого JPEG в ZIP.
        val bytes = bitmap?.let { it.width.toLong() * it.height.toLong() * 4L } ?: 0L
        // Один растр не имеет права пробить весь бюджет LRU: лучше честно
        // оставить caption, чем удержать 100+ MiB на одном развороте.
        val cached = if (bytes > IMAGE_CACHE_BYTES) CachedImage(null, 0L) else CachedImage(bitmap, bytes)
        imageCache[path] = cached
        imageCacheBytes += cached.bytes
        while (imageCacheBytes > IMAGE_CACHE_BYTES && imageOrder.size > 1) {
            val evicted = imageOrder.removeAt(0)
            imageCache.remove(evicted)?.let { imageCacheBytes -= it.bytes }
        }
        _images.value = imageCache.mapValues { it.value.bitmap }
    }

    private fun clearImages() {
        imageJobs.values.forEach(Job::cancel)
        imageJobs.clear()
        imageCache.clear()
        imageOrder.clear()
        imageCacheBytes = 0L
        _images.value = emptyMap()
    }

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
        val session = generation
        _withinChapterProgress.value = book.progress.withinChapter.coerceIn(0f, 1f)

        _state.value = ReaderState(
            loading = true,
            savedLemmas = library.deck(book.id).map { it.lemma }.toSet(),
            startAt = book.progress.withinChapter,
        )

        // Колода читается из библиотеки подпиской, а не копией: слово можно
        // убрать и на экране повторений, и подсветка на странице обязана
        // погаснуть вместе с ним.
        deckJob = viewModelScope.launch {
            library.state.collect { current ->
                val deck = current.deck(book.id).map { it.lemma }.toSet()
                if (owns(session, book.id)) {
                    _state.update { it.copy(savedLemmas = deck) }
                }
            }
        }

        openJob = viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.Default) { core.openBook(book.path) }
                if (!owns(session, book.id)) {
                    // `openBook` успел создать именно свой handle уже после
                    // отмены: освобождаем его, не трогая handle новой сессии.
                    withContext(Dispatchers.Default) { core.closeBook(opened.handle) }
                    return@launch
                }
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
                withContext(Dispatchers.Default) {
                    library.describe(
                        id = book.id,
                        title = title,
                        author = opened.info.author,
                        chapters = opened.info.chapters.size,
                    )
                }
                loadChapter(
                    index = book.progress.chapter.coerceIn(0, (opened.info.chapters.size - 1).coerceAtLeast(0)),
                    session = session,
                    initialPlace = book.progress.withinChapter,
                )
            } catch (e: CoreException) {
                if (owns(session, book.id)) {
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "книга не открылась")
                    }
                }
            }
        }
    }

    /*
     * Выделять ли основу слова.
     *
     * Флагом, а не чтением настроек изнутри: настройки — дело оболочки, а
     * читалке нужно знать только «считать якоря или нет». Пересчёт идёт по
     * уже прочитанной главе, поэтому переключение не перечитывает книгу.
     */
    private var emphasizeStems = false

    /*
     * Размер отрезка чтения в словах. Ноль — отрезки выключены.
     *
     * Хранится здесь, а не читается из настроек: читалке нужно знать только
     * «какой длины подход», а откуда это число взялось — дело оболочки.
     */
    private var segmentWords = 0

    /**
     * Задаёт длину отрезка и пересчитывает текущий.
     *
     * Ноль убирает отрезок совсем: у главы остаётся только её собственный
     * конец, как было до всякой помощи вниманию.
     */
    fun setSegmentWords(words: Int) {
        if (segmentWords == words) return
        segmentWords = words
        if (words <= 0) {
            _state.update { it.copy(segment = null) }
        } else {
            planSegment(from = _state.value.segment?.start ?: 0)
        }
    }

    /**
     * Просит ядро назначить отрезок, начиная с токена `from`.
     *
     * Пересчёт только при смене главы, при смене длины и по кнопке «ещё
     * один»: отрезок, который сам ползёт вслед за читателем, — это снова
     * книга без видимого конца.
     */
    fun planSegment(from: Int) {
        val handle = handle ?: return
        if (segmentWords <= 0) return
        val id = bookId ?: return
        val session = generation
        val index = _state.value.chapterIndex
        val words = segmentWords
        segmentJob?.cancel()
        segmentJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                runCatching { core.chapterSegment(handle, index, from, words) }.getOrNull()
            }
            // Отрезок мерили по главе `index`; если читатель успел уйти в
            // другую, он описывает уже не тот текст.
            if (owns(session, id, handle) && segmentWords == words) {
                _state.update { if (it.chapterIndex == index) it.copy(segment = found) else it }
            }
        }
    }

    fun setEmphasizeStems(on: Boolean) {
        if (emphasizeStems == on) return
        emphasizeStems = on
        val blocks = _state.value.blocks
        if (blocks.isEmpty()) return
        val id = bookId ?: return
        val session = generation
        val openedHandle = handle ?: return
        val index = _state.value.chapterIndex
        anchorJob?.cancel()
        anchorJob = viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { blocks.withAnchors(core, on) }
            // Абзацы взяты из главы `index`, а якоря считались по одному
            // вызову ядра на абзац — за это время можно успеть перелистнуть.
            // Записать их обратно вслепую значит показать текст прошлой главы
            // под заголовком нынешней.
            _state.update {
                if (owns(session, id, openedHandle) && it.chapterIndex == index && emphasizeStems == on) {
                    it.copy(blocks = next)
                } else {
                    it
                }
            }
        }
    }

    /** Читает главу и разбирает её текст. */
    fun loadChapter(index: Int) = loadChapter(index, generation, initialPlace = null)

    private fun loadChapter(index: Int, session: Long, initialPlace: Float?) {
        val handle = handle ?: return
        val id = bookId ?: return
        if (!owns(session, id, handle)) return
        flushPlace()
        invalidateCard()
        chapterJob?.cancel()
        anchorJob?.cancel()
        segmentJob?.cancel()
        chapterJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, card = null) }
            try {
                val (title, blocks) = withContext(Dispatchers.Default) {
                    // Один тяжёлый переход вместо N токенизаций по блокам (§15)
                    val (title, blocks) = try {
                        val prepared = core.preparedChapter(handle, index)
                        prepared.title to prepared.toReaderBlocks()
                    } catch (_: Throwable) {
                        val chapter = core.readChapter(handle, index)
                        chapter.title to chapter.toReaderBlocks(core)
                    }
                    title to blocks.withAnchors(core, emphasizeStems)
                }
                if (!owns(session, id, handle)) return@launch
                val restore = initialPlace?.coerceIn(0f, 1f) ?: 0f
                _state.update {
                    it.copy(
                        loading = false,
                        chapterIndex = index,
                        chapterTitle = title.orEmpty(),
                        blocks = blocks,
                        // Новая глава — новый отрезок: старый мерил чужой текст.
                        segment = null,
                        startAt = restore,
                        error = null,
                    )
                }
                planSegment(from = 0)
                // Место в книге запоминается сразу, а не при выходе: приложение
                // на телефоне закрывают свайпом, и «сохраню потом» означает
                // «не сохраню».
                // Переход к другой главе начинает её с начала: место внутри
                // главы имеет смысл только для той главы, где его записали.
                lastReportedPlace = restore
                _withinChapterProgress.value = restore
                library.rememberProgress(id, index, restore)
            } catch (e: CoreException) {
                if (owns(session, id, handle)) {
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "глава не прочиталась")
                    }
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
        val id = bookId ?: return
        val session = generation
        val request = ++cardGeneration
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
        analysisJob?.cancel()
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
                if (!ownsCard(session, id, request, block, token)) return@update current
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
        analysisJob = viewModelScope.launch {
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
                        if (!ownsCard(session, id, request, block, token)) return@update current
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
                    if (!ownsCard(session, id, request, block, token)) return@launch
                    definitionJob?.cancel()
                    definitionJob = viewModelScope.launch {
                        val entry = dictionary.define(inspected.word.lemma)
                        _state.update { current ->
                            val card = current.card ?: return@update current
                            if (!ownsCard(session, id, request, block, token)) return@update current
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
                        if (!ownsCard(session, id, request, block, token)) return@update current
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
                    if (!ownsCard(session, id, request, block, token)) return@launch
                    definitionJob?.cancel()
                    definitionJob = viewModelScope.launch {
                        val entry = dictionary.define(analysis.lemma)
                        _state.update { current ->
                            val card = current.card ?: return@update current
                            if (!ownsCard(session, id, request, block, token)) return@update current
                            current.copy(card = card.copy(definition = entry?.let(DefinitionState::Ready) ?: DefinitionState.Missing))
                        }
                    }
                }
            } catch (_: Exception) {
                // Тихо — карточка уже видна с placeholder, сеть продолжает работу.
            }
        }
    }

    /** Beta: Gemini объясняет только выбранное предложение, не всю книгу. */
    fun explainCardPhrase() {
        val id = bookId ?: return
        val session = generation
        val card = _state.value.card ?: return
        if (card.betaExplanation is BetaPhraseState.Loading) return
        _state.update { it.copy(card = it.card?.copy(betaExplanation = BetaPhraseState.Loading)) }
        viewModelScope.launch {
            val result = api.explainPhrase(card.context, card.context)
            _state.update { current ->
                if (!owns(session, id) || current.card?.context != card.context) current
                else current.copy(card = current.card.copy(betaExplanation = when (result) {
                    is AiPhraseResult.Ready -> BetaPhraseState.Ready(result.value)
                    is AiPhraseResult.Failed -> BetaPhraseState.Failed(result.message)
                }))
            }
        }
    }

    /** Beta: до десяти последних экранов, ограниченных 18 тысячами знаков. */
    fun recapRecentPages() {
        val id = bookId ?: return
        val opened = handle ?: return
        val session = generation
        if (_state.value.recap is StoryRecapState.Loading) return
        _state.update { it.copy(recap = StoryRecapState.Loading) }
        viewModelScope.launch {
            val snapshot = _state.value
            val excerpt = withContext(Dispatchers.Default) {
                val pieces = mutableListOf<String>()
                var left = RECAP_CHARS
                var index = snapshot.chapterIndex
                while (index >= 0 && left > 0) {
                    val text = if (index == snapshot.chapterIndex) snapshot.blocks.joinToString("\n\n") { it.text }
                    else runCatching { core.readChapter(opened, index).plainText() }.getOrDefault("")
                    if (text.isNotBlank()) { pieces += text.takeLast(left); left -= text.length }
                    index--
                }
                pieces.asReversed().joinToString("\n\n")
            }
            val result = api.recap(snapshot.bookTitle, excerpt)
            _state.update { current ->
                if (!owns(session, id)) current
                else current.copy(recap = when (result) {
                    is AiRecapResult.Ready -> StoryRecapState.Ready(result.value)
                    is AiRecapResult.Failed -> StoryRecapState.Failed(result.message)
                })
            }
        }
    }

    fun dismissRecap() { _state.update { it.copy(recap = StoryRecapState.Idle) } }

    fun dismissCard() {
        invalidateCard()
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
        val place = withinChapter.coerceIn(0f, 1f)
        _withinChapterProgress.value = place
        val previous = lastReportedPlace
        if (previous != null && kotlin.math.abs(previous - place) < 0.02f) return
        lastReportedPlace = place

        // Запись библиотеки — это сериализация всего списка книг и карточек и
        // поход на диск. Во время быстрой прокрутки доля меняется десятки раз
        // в секунду, и запись на каждое изменение подвешивала бы палец на
        // ровном месте. Поэтому не чаще раза в три секунды, а отложенное
        // значение дописывается при закрытии книги и при смене главы.
        val now = clock()
        if (now - lastWriteAt < WRITE_EVERY) {
            pendingPlace = place
            return
        }
        lastWriteAt = now
        pendingPlace = null
        writeProgress(id, _state.value.chapterIndex, place)
    }

    /** Дописывает отложенную долю главы: при смене главы и при закрытии книги. */
    private fun flushPlace() {
        val id = bookId ?: return
        val place = pendingPlace ?: return
        pendingPlace = null
        lastWriteAt = clock()
        writeProgress(id, _state.value.chapterIndex, place)
    }

    private fun writeProgress(id: String, chapter: Int, place: Float) {
        // CoreSession.run может сериализовать всю библиотеку. Даже раз в три
        // секунды это недопустимо на scroll callback. На закрытии последняя
        // отложенная доля добавляется в эту же последовательную очередь.
        progressJob = viewModelScope.launch(Dispatchers.Default) {
            // Порядок важнее отмены: старый progress не должен завершиться
            // после более нового и вернуть книгу назад на пару процентов.
            progressMutex.withLock { library.rememberProgress(id, chapter, place) }
        }
    }

    /**
     * Завершает уже поставленную в очередь запись перед закрытием Session.
     *
     * Это только путь завершения процесса, не scroll hot path. Ограничение
     * времени не даёт зависшему диску повесить выход из приложения навечно.
     */
    fun flushProgressBlocking(timeoutMs: Long = 1_500L) {
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                progressMutex.withLock { }
            }
        }
    }

    /** Закрывает книгу, не закрывая экран, — перед открытием следующей. */
    fun closeCurrent() {
        // Сначала инвалидируем все результаты. Отмена coroutine не может
        // прервать уже начатый JNA/сетевой вызов, а проверка generation может.
        generation += 1
        invalidateCard()
        openJob?.cancel()
        chapterJob?.cancel()
        anchorJob?.cancel()
        segmentJob?.cancel()
        openJob = null
        chapterJob = null
        anchorJob = null
        segmentJob = null
        flushPlace()
        deckJob?.cancel()
        deckJob = null
        val closingHandle = handle
        handle = null
        bookId = null
        lastReportedPlace = null
        pendingPlace = null
        _withinChapterProgress.value = 0f
        clearImages()
        // Закрываем ровно тот handle, который принадлежал предыдущей
        // сессии; запоздавший `open` закрывает свой handle выше сам.
        closingHandle?.let(core::closeBook)
        // Место в книге изменилось — виджет на рабочем столе должен узнать об
        // этом сейчас, а не через полчаса, когда система соберётся сама.
        // Именно здесь, а не в flushPlace: тот зовётся раз в три секунды на
        // всём протяжении чтения, и будить им виджет значило бы жечь батарею
        // ради экрана, которого в этот момент никто не видит.
        refreshBookNudge()
    }

    /** Отменяет всю цепочку карточки и делает её результаты устаревшими. */
    private fun invalidateCard() {
        cardGeneration += 1
        analysisJob?.cancel()
        translationJob?.cancel()
        definitionJob?.cancel()
        analysisJob = null
        translationJob = null
        definitionJob = null
    }

    private companion object {
		const val RECAP_CHARS = 18_000
        /** Как редко место в книге доходит до диска. */
        const val WRITE_EVERY = 3_000L
        /** Не больше 32 MiB готовых растров на открытую книгу. */
        const val IMAGE_CACHE_BYTES = 32L * 1024L * 1024L
    }

    override fun onCleared() {
        // Ядро держит файл книги, пока номер не закрыт.
        closeCurrent()
        super.onCleared()
    }
}

/**
 * Досчитывает якоря полужирной основы для блоков главы.
 *
 * Один вызов ядра на абзац, а не на слово: якоря считаются по тексту целиком
 * и приходят с той же нумерацией, что и токены абзаца.
 *
 * Считать нечего — возвращаем те же блоки, а не копии: лишняя копия списка
 * заставила бы Compose перерисовать всю главу на пустом месте.
 */
private fun List<ReaderBlock>.withAnchors(core: WolfyCore, on: Boolean): List<ReaderBlock> {
    if (!on) return if (none { it.anchors.isNotEmpty() }) this else map { it.copy(anchors = emptyList()) }
    return map { block ->
        if (block.parsed == null || block.text.isBlank()) {
            block
        } else {
            block.copy(anchors = core.textAnchors(block.text).asList())
        }
    }
}

/** Переводит главу из ядра в блоки экрана, разбирая текст на токены. */
private fun Chapter.toReaderBlocks(core: WolfyCore): List<ReaderBlock> {
    // Сквозной номер токена. Считается здесь, а не берётся из ядра, потому
    // что этот путь разбирает главу по абзацам: нумерации главы целиком у
    // него нет, и собрать её можно только накоплением.
    var at = 0
    return blocks.map { block ->
        // Разбираем только то, по чему можно тапнуть: у разделителя и
        // картинки текста нет, и звать ядро ради них незачем.
        val text = block.readableText().orEmpty()
        val parsed = text.takeIf { it.isNotBlank() }?.let(core::tokenize)
        val firstToken = if (parsed == null || parsed.tokens.isEmpty()) -1 else at
        if (parsed != null) at += parsed.tokens.size
        ReaderBlock(
            kind = block.kind,
            text = text,
            level = block.level,
            parsed = parsed,
            imagePath = block.path,
            alt = block.alt,
            source = block.source,
            rows = block.rows,
            firstToken = firstToken,
        )
    }
}

/**
 * Переводит подготовленную главу (один переход) в блоки экрана.
 *
 * Токены компактные (без текста) — текст режется по смещениям UTF-16.
 * Построение блоков повторяет `Chapter::plain_text` — пустая строка между
 * текстовыми блоками, и гарантирует те же индексы, что и прямая токенизация.
 */
internal fun PreparedChapter.toReaderBlocks(): List<ReaderBlock> {
    val plain = plainText()
    // Курсоры проходят компактные массивы один раз. Прежняя версия для
    // каждого блока заново фильтровала все токены и предложения, а затем ещё
    // считала префикс токенов для каждого предложения: большая глава
    // превращалась в O(blocks * tokens). Смещения JVM строк — UTF-16, как и
    // у FFI, поэтому нарезка безопасна и для emoji/non-BMP.
    var tokenCursor = 0
    var sentenceCursor = 0
    var plainAt = 0
    var hasTextBefore = false
    val out = ArrayList<ReaderBlock>(blocks.size)
    for (block in blocks) {
        val blockText = block.readableText()
        val text = blockText.orEmpty()
        val blockStart: Int
        val blockEnd: Int
        if (blockText != null) {
            if (hasTextBefore) plainAt += 2
            blockStart = plainAt
            blockEnd = blockStart + blockText.length
            plainAt = blockEnd
            hasTextBefore = true
        } else {
            blockStart = plainAt
            blockEnd = plainAt
        }

        // Пропускаем данные до текущего блока. Корректный ответ ядра не даёт
        // пересекающихся токенов, но `end <= start` делает путь устойчивым к
        // пустому/старому ответу без квадратичного поиска.
        while (tokenCursor < tokens.size && tokens[tokenCursor].end <= blockStart) tokenCursor++
        val tokenStart = tokenCursor
        while (tokenCursor < tokens.size && tokens[tokenCursor].start < blockEnd) tokenCursor++
        val tokenEnd = tokenCursor

        while (sentenceCursor < sentences.size && sentences[sentenceCursor].end <= blockStart) sentenceCursor++
        val sentenceStart = sentenceCursor
        while (sentenceCursor < sentences.size && sentences[sentenceCursor].start < blockEnd) sentenceCursor++
        val sentenceEnd = sentenceCursor

        val isText = !blockText.isNullOrBlank() && block.kind != "image" && block.kind != "divider"
        if (!isText) {
            out.add(
                ReaderBlock(
                    kind = block.kind,
                    text = text,
                    level = block.level,
                    parsed = null,
                    imagePath = block.path,
                    alt = block.alt,
                    source = block.source,
                    rows = block.rows,
                ),
            )
            continue
        }

        val localTokens = ArrayList<Token>(tokenEnd - tokenStart)
        for (globalIndex in tokenStart until tokenEnd) {
            val token = tokens[globalIndex]
            // Некорректное пересечение границы не должно ронять книгу.
            if (token.start < blockStart || token.end > blockEnd) continue
            localTokens += Token(
                kind = token.kind,
                start = token.start - blockStart,
                end = token.end - blockStart,
                text = plain.substring(token.start, token.end),
            )
        }

        val localSentences = ArrayList<Sentence>(sentenceEnd - sentenceStart)
        for (globalIndex in sentenceStart until sentenceEnd) {
            val sentence = sentences[globalIndex]
            if (sentence.start < blockStart || sentence.end > blockEnd) continue
            localSentences += Sentence(
                start = sentence.start - blockStart,
                end = sentence.end - blockStart,
                firstToken = (sentence.firstToken - tokenStart).coerceIn(0, localTokens.size),
                lastToken = (sentence.lastToken - tokenStart).coerceIn(0, localTokens.size),
                text = plain.substring(sentence.start, sentence.end),
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
                source = block.source,
                rows = block.rows,
                firstToken = tokenStart.takeIf { tokenEnd > tokenStart } ?: -1,
            ),
        )
    }
    return out
}

@Immutable
sealed interface StoryRecapState {
    data object Idle : StoryRecapState
    data object Loading : StoryRecapState
    data class Ready(val value: AiRecap) : StoryRecapState
    data class Failed(val message: String) : StoryRecapState
}
