package com.wolfy.ui.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.TranslateResult
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.ffi.Chapter
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.ParsedText
import com.wolfy.ffi.Token
import com.wolfy.ffi.WolfyCore
import com.wolfy.ui.card.TranslationState
import com.wolfy.ui.card.WordCardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val chapterCount: Int = 0,
    /** Блоки главы вместе с разбором каждого текстового блока. */
    val blocks: List<ReaderBlock> = emptyList(),
    /** Начальные формы слов, уже сохранённых в колоду этой книги. */
    val savedLemmas: Set<String> = emptySet(),
    val card: WordCardState? = null,
    val error: String? = null,
) {
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
) : ViewModel() {

    /** Книга, открытая сейчас. По ней читалка отчитывается библиотеке. */
    private var bookId: String? = null

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /** Номер открытой книги в ядре. Пока он есть, ядро держит её файл. */
    private var handle: Long? = null

    /** Запрос перевода для текущей карточки — новый тап отменяет предыдущий. */
    private var translationJob: Job? = null

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

        viewModelScope.launch {
            _state.update {
                ReaderState(loading = true, savedLemmas = book.deck.toSet())
            }
            try {
                val opened = withContext(Dispatchers.Default) { core.openBook(book.path) }
                handle = opened.handle
                val title = opened.info.title?.takeIf { it.isNotBlank() } ?: book.title
                _state.update {
                    it.copy(
                        bookTitle = title,
                        chapterCount = opened.info.chapters.size,
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
        viewModelScope.launch {
            _state.update { it.copy(loading = true, card = null) }
            try {
                val (chapter, blocks) = withContext(Dispatchers.Default) {
                    val chapter = core.readChapter(handle, index)
                    chapter to chapter.toReaderBlocks(core)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        chapterIndex = index,
                        chapterTitle = chapter.title.orEmpty(),
                        blocks = blocks,
                        error = null,
                    )
                }
                // Место в книге запоминается сразу, а не при выходе: приложение
                // на телефоне закрывают свайпом, и «сохраню потом» означает
                // «не сохраню».
                bookId?.let { library.rememberProgress(it, index, 0f) }
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
     * Карточка открывается тем же кадром: разбор считается локально и занимает
     * микросекунды. Перевод уходит запросом и приезжает в уже открытую
     * карточку — ждать сеть, чтобы показать слово, нельзя.
     */
    fun onWordTap(token: Token, parsed: ParsedText) {
        val context = parsed.sentenceAt(token.start)?.text ?: token.text
        val analysis = core.analyzeWord(token.text)
        // Грамматика считается здесь же, а не отдельным запросом: она про то
        // же предложение и стоит доли миллисекунды. Тянуть её вторым шагом
        // значило бы показать карточку, которая потом дёрнется.
        val grammar = core.explain(context)

        _state.update {
            it.copy(
                card = WordCardState(
                    token = token,
                    analysis = analysis,
                    context = context,
                    grammar = grammar,
                    translation = TranslationState.Loading,
                    saved = analysis.lemma in it.savedLemmas,
                ),
            )
        }

        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            val result = api.translate(context)
            // Пока ходили в сеть, читатель мог закрыть карточку или нажать по
            // другому слову — тогда ответ уже не про то, что на экране.
            _state.update { current ->
                val card = current.card ?: return@update current
                if (card.token.start != token.start) return@update current

                current.copy(
                    card = card.copy(
                        translation = when (result) {
                            is TranslateResult.Ready ->
                                TranslationState.Ready(result.text, context)
                            is TranslateResult.Failed ->
                                TranslationState.Failed(result.message)
                        },
                    ),
                )
            }
        }
    }

    fun dismissCard() {
        translationJob?.cancel()
        _state.update { it.copy(card = null) }
    }

    /**
     * Кладёт слово в колоду книги.
     *
     * Колода живёт в библиотеке и переживает закрытие приложения. Подсветка на
     * странице обновляется тем же кадром: читатель должен видеть свой словарь
     * на полосе, а не после перезахода.
     */
    fun saveWord() {
        val card = _state.value.card ?: return
        bookId?.let { library.saveWord(it, card.analysis.lemma) }
        _state.update {
            it.copy(
                savedLemmas = it.savedLemmas + card.analysis.lemma,
                card = card.copy(saved = true),
            )
        }
    }

    /** Закрывает книгу, не закрывая экран, — перед открытием следующей. */
    fun closeCurrent() {
        handle?.let(core::closeBook)
        handle = null
        bookId = null
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
