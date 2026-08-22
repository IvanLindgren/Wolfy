package com.wolfy.ui.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfy.data.TranslateResult
import com.wolfy.data.WolfyApi
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
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /** Номер открытой книги в ядре. Пока он есть, ядро держит её файл. */
    private var handle: Long? = null

    /** Запрос перевода для текущей карточки — новый тап отменяет предыдущий. */
    private var translationJob: Job? = null

    /** Открывает книгу и показывает первую главу. */
    fun open(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val opened = withContext(Dispatchers.Default) { core.openBook(path) }
                handle = opened.handle
                _state.update {
                    it.copy(
                        bookTitle = opened.info.title ?: "Без названия",
                        chapterCount = opened.info.chapters.size,
                    )
                }
                loadChapter(0)
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

        _state.update {
            it.copy(
                card = WordCardState(
                    token = token,
                    analysis = analysis,
                    context = context,
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
     * Пока только локально: синхронизация с сервером появится вместе с
     * остальной колодой. Важно, что подсветка на странице обновляется сразу —
     * читатель должен видеть свой словарь на полосе.
     */
    fun saveWord() {
        val card = _state.value.card ?: return
        _state.update {
            it.copy(
                savedLemmas = it.savedLemmas + card.analysis.lemma,
                card = card.copy(saved = true),
            )
        }
    }

    override fun onCleared() {
        // Ядро держит файл книги, пока номер не закрыт.
        handle?.let(core::closeBook)
        handle = null
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
