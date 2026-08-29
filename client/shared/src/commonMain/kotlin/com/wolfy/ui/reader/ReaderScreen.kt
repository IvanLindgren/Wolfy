package com.wolfy.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.wolfy.widgets.pressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.wolfy.data.companion.takeCodePoints
import androidx.compose.ui.unit.dp
import com.wolfy.ui.card.WordCardSheet
import com.wolfy.ui.nav.shortcuts
import com.wolfy.ui.nav.LocalKeyboard
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.derivedStateOf
import com.wolfy.data.FocusMode
import com.wolfy.platform.KeepScreenAwake
import com.wolfy.platform.CompanionSound
import com.wolfy.platform.playCompanionSound
import com.wolfy.theme.ReadingTheme
import kotlinx.coroutines.delay
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.still
import com.wolfy.widgets.ChapterHeading
import com.wolfy.widgets.DropCapParagraph
import com.wolfy.widgets.NavGlyph
import com.wolfy.widgets.NavIcon
import com.wolfy.widgets.ReaderParagraph
import com.wolfy.widgets.ReaderQuote
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.ui.companion.CompanionFigure

/**
 * Экран чтения.
 *
 * Без состояния: всё, что он умеет, — нарисовать переданное и сообщить о
 * нажатии. Так его можно показать в превью и проверить тестом, не поднимая ни
 * ядра, ни сети.
 */
// BackHandler помечен и как временный, и как устаревший: Compose 1.11 зовёт
// переходить на NavigationEventHandler. Не переходим пока намеренно — замена
// живёт в отдельной библиотеке, которой в сборке ещё нет, и заводить её ради
// одного обращения дороже, чем однажды это обращение переписать.
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun ReaderScreen(
    state: ReaderState,
    withinChapterProgress: Float,
    images: Map<String, ImageBitmap?>,
    onWordTap: (Int, com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
    onDismissCard: () -> Unit,
    onSaveWord: () -> Unit,
    onSavePhrase: () -> Unit,
    onPronounce: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClose: () -> Unit,
    /**
     * Сигнал прокрутки: доля главы плюс стабильный якорь «блок + смещение
     * внутри блока». Якорь переживает смену кегля и устройства — восстанавливает
     * позицию точнее, чем доля от числа блоков.
     */
    onScrolled: (Float, Int, Float) -> Unit,
    onChapter: (Int) -> Unit,
    onOpenRule: (String) -> Unit,
    onExplainPhrase: () -> Unit,
    onRecap: () -> Unit,
    onDismissRecap: () -> Unit,
    /**
     * Выделение завершено: блок, диапазон в границах токенов, текст абзаца и
     * его разбор. По нему открывается карточка сразу на вкладке «Фраза».
     */
    onPhraseSelected: (Int, IntRange, String, com.wolfy.ffi.ParsedText) -> Unit = { _, _, _, _ -> },
    /** Компаньон: профиль, синхронизация правок и действия. null — раздела нет. */
    companionProfile: com.wolfy.data.companion.CompanionProfile? = null,
    companionOnProfileChange: (com.wolfy.data.companion.CompanionProfile) -> Unit = {},
    companionPersona: com.wolfy.data.CompanionPersonaIn = com.wolfy.data.CompanionPersonaIn(),
    companionApi: com.wolfy.data.WolfyApi? = null,
    companionBookId: String = "",
    /**
     * Прочитанное для вопроса компаньону.
     *
     * Отдельно от `pageText`: мнение строится по видимой странице, а вопрос
     * «что уже случилось в книге» — по истории чтения. Раньше в оба уходил
     * один и тот же видимый фрагмент, и на вопрос компаньон честно отвечать
     * не мог.
     */
    companionContext: suspend () -> String = { "" },
    companionOnRecap: () -> Unit = {},
    companionOnEdit: () -> Unit = {},
    onImageVisible: (String) -> Unit,
    theme: ReadingTheme,
    fontScale: Float,
    lineScale: Float,
    onThemeChange: (ReadingTheme) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLineScaleChange: (Float) -> Unit,
    emphasizeStems: Boolean,
    onEmphasizeStems: (Boolean) -> Unit,
    /*
     * Помощь вниманию. Всё выключено по умолчанию и приезжает из настроек,
     * общих с браузером: включив окно на телефоне, читатель ждёт его и там.
     */
    focusMode: FocusMode = FocusMode.Off,
    onFocusModeChange: (FocusMode) -> Unit = {},
    pacerWpm: Int = 0,
    onPacerChange: (Int) -> Unit = {},
    segmentWords: Int = 0,
    reduceMotion: Boolean = false,
    companionSounds: Boolean = true,
    onSegmentWordsChange: (Int) -> Unit = {},
    onNextSegment: (Int) -> Unit = {},
    onStopSegments: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Пока книга открыта, системный таймер не должен гасить экран посреди
    // абзаца. Эффект сам снимает запрет при выходе из читалки.
    KeepScreenAwake()

    val colors = WolfyTheme.colors
    val motion = WolfyTheme.motion
    var contentsOpen by remember { mutableStateOf(false) }
    var readingSettingsOpen by remember { mutableStateOf(false) }

    // Живое выделение фразы: блок и диапазон, подсвечиваемый по ходу жеста.
    val phraseSelection = remember { mutableStateOf<PhraseSelection?>(null) }
    // Десктоп выделяет мышью (двойной клик + протягивание), палец — долгим
    // нажатием. Признак берётся у клавиатуры: она есть на десктопе.
    val selectViaMouse = LocalKeyboard.current

    // Подсветка принадлежит открытой карточке и текущей главе. Раньше она
    // переживала закрытие карточки и на следующей главе могла подсветить
    // случайный диапазон с теми же смещениями.
    LaunchedEffect(state.chapterIndex) { phraseSelection.value = null }
    LaunchedEffect(state.card == null) {
        if (state.card == null) phraseSelection.value = null
    }

    // Прокрутка живёт здесь, а не в теле главы: её же двигают клавиши, а они
    // ловятся на самом верху экрана.
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cardOpen = state.card != null

    /*
     * Ведущая строка.
     *
     * Едет, только пока читатель этого хочет: пауза — состояние по умолчанию,
     * и открытая карточка слова её останавливает. Темп здесь не про скорость,
     * а про то, что решение «читать дальше» больше не нужно принимать на
     * каждой строке.
     */
    var pacing by remember { mutableStateOf(false) }
    var paceSentence by remember(state.chapterIndex) { mutableStateOf(0) }
    LaunchedEffect(state.chapterIndex, pacerWpm) { pacing = false }
    LaunchedEffect(cardOpen) { if (cardOpen) pacing = false }

    // Активный блок — первый видимый: на телефоне указателя нет, и водить
    // окно нечем, кроме самой прокрутки.
    val activeBlock by remember { derivedStateOf { scroll.firstVisibleItemIndex } }

    // Оценка пересчитывается на смене блока, а не на каждом кадре прокрутки.
    val minutesLeft by remember(state.blocks, pacerWpm) {
        derivedStateOf { minutesLeftInChapter(state.blocks, activeBlock, pacerWpm) }
    }
    val activeParsed = state.blocks.getOrNull(activeBlock)?.parsed

    LaunchedEffect(pacing, pacerWpm, activeBlock, paceSentence, activeParsed) {
        if (!pacing || pacerWpm <= 0) return@LaunchedEffect
        val sentences = activeParsed?.sentences.orEmpty()
        val sentence = sentences.getOrNull(paceSentence)
        if (sentence == null) {
            paceSentence = 0
            val next = activeBlock + 1
            if (next < state.blocks.size) {
                scroll.animateScrollToItem(next)
            } else {
                // `size` не является допустимым индексом LazyColumn. На
                // последнем блоке ведущая строка заканчивает ход спокойно,
                // а не падает попыткой прокрутиться за главу.
                pacing = false
            }
            return@LaunchedEffect
        }
        // Время предложения — по числу слов в нём: «Yes.» и придаточное на
        // сорок слов требуют разного времени, и равный шаг сделал бы одно
        // ожиданием, а другое гонкой. Полсекунды снизу — чтобы окно на
        // коротком предложении не моргало быстрее, чем глаз его замечает.
        val words = sentence.text.split(" ").count { it.isNotBlank() }.coerceAtLeast(1)
        delay(maxOf(500L, (words.toLong() * 60_000L) / pacerWpm))
        if (paceSentence + 1 < sentences.size) {
            paceSentence += 1
        } else {
            paceSentence = 0
            val next = activeBlock + 1
            if (next < state.blocks.size) {
                scroll.animateScrollToItem(next)
            } else {
                pacing = false
            }
        }
    }

    // Уход назад разбирает то, что открыто, по одному слою за раз: сначала
    // оглавление, потом карточка, и только потом сама книга. Закрывать всё
    // разом — значит выбрасывать читателя из книги за жест, которым он хотел
    // убрать карточку.
    //
    // Одна лестница на Esc и на системный «назад»: на телефоне и на настольной
    // машине это одно и то же намерение, и разойтись они не должны.
    // Ступеней ровно столько, сколько слоёв умеет открыться поверх страницы.
    // Раньше их было две лестницы в двух BackHandler, и работала только
    // нижняя — та, что регистрируется последней. Панель быстрых настроек в
    // неё не входила, и «назад» при открытой панели закрывал книгу целиком.
    val goBack = {
        when {
            readingSettingsOpen -> readingSettingsOpen = false
            contentsOpen -> contentsOpen = false
            cardOpen -> onDismissCard()
            else -> onClose()
        }
    }
    BackHandler(enabled = true, onBack = goBack)

    Box(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .chapterSwipe(
                enabled = !cardOpen && !contentsOpen && !readingSettingsOpen && !LocalKeyboard.current,
                onPrevious = onPreviousChapter,
                onNext = onNextChapter,
            )
            .shortcuts { event ->
                when {
                    event.key == Key.Escape -> {
                        goBack()
                        true
                    }

                    // При открытой карточке клавиши принадлежат ей: листать
                    // страницу под карточкой читатель не просил.
                    cardOpen -> when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { onSaveWord(); true }
                        Key.S -> { onPronounce(); true }
                        else -> false
                    }

                    event.key == Key.Spacebar ->
                        { scope.launch { scroll.turnPage(forward = !event.isShiftPressed) }; true }
                    event.key == Key.PageDown || event.key == Key.DirectionDown ->
                        { scope.launch { scroll.turnPage(forward = true) }; true }
                    event.key == Key.PageUp || event.key == Key.DirectionUp ->
                        { scope.launch { scroll.turnPage(forward = false) }; true }

                    event.key == Key.DirectionLeft -> { onPreviousChapter(); true }
                    event.key == Key.DirectionRight -> { onNextChapter(); true }

                    event.key == Key.MoveHome -> { scope.launch { scroll.scrollToItem(0) }; true }
                    event.key == Key.MoveEnd -> {
                        scope.launch {
                            scroll.scrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                        }
                        true
                    }

                    else -> false
                }
            },
    ) {
        // Высота верхней зоны меряется отдельно и только у неё: шапка,
        // прогресс, ИИ-кнопки и панель внимания. Если мерить весь столбец,
        // в замер попадает и тело книги — и overlay настроек уезжает на
        // экран вниз.
        // Высота читается на разметке, а не на композиции: `padding(top = …)`
        // требовал значение уже при сборке, и каждый замер шапки перезапускал
        // композицию всего экрана, а панель на первом открытии успевала
        // мигнуть на кадр выше своего места.
        val headerHeightPx = remember { mutableIntStateOf(0) }
        Column(
            Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.onSizeChanged { headerHeightPx.intValue = it.height },
            ) {
                ReaderTopBar(
                    state = state,
                    withinChapterProgress = withinChapterProgress,
                    minutesLeft = minutesLeft,
                    onClose = onClose,
                    onOpenContents = { contentsOpen = true },
                    onOpenSettings = { readingSettingsOpen = !readingSettingsOpen },
                    onRecap = onRecap,
                    companionMode = companionProfile?.readerMode,
                    onCompanionMode = {
                        companionProfile?.let { profile ->
                            val next = when (profile.readerMode) {
                                "off" -> "quiet"
                                "quiet" -> "active"
                                else -> "off"
                            }
                            companionOnProfileChange(profile.copy(readerMode = next))
                        }
                    },
                )
                AttentionBar(
                    state = state,
                    activeBlock = activeBlock,
                    pacing = pacing,
                    pacerWpm = pacerWpm,
                    onPace = { pacing = it },
                    onNextSegment = onNextSegment,
                    onStopSegments = onStopSegments,
                )
            }

            when {
                state.error != null -> Message(state.error)
                state.loading -> Message("Книга открывается…")
                else -> ChapterBody(
                    state = state,
                    scroll = scroll,
                    focusMode = focusMode,
                    activeBlock = activeBlock,
                    activeSentence = if (pacing) paceSentence else 0,
                    onWordTap = onWordTap,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onScrolled = onScrolled,
                    images = images,
                    onImageVisible = onImageVisible,
                    phraseSelectionBlock = phraseSelection.value?.block ?: -1,
                    phraseSelectionRange = phraseSelection.value?.range,
                    selectViaMouse = selectViaMouse,
                    onPhraseSelect = { block, range ->
                        if (phraseSelection.value?.block != block || phraseSelection.value?.range != range) {
                            phraseSelection.value = PhraseSelection(block, range)
                        }
                    },
                    onPhraseCommit = { block, range ->
                        val selectedBlock = state.blocks.getOrNull(block)
                        val parsedForBlock = selectedBlock?.parsed
                        if (parsedForBlock != null && range.first <= range.last) {
                            onPhraseSelected(block, range, selectedBlock.text, parsedForBlock)
                        }
                        // Подсветка остаётся до закрытия карточки: снять её
                        // раньше — значит спрятать то, что читатель взял.
                    },
                    modifier = Modifier
                        .weight(1f)
                        .chapterArrival(state.chapterIndex),
                )
            }
        }

        // Панель настроек — overlay по-прежнему: открытие не сдвигает строку,
        // на которой читатель остановился. Слой под ней ловит касание мимо:
        // тап вне панели закрывает её, как ведут себя системные листы.
        if (readingSettingsOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(Z_OVERLAY_SCRIM)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            readingSettingsOpen = false
                        }
                    },
            )
        }

        AnimatedVisibility(
            visible = readingSettingsOpen,
            enter = fadeIn(motion.paced(motion.quick)),
            exit = fadeOut(motion.paced(motion.instant)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, headerHeightPx.intValue) }
                .zIndex(Z_OVERLAY),
        ) {
            ReaderQuickSettings(
                theme = theme,
                fontScale = fontScale,
                lineScale = lineScale,
                onThemeChange = onThemeChange,
                onFontScaleChange = onFontScaleChange,
                onLineScaleChange = onLineScaleChange,
                emphasizeStems = emphasizeStems,
                onEmphasizeStems = onEmphasizeStems,
                focusMode = focusMode,
                onFocusModeChange = onFocusModeChange,
                pacerWpm = pacerWpm,
                onPacerChange = onPacerChange,
                segmentWords = segmentWords,
                onSegmentWordsChange = onSegmentWordsChange,
            )
        }

        ContentsSheet(
            modifier = Modifier.zIndex(Z_SHEET),
            visible = contentsOpen,
            chapters = state.chapters,
            current = state.chapterIndex,
            onSelect = {
                contentsOpen = false
                onChapter(it)
            },
            onDismiss = { contentsOpen = false },
        )

        WordCardSheet(
            modifier = Modifier.zIndex(Z_SHEET),
            state = state.card,
            onDismiss = onDismissCard,
            onSave = onSaveWord,
            onSavePhrase = onSavePhrase,
            onPronounce = onPronounce,
            onOpenRule = onOpenRule,
            onExplainPhrase = onExplainPhrase,
        )
        if (state.recap !is StoryRecapState.Idle) {
            // Лист снизу: то же место, где карточка слова, — а не случайный
            // угол, из которого он перекрывает текст.
            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).zIndex(Z_SHEET)) {
                StoryRecapSheet(state.recap, companionProfile, companionSounds, onRecap, onDismissRecap)
            }
        }

        // Компаньон: нижний безопасный угол, никакой сети при обычном чтении.
        if (companionProfile != null && companionApi != null) {
            CompanionLayer(
                profile = companionProfile,
                onProfileChange = companionOnProfileChange,
                persona = companionPersona,
                api = companionApi,
                bookId = companionBookId,
                bookTitle = state.bookTitle,
                chapter = state.chapterIndex,
                offset = { (withinChapterProgress * 10_000).toInt() },
                pageText = { visiblePageText(state.blocks, activeBlock) },
                readContext = companionContext,
                suppressed = cardOpen || contentsOpen || readingSettingsOpen || phraseSelection.value != null || state.recap !is StoryRecapState.Idle,
                scrolling = scroll.isScrollInProgress,
                compact = !selectViaMouse,
                soundsEnabled = companionSounds,
                activeBlock = activeBlock,
                chapterKey = state.chapterIndex,
                onRecap = companionOnRecap,
                onEditCompanion = companionOnEdit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // На телефоне нижняя навигация приложения занимает около
                    // 64 dp. Ярлычок живёт над ней и не закрывает вкладки.
                    .padding(bottom = if (selectViaMouse) 32.dp else 84.dp, end = 6.dp)
                    .zIndex(Z_COMPANION),
            )
        }
    }
}

/**
 * Видимый фрагмент для мнения компаньона.
 *
 * Берём активный блок и следующие за ним, пока не наберётся осмысленный
 * кусок. Трёх блоков жёстко не хватало: заголовок главы, эпиграф и реплика
 * диалога дают меньше сорока знаков, а сервер такой фрагмент отклоняет — и
 * «что думаешь об этой странице» переставало работать на каждом переходе к
 * новой главе.
 */
private fun visiblePageText(blocks: List<ReaderBlock>, activeBlock: Int): String {
    val forward = mutableListOf<String>()
    var length = 0
    for (block in blocks.drop(activeBlock)) {
        if (block.text.isBlank()) continue
        forward += block.text
        length += block.text.length
        if (length >= PAGE_TEXT_TARGET) break
    }
    // На последнем блоке главы вперёд брать уже нечего, а одного абзаца может
    // не хватить до минимума. Тогда добираем назад: читатель видит конец
    // страницы, и предыдущий абзац для мнения о ней — тот же экран.
    val backward = mutableListOf<String>()
    var index = activeBlock - 1
    while (length < PAGE_TEXT_MIN && index >= 0) {
        val text = blocks[index].text
        if (text.isNotBlank()) {
            backward += text
            length += text.length
        }
        index--
    }
    val pieces = backward.asReversed() + forward
    return pieces.joinToString(" ").takeCodePoints(PAGE_TEXT_MAX)
}

/*
 * Порядок слоёв над страницей.
 *
 * Назван явно, потому что расставлялся по одному числу за правку: карточка
 * слова оставалась на нуле и оказывалась под невидимым ловцом касаний панели
 * настроек, а лист сюжета и сама панель делили одно значение и раскладывались
 * по порядку объявления.
 */
private const val Z_COMPANION = 1f
private const val Z_SHEET = 2f
private const val Z_OVERLAY_SCRIM = 3f
private const val Z_OVERLAY = 4f

/**
 * Приход новой главы.
 *
 * Раньше содержимое просто подменялось: переход вперёд, назад и сбой выглядели
 * одинаково — «моргнуло». Теперь глава приезжает с той стороны, с которой её
 * позвали.
 *
 * Анимируется только приход. Уход потребовал бы держать на экране две главы
 * сразу, а состояние прокрутки у читалки одно на всех: две копии тела главы
 * начали бы спорить за него, и место в книге поехало бы ради красоты перехода.
 */
@Composable
private fun Modifier.chapterArrival(chapterIndex: Int): Modifier {
    val motion = WolfyTheme.motion
    val arrival = remember { Animatable(1f) }
    var previous by remember { mutableIntStateOf(chapterIndex) }
    var direction by remember { mutableIntStateOf(1) }
    val travel = with(LocalDensity.current) { CHAPTER_TRAVEL.toPx() }

    LaunchedEffect(chapterIndex) {
        if (chapterIndex == previous) return@LaunchedEffect
        direction = if (chapterIndex > previous) 1 else -1
        previous = chapterIndex
        if (motion.still) return@LaunchedEffect
        arrival.snapTo(0f)
        arrival.animateTo(1f, motion.paced(motion.calm))
    }

    if (motion.still) return this
    return this.graphicsLayer {
        alpha = arrival.value
        translationX = (1f - arrival.value) * travel * direction
    }
}

/** Насколько глава выезжает из-за края при переходе. */
private val CHAPTER_TRAVEL = 28.dp

/**
 * Сколько осталось читать до конца главы.
 *
 * Считается по словам, оставшимся ниже видимого блока, и по скорости чтения.
 * Скорость берётся у ведущей строки, если читатель её настроил: он там уже
 * сказал, в каком темпе читает. Иначе — спокойный темп чтения на неродном
 * языке, ради которого приложение и существует.
 *
 * Возвращает null там, где оценка была бы враньём: глава не разобрана, слов
 * не осталось или до конца меньше минуты.
 */
internal fun minutesLeftInChapter(blocks: List<ReaderBlock>, activeBlock: Int, pacerWpm: Int): Int? {
    if (blocks.isEmpty() || activeBlock >= blocks.size) return null
    var words = 0
    for (index in activeBlock.coerceAtLeast(0) until blocks.size) {
        val parsed = blocks[index].parsed ?: continue
        words += parsed.tokens.count { it.kind == "word" }
    }
    if (words == 0) return null
    val wpm = if (pacerWpm > 0) pacerWpm else LEARNER_WPM
    val minutes = (words + wpm - 1) / wpm
    return minutes.takeIf { it >= 1 }
}

/** Спокойный темп чтения на неродном языке. */
private const val LEARNER_WPM = 130

/** Ниже этого сервер считает страницу непригодной для мнения. */
private const val PAGE_TEXT_MIN = 40

/** Обычная страница: примерно три абзаца. */
private const val PAGE_TEXT_TARGET = 600

/** Потолок серверного контракта. */
private const val PAGE_TEXT_MAX = 4_000

/** Шапка: глава и полоса прогресса чтения. */
@Composable
private fun ReaderTopBar(
    state: ReaderState,
    withinChapterProgress: Float,
    onClose: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit,
    onRecap: () -> Unit,
    /** Оценка «сколько осталось читать главу» или null, если её не собрать. */
    minutesLeft: Int? = null,
    /** Режим компаньона или null, если компаньона нет. Тап переключает по кругу. */
    companionMode: String? = null,
    onCompanionMode: () -> Unit = {},
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val progress = if (state.chapterCount > 0) {
        ((state.chapterIndex + withinChapterProgress.coerceIn(0f, 1f)) / state.chapterCount)
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageMargin, vertical = spacing.small),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Возврат в библиотеку — слева и текстом, а не значком: значок
            // «назад» в шапке читалки читатели путают с переходом на
            // предыдущую страницу, и промах стоит потерянного места в книге.
            SectionLabel(
                text = "‹ библиотека",
                modifier = Modifier.pressable(onClick = onClose),
            )
            // Название главы — вход в оглавление. Отдельный значок для этого
            // не нужен: читатель и так смотрит сюда, чтобы понять, где он.
            //
            // Средней зоне отдаётся всё свободное место и обрезка: на узком
            // экране или крупном кегле длинная глава вытесняла кнопки справа.
            Text(
                text = (state.chapterTitle.ifBlank { state.bookTitle }).uppercase(),
                style = WolfyTheme.typography.sectionLabel,
                color = WolfyTheme.colors.inkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .pressable(onClick = onOpenContents),
            )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderActionIcon(
                        label = "Настройки чтения",
                        icon = NavIcon.Reading,
                        tint = colors.inkMuted,
                        onClick = onOpenSettings,
                    )
                    ReaderActionIcon(
                        label = "Вспомнить сюжет",
                        icon = NavIcon.Recap,
                        tint = colors.accent,
                        onClick = onRecap,
                    )
                    // Доля книги уже нарисована линейкой под шапкой, и второй
                    // раз числом она ничего не добавляет: «сорок три процента»
                    // не влияет ни на одно решение. Влияет другое — сколько
                    // осталось до конца главы, потому что читают до неё.
                    SectionLabel(minutesLeft?.let { "$it мин" } ?: "${(progress * 100).toInt()}%")
                }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(spacing.hair)
                .background(colors.rule),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(spacing.hair)
                .background(colors.accent),
            )
        }
        if (companionMode != null) {
            Row(Modifier.align(Alignment.End).padding(horizontal = spacing.pageMargin, vertical = spacing.small), horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                // Режимы компаньона в компактном меню: без него чтение не меняется.
                val modeLabel = when (companionMode) {
                    "quiet" -> "Компаньон: тихо"
                    "active" -> "Компаньон: рядом"
                    else -> "Читать с компаньоном"
                }
                Text(
                        modeLabel,
                        style = WolfyTheme.typography.caption,
                        color = if (companionMode == "active") colors.accent else colors.inkMuted,
                        modifier = Modifier.pressable(onClick = onCompanionMode),
                    )
            }
        }
    }
}

/** Компактное действие: подсказка появляется при наведении или фокусе. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderActionIcon(
    label: String,
    icon: NavIcon,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .pressable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            NavGlyph(icon, tint)
        }
    }
}

/** Настройки, доступные без выхода со страницы книги. */
@Composable
private fun ReaderQuickSettings(
    theme: ReadingTheme,
    fontScale: Float,
    lineScale: Float,
    onThemeChange: (ReadingTheme) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLineScaleChange: (Float) -> Unit,
    emphasizeStems: Boolean,
    onEmphasizeStems: (Boolean) -> Unit,
    focusMode: FocusMode,
    onFocusModeChange: (FocusMode) -> Unit,
    pacerWpm: Int,
    onPacerChange: (Int) -> Unit,
    segmentWords: Int,
    onSegmentWordsChange: (Int) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    // Настройки стали богаче, и на маленьком landscape-экране крупным кеглем
    // они больше не помещаются: панель ограничена четырьмя пятыми экрана и
    // прокручивается. Нижние переключатели остаются досягаемыми.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val panelCap = maxHeight * 0.8f
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = panelCap)
                .verticalScroll(rememberScrollState())
                .background(colors.surface)
                .padding(horizontal = spacing.pageMargin, vertical = spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            SectionLabel("Тема страницы")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                ReadingTheme.entries.forEach { option ->
                    Text(
                        text = option.title,
                        style = WolfyTheme.typography.caption,
                        color = if (theme == option) colors.onInverse else colors.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .pressable(onClick = { onThemeChange(option) })
                            .background(
                                if (theme == option) colors.inverse else colors.paper,
                                RoundedCornerShape(spacing.huge),
                            )
                            .padding(vertical = spacing.small),
                    )
                }
            }
            SettingStepper(
                title = "Размер текста",
                value = "${(fontScale * 100).toInt()}%",
                onLess = { onFontScaleChange(fontScale - 0.1f) },
                onMore = { onFontScaleChange(fontScale + 0.1f) },
            )
            SettingStepper(
                title = "Интервал строк",
                value = "${(lineScale * 100).toInt()}%",
                onLess = { onLineScaleChange(lineScale - 0.1f) },
                onMore = { onLineScaleChange(lineScale + 0.1f) },
            )
            QuickSwitch(
                title = "Выделять основу слова",
                on = emphasizeStems,
                onChange = onEmphasizeStems,
            )
            QuickChoice(
                title = "Окно чтения",
                choices = listOf(
                    FocusMode.Off to "нет",
                    FocusMode.Sentence to "фраза",
                    FocusMode.Paragraph to "абзац",
                ),
                selected = focusMode,
                onChange = onFocusModeChange,
            )
            QuickChoice(
                title = "Ведущая строка",
                choices = listOf(0 to "нет", 160 to "тихо", 220 to "обычно", 300 to "быстро"),
                selected = pacerWpm,
                onChange = onPacerChange,
            )
            QuickChoice(
                title = "Отрезок",
                choices = listOf(0 to "нет", 150 to "короткий", 400 to "средний", 900 to "длинный"),
                selected = segmentWords,
                onChange = onSegmentWordsChange,
            )
        }
    }
}

@Composable
private fun QuickSwitch(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = WolfyTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = WolfyTheme.typography.body, color = colors.ink)
        Text(
            text = if (on) "включено" else "выключено",
            style = WolfyTheme.typography.caption,
            color = if (on) colors.accent else colors.inkMuted,
            modifier = Modifier.pressable { onChange(!on) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> QuickChoice(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onChange: (T) -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
        Text(title, style = WolfyTheme.typography.body, color = colors.ink)
        // Четыре подписи в обычной Row на узком экране с крупным кеглем не
        // умещаются: переносятся на вторую строку.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.tight),
        ) {
            choices.forEach { (value, label) ->
                Text(
                    text = label,
                    style = WolfyTheme.typography.caption,
                    color = if (value == selected) colors.accent else colors.inkMuted,
                    modifier = Modifier.pressable { onChange(value) },
                )
            }
        }
    }
}

@Composable
private fun StoryRecapSheet(
    state: StoryRecapState,
    companion: com.wolfy.data.companion.CompanionProfile?,
    soundsEnabled: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    LaunchedEffect(state) {
        if (soundsEnabled && state is StoryRecapState.Ready) {
            playCompanionSound(CompanionSound.Ready)
        }
    }
    // Итог с событиями может быть длинным, а экран — коротким. Задавленная
    // кнопка «закрыть» хуже любого листания, поэтому sheet ограничен тремя
    // четвертями экрана и прокручивается целиком.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cap = maxHeight * 0.75f
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = cap)
                .verticalScroll(rememberScrollState())
                .background(colors.surface, RoundedCornerShape(spacing.large))
                .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.large))
                .padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.Top,
            ) {
                if (companion != null) {
                    // Выделенная колонка держит весь портрет, включая волосы и
                    // одежду. Текст больше не начинается под рисунком.
                    Box(Modifier.size(width = 68.dp, height = 76.dp), contentAlignment = Alignment.Center) {
                        CompanionFigure(companion.appearance, Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
                    Text("Сюжет · Beta", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                    if (companion != null) {
                        Text(
                            "${companion.name} вспоминает прочитанное",
                            style = WolfyTheme.typography.caption,
                            color = colors.inkMuted,
                        )
                    }
                    Text(
                        "ИИ может ошибаться. До 10 запросов в день.",
                        style = WolfyTheme.typography.caption,
                        color = colors.inkMuted,
                    )
                }
                // Отступ даёт кнопке нормальную цель нажатия: одна строка
                // кегля caption — это шестнадцать точек по высоте.
                Text(
                    "закрыть",
                    style = WolfyTheme.typography.caption,
                    color = colors.accent,
                    modifier = Modifier
                        .pressable(onClick = onDismiss)
                        .padding(horizontal = spacing.small, vertical = spacing.medium),
                )
            }
            when (state) {
                StoryRecapState.Loading ->
                    Text("Собираю события из прочитанного…", style = WolfyTheme.typography.body, color = colors.inkMuted)
                is StoryRecapState.Failed -> {
                    Text(state.message, style = WolfyTheme.typography.caption, color = colors.accent)
                    Text(
                        "Попробовать снова",
                        style = WolfyTheme.typography.button,
                        color = colors.accent,
                        modifier = Modifier
                            .pressable(onClick = onRetry)
                            .padding(vertical = spacing.small),
                    )
                }
                is StoryRecapState.Ready -> {
                    Text(state.value.summary, style = WolfyTheme.typography.body, color = colors.ink)
                    state.value.events.forEachIndexed { index, event ->
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                            Text(if (index == 0) "●" else "↓", style = WolfyTheme.typography.body, color = colors.accent)
                            Column {
                                Text(event.title, style = WolfyTheme.typography.body, color = colors.ink)
                                Text(event.text, style = WolfyTheme.typography.caption, color = colors.inkMuted)
                            }
                        }
                    }
                    Text(
                        "Осталось сегодня: ${state.value.remaining}",
                        style = WolfyTheme.typography.caption,
                        color = colors.inkMuted,
                    )
                }
                StoryRecapState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun SettingStepper(
    title: String,
    value: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = WolfyTheme.typography.body, color = WolfyTheme.colors.ink)
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Цвет задаётся явно. Без него material3 брал LocalContentColor,
            // а тот по умолчанию чёрный — и обе подписи пропадали на тёмной
            // и OLED-бумаге.
            StepperAction("Меньше", onLess)
            Text(value, style = WolfyTheme.typography.body, color = WolfyTheme.colors.ink)
            StepperAction("Больше", onMore)
        }
    }
}

/** Подпись-действие шага: своя цель нажатия, а не одна строка кегля caption. */
@Composable
private fun StepperAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = WolfyTheme.typography.caption,
        color = WolfyTheme.colors.accent,
        modifier = Modifier
            .pressable(onClick = onClick)
            .padding(horizontal = WolfyTheme.spacing.small, vertical = WolfyTheme.spacing.medium),
    )
}

/** Замеченный кадр прокрутки: доля главы и якорь «блок + смещение внутри». */
private data class ScrollReport(
    val place: Float,
    val blockIndex: Int,
    val blockOffset: Float,
)

/** Живое выделение фразы: номер блока и диапазон в смещениях абзаца. */
private data class PhraseSelection(val block: Int, val range: IntRange)

/** Абсолютный потолок высоты иллюстрации: доля экрана его только ограничивает. */
private val ImageMaxHeight = 640.dp

@Composable
private fun ChapterBody(
    state: ReaderState,
    scroll: LazyListState,
    focusMode: FocusMode,
    activeBlock: Int,
    activeSentence: Int,
    onWordTap: (Int, com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrolled: (Float, Int, Float) -> Unit,
    images: Map<String, ImageBitmap?>,
    onImageVisible: (String) -> Unit,
    phraseSelectionBlock: Int,
    phraseSelectionRange: IntRange?,
    selectViaMouse: Boolean,
    onPhraseSelect: (Int, IntRange) -> Unit,
    onPhraseCommit: (Int, IntRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WolfyTheme.spacing

    // Возвращение на место: один раз на открытие книги. Ждём, пока список
    // сообщит, сколько в нём блоков, — до первой раскладки их ноль, и
    // прокручивать некуда.
    //
    // Есть якорь «блок + смещение» — идём по нему: сначала блок целиком,
    // затем доля его прокручиваемой высоты, как только раскладка измерила
    // высоту этого блока. Так позиция восстанавливается внутри огромного
    // абзаца, чего старая доля от числа блоков позволить себе не могла.
    LaunchedEffect(scroll, state.chapterIndex, state.startAt, state.anchorIndex) {
        if (state.startAt <= 0f && state.anchorIndex < 0) return@LaunchedEffect
        val total = snapshotFlow { scroll.layoutInfo.totalItemsCount }
            .first { it > 1 }
        val anchor = state.anchorIndex
        if (anchor >= 0) {
            val target = anchor.coerceIn(0, total - 1)
            scroll.scrollToItem(target)
            // Высота блока известна только после того, как он стал видимым:
            // вторым проходом докручиваем к точной доле внутри блока.
            var attempts = 0
            while (state.anchorOffset > 0f && attempts < 8) {
                kotlinx.coroutines.delay(32)
                val item = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                    ?: scroll.layoutInfo.visibleItemsInfo.firstOrNull() ?: break
                if (item.index == target && item.size > 0) {
                    val within = (state.anchorOffset * item.size).toInt()
                    if (within > 1) scroll.scrollToItem(target, within)
                    return@LaunchedEffect
                }
                attempts += 1
            }
            return@LaunchedEffect
        }
        scroll.scrollToItem((state.startAt * (total - 1)).toInt().coerceIn(0, total - 1))
    }

    LaunchedEffect(scroll, state.chapterIndex) {
        snapshotFlow {
            val layout = scroll.layoutInfo
            val total = layout.totalItemsCount
            if (total <= 1) {
                ScrollReport(0f, -1, 0f)
            } else {
                val first = scroll.firstVisibleItemIndex.coerceIn(0, total - 1)
                val item = layout.visibleItemsInfo.firstOrNull { it.index == first }
                // В длинном единственном абзаце индекс всегда нулевой. Его
                // смещение внутри viewport всё равно сообщает, какую часть
                // блока читатель уже прошёл, и именно оно не даёт 0% до
                // самого конца главы.
                val inItem = item?.let {
                    (-it.offset).toFloat().coerceAtLeast(0f) / it.size.coerceAtLeast(1)
                }?.coerceIn(0f, 1f) ?: 0f
                val fraction = ((first + inItem) / (total - 1).toFloat()).coerceIn(0f, 1f)
                ScrollReport(fraction, first, inItem)
            }
        }.collect { report -> onScrolled(report.place, report.blockIndex, report.blockOffset) }
    }

    // Ленивый список, а не прокручиваемая колонка: глава романа — это сотни
    // абзацев, и рисовать их все разом значит держать кадр несколько секунд.
    // Буквица открывает главу, а не каждый абзац: ищем первый текстовый блок
    // один раз на главу. Внутри itemsIndexed этот поиск шёл бы заново для
    // каждого блока — сотня линейных проходов по сотне блоков на каждый кадр
    // прокрутки.
    val dropCapAt = remember(state.blocks) { state.blocks.indexOfFirst { it.kind == "paragraph" } }

    LazyColumn(
        modifier.fillMaxSize(),
        state = scroll,
        contentPadding = PaddingValues(
            start = spacing.pageMargin,
            end = spacing.pageMargin,
            top = spacing.large,
            bottom = spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        itemsIndexed(
            items = state.blocks,
            contentType = { _, block -> block.kind },
        ) { index, block ->
            BlockView(
                index = index,
                block = block,
                withDropCap = index == dropCapAt,
                savedLemmas = state.savedLemmas,
                // Окно чтения: светлым остаётся только то место, где читатель
                // сейчас. Абзац целиком или одно предложение в нём — решает он.
                dimmed = focusMode != FocusMode.Off && index != activeBlock,
                bright = if (focusMode == FocusMode.Sentence && index == activeBlock) {
                    block.parsed?.sentences?.getOrNull(activeSentence)
                        ?.let { it.start until it.end }
                } else {
                    null
                },
                // Подсвечивается только тот блок, в котором нашлось слово.
                // Иначе абзац с тем же смещением подсветит своё слово заодно —
                // смещения у блоков считаются каждое от своего начала.
                selected = state.card?.token?.takeIf { index == state.selectedBlock },
                phraseSelection = phraseSelectionRange?.takeIf { index == phraseSelectionBlock },
                selectViaMouse = selectViaMouse,
                onPhrase = { range -> onPhraseSelect(index, range) },
                onPhraseDone = { range -> onPhraseCommit(index, range) },
                image = block.imagePath?.let(images::get),
                onImageVisible = onImageVisible,
                onWordTap = onWordTap,
            )
        }

        item {
            ChapterNavigation(
                state = state,
                onPrevious = onPreviousChapter,
                onNext = onNextChapter,
            )
        }
    }
}


@Composable
private fun BlockView(
    index: Int,
    block: ReaderBlock,
    withDropCap: Boolean,
    savedLemmas: Set<String>,
    dimmed: Boolean,
    bright: IntRange?,
    selected: com.wolfy.ffi.Token?,
    phraseSelection: IntRange?,
    selectViaMouse: Boolean,
    onPhrase: (IntRange) -> Unit,
    onPhraseDone: (IntRange) -> Unit,
    image: ImageBitmap?,
    onImageVisible: (String) -> Unit,
    onWordTap: (Int, com.wolfy.ffi.Token, com.wolfy.ffi.ParsedText) -> Unit,
) {
    val parsed = block.parsed

    when (block.kind) {
        "heading" -> ChapterHeading(block.text)

        "paragraph" -> if (parsed != null) {
            if (withDropCap) {
                DropCapParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    savedLemmaOf = { it.text.lowercase() },
                    selected = selected,
                    selection = phraseSelection,
                    selectViaMouse = selectViaMouse,
                    anchors = block.anchors,
                    dimmed = dimmed,
                    onWordTap = { onWordTap(index, it, parsed) },
                    onPhrase = onPhrase,
                    onPhraseDone = onPhraseDone,
                )
            } else {
                ReaderParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    savedLemmaOf = { it.text.lowercase() },
                    selected = selected,
                    selection = phraseSelection,
                    selectViaMouse = selectViaMouse,
                    anchors = block.anchors,
                    dimmed = dimmed,
                    bright = bright,
                    onWordTap = { onWordTap(index, it, parsed) },
                    onPhrase = onPhrase,
                    onPhraseDone = onPhraseDone,
                )
            }
        }

        "quote" -> ReaderQuote(block.text)

        "listItem" -> if (parsed != null) {
            Row {
                Text("• ", style = WolfyTheme.typography.reader, color = WolfyTheme.colors.inkMuted)
                ReaderParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    selected = selected,
                    selection = phraseSelection,
                    selectViaMouse = selectViaMouse,
                    anchors = block.anchors,
                    dimmed = dimmed,
                    onWordTap = { onWordTap(index, it, parsed) },
                    onPhrase = onPhrase,
                    onPhraseDone = onPhraseDone,
                )
            }
        }

        "divider" -> Box(
            Modifier.fillMaxWidth().padding(vertical = WolfyTheme.spacing.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "* * *",
                style = WolfyTheme.typography.reader,
                color = WolfyTheme.colors.inkMuted,
            )
        }

        "image" -> {
            block.imagePath?.let { path ->
                // При вытеснении из LRU `image` меняется на null: эффект
                // попросит ресурс заново только если этот блок всё ещё
                // видим, а не оставит его подписью навсегда.
                LaunchedEffect(path, image) { onImageVisible(path) }
            }
            if (image != null) {
                // Верхняя граница — доля доступного экрана, а не константа:
                // на телефонном landscape 480.dp съедали экран целиком, а на
                // десктопе крупную иллюстрацию ужимали без причины.
                BoxWithConstraints {
                    val cap = min(maxHeight * 0.55f, ImageMaxHeight)
                    Image(
                        bitmap = image,
                        contentDescription = block.alt,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = cap),
                    )
                }
            }
            // Подпись остаётся рядом с удачной картинкой и становится
            // fallback, если ресурс испорчен или старая библиотека ядра ещё
            // не умеет бинарный API.
            block.alt?.let { SectionLabel(it) }
        }

        // Rich scientific renderer можно добавить отдельно; сейчас ни
        // формула, ни её fallback не исчезают вместе с незнакомым MathML.
        "math" -> Column(verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.tight)) {
            SectionLabel("Формула")
            Text(block.text, style = WolfyTheme.typography.reader, color = WolfyTheme.colors.ink)
        }

        // В pre переносы и отступы — содержимое, а не вёрстка абзаца: моно-
        // шрифт и осторожный перенос, чтобы длинная строка кода или адреса
        // не растянула страницу шире экрана.
        "pre" -> Text(
            block.text,
            style = WolfyTheme.typography.reader.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = WolfyTheme.colors.ink,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )

        "table" -> TableBlock(block.rows, block.text)

        // Новое kind старого ядра/формата не должно превращаться в пустоту.
        else -> if (block.text.isNotBlank()) {
            Text(block.text, style = WolfyTheme.typography.reader, color = WolfyTheme.colors.ink)
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>?, fallback: String) {
    val spacing = WolfyTheme.spacing
    val table = rows.orEmpty()
    if (table.isEmpty()) {
        Text(fallback, style = WolfyTheme.typography.reader, color = WolfyTheme.colors.ink)
        return
    }
    // Сетка общая для всей таблицы: строка из двух ячеек обязана делить
    // ширину на столько же колонок, что и строка из четырёх, иначе колонки
    // «плавают» от строки к строке.
    val columnCount = table.maxOf { it.size }
    val minWidthPerColumn = 96.dp
    // Широкие таблицы листаются вбок с фиксированной шириной колонки, а не
    // сплющиваются в нечитаемую полоску. Узкие делят ширину поровну.
    val wide = columnCount > 4
    Column(
        if (wide) {
            Modifier.horizontalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        },
        verticalArrangement = Arrangement.spacedBy(spacing.tight),
    ) {
        table.forEach { row ->
            // Недостающие ячейки достраиваются пустыми: без этого строка с
            // меньшим числом клеток вылетала бы из общей сетки.
            val padded = List(columnCount) { index -> row.getOrElse(index) { "" } }
            Row(
                Modifier.takeIf { !wide }?.fillMaxWidth() ?: Modifier,
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                padded.forEach { cell ->
                    Text(
                        cell,
                        style = WolfyTheme.typography.caption,
                        color = WolfyTheme.colors.ink,
                        modifier =
                            if (wide) {
                                Modifier.width(minWidthPerColumn)
                            } else {
                                Modifier.weight(1f)
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterNavigation(
    state: ReaderState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val spacing = WolfyTheme.spacing

    Column(Modifier.fillMaxWidth().padding(top = spacing.xlarge)) {
        Rule()
        Row(
            Modifier.fillMaxWidth().padding(top = spacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (state.hasPrevious) {
                SectionLabel("← предыдущая", Modifier.pressable(onClick = onPrevious))
            } else {
                SectionLabel(" ")
            }
            SectionLabel("${state.chapterIndex + 1} / ${state.chapterCount}")
            if (state.hasNext) {
                SectionLabel("следующая →", Modifier.pressable(onClick = onNext))
            } else {
                SectionLabel(" ")
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(WolfyTheme.spacing.pageMargin),
        )
    }
}

/**
 * Какую часть окна страница оставляет от предыдущей.
 *
 * Прокрутка ровно на высоту окна кажется правильной и читается плохо: строка,
 * на которой читатель остановился, уезжает за верхний край, и глазу не за что
 * зацепиться — он ищет продолжение по смыслу, а не по месту. Десятая часть
 * окна — это примерно две строки, и их достаточно.
 */
private const val PAGE_OVERLAP = 0.1f

/** Страница вперёд или назад — по высоте окна за вычетом перекрытия. */
private suspend fun LazyListState.turnPage(forward: Boolean) {
    val viewport = layoutInfo.viewportSize.height
    if (viewport <= 0) return
    val step = viewport * (1f - PAGE_OVERLAP)
    animateScrollBy(if (forward) step else -step)
}

/**
 * Сколько надо провести пальцем вбок, чтобы сменилась глава.
 *
 * Сто точек — это движение, которое не сделаешь случайно, отрывая палец от
 * страницы при прокрутке, но и не размах через весь экран.
 */
private val SWIPE_TO_CHAPTER = 100.dp

/**
 * Смена главы движением пальца вбок.
 *
 * Только там, где нет клавиатуры. На машине с мышью тот же обработчик ловил бы
 * протяжку указателем по странице — движение, которым ничего не хотели, — и
 * уносил читателя в соседнюю главу.
 *
 * Вертикальной прокрутке жест не мешает: горизонтальная протяжка начинает
 * считаться только после того, как палец ушёл вбок дальше порога системы, а
 * движение вниз до этого порога не доходит никогда.
 */
@Composable
private fun Modifier.chapterSwipe(
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(onPrevious, onNext) {
        var travelled = 0f
        val enough = SWIPE_TO_CHAPTER.toPx()
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragCancel = { travelled = 0f },
            onDragEnd = {
                // Палец влево — вперёд: страница уезжает влево, как в книге.
                when {
                    travelled <= -enough -> onNext()
                    travelled >= enough -> onPrevious()
                }
            },
        ) { _, amount -> travelled += amount }
    }
}
