package com.wolfy.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.wolfy.ui.card.WordCardSheet
import com.wolfy.ui.nav.shortcuts
import com.wolfy.ui.nav.LocalKeyboard
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.derivedStateOf
import com.wolfy.data.FocusMode
import com.wolfy.platform.KeepScreenAwake
import com.wolfy.theme.ReadingTheme
import kotlinx.coroutines.delay
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.ChapterHeading
import com.wolfy.widgets.DropCapParagraph
import com.wolfy.widgets.ReaderParagraph
import com.wolfy.widgets.ReaderQuote
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel

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
    onScrolled: (Float) -> Unit,
    onChapter: (Int) -> Unit,
    onOpenRule: (String) -> Unit,
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
    onSegmentWordsChange: (Int) -> Unit = {},
    onNextSegment: (Int) -> Unit = {},
    onStopSegments: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Пока книга открыта, системный таймер не должен гасить экран посреди
    // абзаца. Эффект сам снимает запрет при выходе из читалки.
    KeepScreenAwake()

    val colors = WolfyTheme.colors
    var contentsOpen by remember { mutableStateOf(false) }
    var readingSettingsOpen by remember { mutableStateOf(false) }

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
    val goBack = {
        when {
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
                enabled = !cardOpen && !contentsOpen && !LocalKeyboard.current,
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
        Column(Modifier.fillMaxSize()) {
            ReaderTopBar(
                state = state,
                withinChapterProgress = withinChapterProgress,
                onClose = onClose,
                onOpenContents = { contentsOpen = true },
                onOpenSettings = { readingSettingsOpen = !readingSettingsOpen },
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
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Это overlay, а не разворачивающийся блок в Column: открытие текста
        // не меняет высоту viewport и не сдвигает строку, на которой читатель
        // остановился.
        AnimatedVisibility(
            visible = readingSettingsOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .zIndex(2f),
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
            state = state.card,
            onDismiss = onDismissCard,
            onSave = onSaveWord,
            onSavePhrase = onSavePhrase,
            onPronounce = onPronounce,
            onOpenRule = onOpenRule,
        )
    }
}

/** Шапка: глава и полоса прогресса чтения. */
@Composable
private fun ReaderTopBar(
    state: ReaderState,
    withinChapterProgress: Float,
    onClose: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit,
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
            horizontalArrangement = Arrangement.SpaceBetween,
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
            SectionLabel(
                text = state.chapterTitle.ifBlank { state.bookTitle },
                modifier = Modifier.pressable(onClick = onOpenContents),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                SectionLabel("Текст", Modifier.pressable(onClick = onOpenSettings))
                SectionLabel("${(progress * 100).toInt()}%")
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
    Column(
        Modifier
            .fillMaxWidth()
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
                        .background(
                            if (theme == option) colors.inverse else colors.paper,
                            androidx.compose.foundation.shape.RoundedCornerShape(spacing.huge),
                        )
                        .pressable(onClick = { onThemeChange(option) })
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
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
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
            Text("Меньше", style = WolfyTheme.typography.caption, modifier = Modifier.pressable(onClick = onLess))
            Text(value, style = WolfyTheme.typography.body, color = WolfyTheme.colors.ink)
            Text("Больше", style = WolfyTheme.typography.caption, modifier = Modifier.pressable(onClick = onMore))
        }
    }
}

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
    onScrolled: (Float) -> Unit,
    images: Map<String, ImageBitmap?>,
    onImageVisible: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WolfyTheme.spacing

    // Возвращение на место: один раз на открытие книги. Ждём, пока список
    // сообщит, сколько в нём блоков, — до первой раскладки их ноль, и
    // прокручивать некуда.
    LaunchedEffect(scroll, state.chapterIndex, state.startAt) {
        if (state.startAt <= 0f) return@LaunchedEffect
        snapshotFlow { scroll.layoutInfo.totalItemsCount }
            .first { it > 1 }
            .let { total ->
                scroll.scrollToItem((state.startAt * (total - 1)).toInt().coerceIn(0, total - 1))
            }
    }

    LaunchedEffect(scroll, state.chapterIndex) {
        snapshotFlow {
            val layout = scroll.layoutInfo
            val total = layout.totalItemsCount
            if (total <= 1) {
                0f
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
                ((first + inItem) / (total - 1).toFloat()).coerceIn(0f, 1f)
            }
        }.collect { onScrolled(it) }
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
                    anchors = block.anchors,
                    dimmed = dimmed,
                    onWordTap = { onWordTap(index, it, parsed) },
                )
            } else {
                ReaderParagraph(
                    parsed = parsed,
                    saved = savedLemmas,
                    savedLemmaOf = { it.text.lowercase() },
                    selected = selected,
                    anchors = block.anchors,
                    dimmed = dimmed,
                    bright = bright,
                    onWordTap = { onWordTap(index, it, parsed) },
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
                    anchors = block.anchors,
                    dimmed = dimmed,
                    onWordTap = { onWordTap(index, it, parsed) },
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
                Image(
                    bitmap = image,
                    contentDescription = block.alt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                )
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

        // В pre переносы/отступы — содержимое, не обычная вёрстка абзаца.
        "pre" -> Text(
            block.text,
            style = WolfyTheme.typography.reader,
            color = WolfyTheme.colors.ink,
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
    Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
        table.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                row.forEach { cell ->
                    Text(
                        cell,
                        style = WolfyTheme.typography.caption,
                        color = WolfyTheme.colors.ink,
                        modifier = Modifier.weight(1f),
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
