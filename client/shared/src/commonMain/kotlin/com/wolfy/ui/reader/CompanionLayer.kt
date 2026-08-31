package com.wolfy.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wolfy.data.CompanionAiResult
import com.wolfy.data.CompanionOpinion
import com.wolfy.data.CompanionPersonaIn
import com.wolfy.data.CompanionQuestion
import com.wolfy.data.WolfyApi
import com.wolfy.data.companion.CompanionAppearance
import com.wolfy.data.companion.CompanionPhrasePack
import com.wolfy.data.companion.CompanionProfile
import com.wolfy.data.companion.CompanionMemoryRepository
import com.wolfy.data.companion.CompanionReactionEngine
import com.wolfy.data.companion.CompanionRepository
import com.wolfy.data.companion.FallbackPhrases
import com.wolfy.data.companion.MoodScorer
import com.wolfy.data.companion.PHRASE_COUNT
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.platform.CompanionSound
import com.wolfy.platform.playCompanionSound
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.settling
import com.wolfy.theme.still
import com.wolfy.ui.companion.CompanionFigure
import com.wolfy.ui.companion.CompanionMotion
import com.wolfy.ui.companion.rememberCompanionPose
import com.wolfy.widgets.PrimaryButton
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.CompanionSpark
import com.wolfy.widgets.SparkKind
import com.wolfy.widgets.TypesettingLine
import com.wolfy.widgets.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Компаньон в читалке.
 *
 * В обычном состоянии виден только узкий газетный ярлычок у правого края.
 * Читатель тянет его влево (или нажимает мышью), и компаньон появляется по
 * его просьбе. Так персонаж не закрывает строки и не возвращается сам после
 * прокрутки. Обычное чтение не делает ни одного сетевого запроса: реплики
 * выбираются локально, сеть трогают только явные действия читателя.
 */
@Composable
fun CompanionLayer(
    profile: CompanionProfile,
    onProfileChange: (CompanionProfile) -> Unit,
    persona: CompanionPersonaIn,
    api: WolfyApi,
    memory: CompanionMemoryRepository? = null,
    bookId: String,
    bookTitle: String,
    chapter: Int,
    offset: () -> Int,
    pageText: () -> String,
    /** Прочитанное для вопроса о книге. Мнение строится по видимой странице. */
    readContext: suspend () -> String,
    suppressed: Boolean,
    scrolling: Boolean,
    compact: Boolean,
    soundsEnabled: Boolean,
    activeBlock: Int,
    chapterKey: Int,
    onRecap: () -> Unit,
    onEditCompanion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val scope = rememberCoroutineScope()
    val mode = profile.readerMode
    if (mode == "off") return

    val reactionsAllowed = mode == "active" && profile.reactionsEnabled

    // Персональный набор, а если его ещё нет — встроенный. Работоспособность
    // офлайн важнее персональности: приложение обязано жить без сети.
    val pack = profile.phrasePack?.takeIf { it.phrases.size == PHRASE_COUNT }
        ?: FallbackPhrases.pack(profile.locale)
    // Хеш и источник опознают набор целиком. Список из ста реплик как ключ
    // означал поэлементное сравнение на каждой рекомпозиции и ничего сверх.
    val engine = remember(profile.id, pack.profileHash, pack.source) {
        CompanionReactionEngine(pack, ::currentTimeMillis, CompanionReactionEngine.seedFor(profile.id, 0))
    }

    var bubble by remember { mutableStateOf<String?>(null) }
    var revealed by remember(profile.id, bookId) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var aiSheet by remember { mutableStateOf<AiSheetState?>(null) }
    var questionDraft by remember { mutableStateOf("") }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    var pendingAiAction by remember { mutableStateOf<PendingAiAction?>(null) }
    // Вспышка над компаньоном: лампочка на готовый ответ, звёздочки на
    // заготовленную реплику. Счётчик, а не флаг: две подряд одинаковые
    // вспышки обязаны сыграть дважды.
    var sparkTrigger by remember { mutableStateOf(0) }
    var sparkKind by remember { mutableStateOf(SparkKind.Cheer) }
    // Намёк про протяжку показывается один раз за сессию чтения.
    var hintShown by remember(profile.id) { mutableStateOf(false) }
    // Начало сессии чтения: по нему считаются настоящие минуты для реплик.
    var sessionStartedAt by remember(profile.id) { mutableStateOf(currentTimeMillis()) }
    val uriHandler = LocalUriHandler.current
    val motion = WolfyTheme.motion

    // Жест текущей реплики. Набор реплик носит его в поле motion у каждой
    // фразы: сервер это поле проверяет, клиент разбирал и клал в модель, а
    // персонаж всё равно стоял столбом. Счётчик рядом с жестом обязателен —
    // две одинаковые реплики подряд должны сыграть его дважды.
    var gesture by remember { mutableStateOf(CompanionMotion.None) }
    var gestureTrigger by remember { mutableStateOf(0) }

    fun play(motionCode: String?) {
        gesture = CompanionMotion.of(motionCode)
        gestureTrigger += 1
    }

    fun revealCompanion() {
        if (!revealed && soundsEnabled) playCompanionSound(CompanionSound.Reveal)
        revealed = true
    }

    fun closeAiSheet() {
        aiJob?.cancel()
        aiJob = null
        aiSheet = null
    }

    // Карточка слова, настройки и оглавление имеют безусловный приоритет.
    // Раньше закрывалась только фигура, а меню/согласие продолжали лежать
    // поверх карточки и активный запрос мог завершиться в уже чужом экране.
    LaunchedEffect(suppressed) {
        if (suppressed) {
            revealed = false
            bubble = null
            menuOpen = false
            pendingAiAction = null
            closeAiSheet()
        }
    }

    // Любая прокрутка снова освобождает поле книги. Компаньон не появляется
    // обратно сам: открыть его можно только новой протяжкой ярлычка.
    LaunchedEffect(scrolling) {
        if (scrolling) revealed = false
    }

    // Если персонажа вытащили и ничего не открыли, он спокойно прячется сам.
    // Меню и ответы ИИ не закрываются по таймеру.
    LaunchedEffect(revealed, bubble, menuOpen, aiSheet) {
        if (revealed && bubble == null && !menuOpen && aiSheet == null) {
            delay(8_000)
            revealed = false
        }
    }

    // Старт сессии: одна реплика на открытие читалки.
    LaunchedEffect(profile.id) {
        sessionStartedAt = currentTimeMillis()
        engine.newSession()
        engine.decide(
            CompanionReactionEngine.Event.SessionStart,
            CompanionReactionEngine.Context(0, overlayOpen = false, scrolling = false, reactionsEnabled = reactionsAllowed),
        ).phrase?.let { bubble = it.text; play(it.motion) }
    }

    // Завершение главы: событие только при реальном переходе вперёд. При
    // первом открытии книги поздравление показывать нельзя.
    var previousChapter by remember(profile.id, bookId) { mutableStateOf(chapterKey) }
    LaunchedEffect(chapterKey) {
        if (chapterKey > previousChapter) {
            engine.decide(
                CompanionReactionEngine.Event.ChapterCompleted,
                CompanionReactionEngine.Context(0, overlayOpen = false, scrolling = false, reactionsEnabled = reactionsAllowed),
            ).phrase?.let { bubble = it.text; play(it.motion) }
        }
        previousChapter = chapterKey
    }

    // Покой после прокрутки: две секунды без движения дают событие страницы,
    // каждое восьмое добавляет настроение по локальной оценке текста.
    var restedBlock by remember { mutableStateOf(-1) }
    var rests by remember { mutableStateOf(0) }
    // Значение на момент проверки, а не на момент запуска эффекта. Эффект
    // ждёт две секунды, и за это время читатель успевает открыть карточку
    // слова: захваченное `suppressed` оставалось ложным, и пузырь всплывал
    // поверх открытой карточки.
    val currentlySuppressed by rememberUpdatedState(suppressed)
    LaunchedEffect(scrolling, activeBlock) {
        if (scrolling || activeBlock == restedBlock) return@LaunchedEffect
        delay(2_000)
        if (currentlySuppressed) return@LaunchedEffect
        restedBlock = activeBlock
        rests += 1
        val context = CompanionReactionEngine.Context(
            // Настоящие минуты сессии, а не «одна за две остановки прокрутки».
            // По выдуманной мере реплики с minMinutes от получаса не выпадали
            // никогда: до них нужно было двести раз остановиться.
            sessionMinutes = ((currentTimeMillis() - sessionStartedAt) / 60_000L).toInt(),
            overlayOpen = currentlySuppressed,
            scrolling = false,
            reactionsEnabled = reactionsAllowed,
        )
        engine.decide(CompanionReactionEngine.Event.PageCompleted, context).phrase?.let { bubble = it.text; play(it.motion) }
        if (rests % 8 == 0 && bubble == null) {
            val mood = MoodScorer.analyze(pageText())
            if (mood.mood != MoodScorer.NEUTRAL) {
                engine.decide(CompanionReactionEngine.Event.Mood(mood.mood), context).phrase?.let { bubble = it.text; play(it.motion) }
            }
        }
    }

    // Пузырь живёт недолго: две короткие строки и сам закрывается.
    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(6_000)
            bubble = null
        }
    }

    fun runOpinion(consentGranted: Boolean = false) {
        if (!consentGranted && profile.aiConsentAt <= 0) {
            pendingAiAction = PendingAiAction.Opinion
            aiSheet = AiSheetState.Consent
            return
        }
        aiJob?.cancel()
        engine.noteManualShow()
        val position = offset()
        val visibleText = pageText()
        memory?.findOpinion(bookId, chapter, visibleText, profile.profileHash)?.let {
            aiSheet = AiSheetState.OpinionReady(it)
            return
        }
        aiSheet = AiSheetState.Loading
        aiJob = scope.launch {
            val result = api.companionOpinion(
                bookId, bookTitle, chapter, position, visibleText, persona, memory?.contextFor(bookId).orEmpty(),
            )
            if (result is CompanionAiResult.Ready) {
                memory?.rememberOpinion(bookId, chapter, visibleText, profile.profileHash, result.value)
            }
            aiSheet = when (result) {
                is CompanionAiResult.Ready -> AiSheetState.OpinionReady(result.value)
                is CompanionAiResult.Failed ->
                    AiSheetState.Failed(result.message, PendingAiAction.Opinion, retryable = result.code != "quota")
            }
        }
    }

    fun runQuestion(consentGranted: Boolean = false) {
        val question = questionDraft.trim()
        if (question.length !in 3..500) return
        if (!consentGranted && profile.aiConsentAt <= 0) {
            pendingAiAction = PendingAiAction.Question
            aiSheet = AiSheetState.Consent
            return
        }
        aiJob?.cancel()
        engine.noteManualShow()
        aiSheet = AiSheetState.Loading
        aiJob = scope.launch {
            // Вопрос о книге отвечается по прочитанному, а не по видимой
            // странице: «что уже случилось» в трёх абзацах перед глазами
            // не написано, и раньше компаньон честно уходил в «не знаю».
            val history = readContext().ifBlank { pageText() }
            val position = offset()
            // В ключ памяти уходит место, а не прочитанное: прочитанное растёт
            // каждой строкой, и один и тот же вопрос за вечер был бы для
            // памяти двумя разными. На сервер прочитанное едет по-прежнему —
            // ответ считается по нему, а узнаётся вопрос по месту.
            val cached = memory?.findQuestion(bookId, chapter, question, position, profile.profileHash)
            val result = if (cached != null) {
                CompanionAiResult.Ready(cached)
            } else {
                api.companionQuestion(
                    bookId, bookTitle, chapter, position, question, history, persona, memory?.contextFor(bookId).orEmpty(),
                )
            }
            if (result is CompanionAiResult.Ready && !result.value.cached) {
                memory?.rememberQuestion(
                    bookId, bookTitle, chapter, question, position, profile.profileHash, result.value,
                )
            }
            aiSheet = when (result) {
                is CompanionAiResult.Ready -> AiSheetState.QuestionReady(result.value)
                is CompanionAiResult.Failed ->
                    AiSheetState.Failed(result.message, PendingAiAction.Question, retryable = result.code != "quota")
            }
        }
    }

    fun runRecap(consentGranted: Boolean = false) {
        if (!consentGranted && profile.aiConsentAt <= 0) {
            pendingAiAction = PendingAiAction.Recap
            aiSheet = AiSheetState.Consent
            return
        }
        engine.noteManualShow()
        onRecap()
    }

    /**
     * Реплика из готового набора, без сети и без квоты.
     *
     * До сих пор у читателя был единственный способ обратиться к компаньону -
     * потратить один из десяти дневных запросов к модели. Это делало обычное
     * «скажи что-нибудь» дорогим и потому неуместным: с персонажем нельзя было
     * просто поговорить. Набор из ста реплик лежит на устройстве и на такие
     * просьбы отвечает сам.
     */
    fun saySomething(scenario: String, kind: SparkKind) {
        menuOpen = false
        aiSheet = null
        revealed = true
        val phrase = engine.offer(scenario) ?: return
        bubble = phrase.text
        // На просьбу «скажи что-нибудь» персонаж отвечает вслух, поэтому речь
        // важнее приписанного фразе жеста: молча раскрытый пузырь читается
        // как чужая реплика, а не как его собственная.
        play(if (phrase.motion == "none") CompanionMotion.Speak.code else phrase.motion)
        sparkKind = kind
        sparkTrigger += 1
        if (soundsEnabled) playCompanionSound(CompanionSound.Reaction)
    }

    val ribbonEnter = fadeIn(motion.paced(motion.quick)) +
        slideInHorizontally(motion.paced(motion.calm)) { it }
    val ribbonExit = fadeOut(motion.paced(motion.instant)) +
        slideOutHorizontally(motion.paced(motion.quick)) { it }

    // Последнее непустое значение. AnimatedVisibility рисует содержимое и во
    // время ухода, а к этому моменту состояние уже пустое: наружу уезжала
    // пустая рамка вместо реплики.
    val lastBubble = rememberLastNotNull(bubble)
    val lastSheet = rememberLastNotNull(aiSheet)

    Box(modifier, contentAlignment = Alignment.BottomEnd) {
        // Пузырь. Карточка слова и любые оверлеи полностью его скрывают.
        AnimatedVisibility(
            visible = revealed && bubble != null && !suppressed && !menuOpen && aiSheet == null,
            enter = fadeIn(motion.paced(motion.quick)) +
                slideInVertically(motion.settling()) { it / 2 },
            exit = fadeOut(motion.paced(motion.instant)) +
                slideOutVertically(motion.paced(motion.quick)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = (if (compact) FIGURE_COMPACT else FIGURE_WIDE) + 8.dp),
        ) {
            Text(
                lastBubble.orEmpty(),
                style = WolfyTheme.typography.caption,
                color = colors.ink,
                maxLines = 2,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .pressable(enabled = true) {
                        menuOpen = true
                        bubble = null
                    }
                    .clip(RoundedCornerShape(spacing.medium))
                    .background(colors.surface)
                    .border(1.dp, colors.rule, RoundedCornerShape(spacing.medium))
                    .padding(spacing.medium),
            )
        }

        // Меню и лист ответа — одна панель с двумя лицами, а не два слоя в
        // одной точке. Раньше меню гасло, и на его месте с нуля проявлялся
        // лист: между ними был кадр пустоты, и переход читался как моргание.
        // Теперь общая поверхность остаётся на месте, меняет содержимое и
        // подгоняет под него размер — меню разворачивается в лист.
        val panel: CompanionPanel = when {
            aiSheet != null -> CompanionPanel.Sheet
            menuOpen -> CompanionPanel.Menu
            else -> CompanionPanel.None
        }
        val shownPanel = rememberLastNotNull(panel.takeIf { it != CompanionPanel.None })
        AnimatedVisibility(
            visible = panel != CompanionPanel.None && !suppressed,
            enter = fadeIn(motion.paced(motion.quick)) +
                scaleIn(motion.settling(), initialScale = 0.94f, transformOrigin = PanelOrigin),
            exit = fadeOut(motion.paced(motion.instant)) +
                scaleOut(motion.paced(motion.quick), targetScale = 0.94f, transformOrigin = PanelOrigin),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            // Панель стоит в левом нижнем углу, а компаньон - в правом.
            //
            // Раньше она открывалась поверх него: собеседник исчезал ровно в
            // тот момент, когда с ним заговаривали, и панель упиралась в его
            // же ширину. Теперь они делят низ экрана - фигура остаётся видна,
            // а панели достаётся вся ширина, кроме отведённой ему полосы.
            //
            // Потолок в три четверти экрана и прокрутка целиком - тот же
            // приём, что у листа сюжета и панели настроек. Мнение на три
            // абзаца с деталями иначе уезжало за экран вместе с «Закрыть»,
            // а закрыть лист больше нечем: тап мимо он не ловит.
            BoxWithConstraints {
                val cap = maxHeight * 0.75f
                // Ширина берётся от экрана, а не назначается числом: на
                // телефоне это почти вся полоса, на планшете - предел, за
                // которым строка становится слишком длинной для чтения.
                val reserved = if (compact) FIGURE_LANE_COMPACT else FIGURE_LANE_WIDE
                val room = (maxWidth - reserved).coerceAtLeast(MIN_PANEL_WIDTH)
                val width = minOf(room, MAX_PANEL_WIDTH)
                // Угол, обращённый к персонажу, оставлен прямым: панель
                // прирастает к нему, а не висит рядом самостоятельной
                // карточкой. Три скруглённых угла и один прямой — тот же
                // приём, что у ярлычка у края страницы.
                val slip = RoundedCornerShape(
                    topStart = spacing.medium,
                    topEnd = spacing.medium,
                    bottomEnd = 0.dp,
                    bottomStart = spacing.medium,
                )
                Column(
                    Modifier
                        .widthIn(min = minOf(width, room), max = width)
                        .heightIn(max = cap)
                        .clip(slip)
                        .background(colors.paper)
                        .border(1.dp, colors.rule, slip)
                        .animateContentSize(motion.settling())
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.large),
                    verticalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    AnimatedContent(
                        targetState = shownPanel,
                        transitionSpec = {
                            fadeIn(motion.paced(motion.quick)) togetherWith
                                fadeOut(motion.paced(motion.instant))
                        },
                        label = "companion-panel",
                    ) { face ->
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                            when (face) {
                                CompanionPanel.Menu -> CompanionMenu(
                                    name = profile.name,
                                    description = persona.description,
                                    reactionsEnabled = profile.reactionsEnabled,
                                    onOpinion = { menuOpen = false; runOpinion() },
                                    onAsk = {
                                        menuOpen = false
                                        questionDraft = ""
                                        aiSheet = AiSheetState.Asking
                                    },
                                    onRecap = { menuOpen = false; runRecap() },
                                    onCheerUp = { saySomething("difficult_page", SparkKind.Cheer) },
                                    onHowIsItGoing = { saySomething("steady_reading", SparkKind.Cheer) },
                                    onSaySomething = { saySomething(CHATTER.random(), SparkKind.Cheer) },
                                    onToggleReactions = {
                                        onProfileChange(profile.copy(reactionsEnabled = !profile.reactionsEnabled))
                                        menuOpen = false
                                    },
                                    onEdit = { menuOpen = false; onEditCompanion() },
                                    onHide = {
                                        menuOpen = false
                                        bubble = null
                                        revealed = false
                                    },
                                )
                                CompanionPanel.Sheet, null -> CompanionSheet(
                                    sheet = lastSheet,
                                    questionDraft = questionDraft,
                                    onQuestionDraft = { questionDraft = it },
                                    onAllowAi = {
                                        onProfileChange(profile.copy(aiConsentAt = currentTimeMillis()))
                                        val pending = pendingAiAction
                                        pendingAiAction = null
                                        aiSheet = null
                                        when (pending) {
                                            PendingAiAction.Opinion -> runOpinion(consentGranted = true)
                                            PendingAiAction.Question -> runQuestion(consentGranted = true)
                                            PendingAiAction.Recap -> runRecap(consentGranted = true)
                                            null -> Unit
                                        }
                                    },
                                    onDeclineAi = {
                                        pendingAiAction = null
                                        closeAiSheet()
                                    },
                                    onAsk = { menuOpen = false; runQuestion() },
                                    onRetry = { action ->
                                        closeAiSheet()
                                        when (action) {
                                            PendingAiAction.Opinion -> runOpinion(consentGranted = true)
                                            PendingAiAction.Question -> runQuestion(consentGranted = true)
                                            PendingAiAction.Recap -> runRecap(consentGranted = true)
                                        }
                                    },
                                    onOpenPrivacy = { uriHandler.openUri(PRIVACY_URL) },
                                    onClose = { closeAiSheet() },
                                )
                                CompanionPanel.None -> Unit
                            }
                        }
                    }
                }
            }
        }

        // Закрытый компаньон оставляет только узкий ярлычок. Он принимает и
        // протяжку влево на сенсорном экране, и обычный клик мышью.
        AnimatedVisibility(
            visible = !suppressed && !revealed && !menuOpen && aiSheet == null,
            enter = ribbonEnter,
            exit = ribbonExit,
        ) {
            // Пройденное расстояние жеста — обычная переменная, а не состояние:
            // его читает только сам обработчик, а снимок состояния на каждом
            // кадре протяжки не нужен никому.
            val dragged = remember { DragDistance() }

            // Один намёк за сессию, и только если компаньона ещё ни разу не
            // открывали. Ярлычок отходит влево и возвращается — показывает
            // жест, которым его открывают. Без этого протяжку не находил
            // никто: зрячий читатель видит плашку и нажимает её, а про
            // «потяните влево» знала только озвучка экрана.
            //
            // Ровно один раз, а не циклом: повторяющееся движение у края
            // страницы — это уже не подсказка, а мигающая реклама самой себя.
            val nudge = remember { Animatable(0f) }
            LaunchedEffect(profile.id) {
                if (hintShown || motion.still) return@LaunchedEffect
                delay(HINT_DELAY_MILLIS)
                if (revealed) return@LaunchedEffect
                hintShown = true
                nudge.animateTo(-1f, motion.paced(motion.calm))
                nudge.animateTo(0f, motion.settling(stiffness = Spring.StiffnessLow))
            }
            val nudgeShift = with(LocalDensity.current) { HINT_TRAVEL.toPx() }
            // Ярлычок остаётся узким — это газетная закладка, а не кнопка. Но
            // палец в тридцать точек не попадает, поэтому область нажатия
            // шире рисунка: сам ярлычок прижат к краю, ловит жест вся полоса.
            Box(
                Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .graphicsLayer { translationX = nudge.value * nudgeShift }
                    .semantics { contentDescription = "Потяните влево, чтобы открыть компаньона" }
                    .pointerInput(profile.id) {
                        val threshold = 22.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { dragged.value = 0f },
                            onDragCancel = { dragged.value = 0f },
                            onDragEnd = {
                                if (dragged.value <= -threshold) revealCompanion()
                                dragged.value = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            dragged.value += amount
                            if (dragged.value <= -threshold) revealCompanion()
                        }
                    }
                    .pressable { revealCompanion() },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .size(width = 30.dp, height = 68.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                        .background(colors.accent)
                        .border(1.dp, colors.rule, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", style = WolfyTheme.typography.bookTitle, color = colors.paper)
                }
            }
        }

        // Лампочка загорается, когда ответ готов. Она стоит над фигурой и
        // живёт своей жизнью: пропустивший её ничего не теряет, а заметивший
        // понимает, что панель уже можно открывать.
        //
        // Тем же переходом персонаж показывает, чем занят: пока ответ едет, он
        // думает, а получив — говорит. Крутящийся индикатор об этом сообщает
        // не хуже, но он сообщает о сервере, а разговаривают тут не с сервером.
        LaunchedEffect(aiSheet) {
            when (aiSheet) {
                is AiSheetState.Loading -> play(CompanionMotion.Think.code)
                is AiSheetState.OpinionReady, is AiSheetState.QuestionReady -> {
                    sparkKind = SparkKind.Idea
                    sparkTrigger += 1
                    play(CompanionMotion.Speak.code)
                }
                else -> Unit
            }
        }
        val figureSize = if (compact) FIGURE_COMPACT else FIGURE_WIDE
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = figureSize * 0.82f, end = figureSize * 0.5f),
        ) {
            CompanionSpark(trigger = sparkTrigger, kind = sparkKind)
        }

        // Полная фигура появляется только после осознанного действия. Тап по
        // ней открывает меню, а прокрутка или отдельная команда снова прячут.
        AnimatedVisibility(
            // Персонаж остаётся на экране и при открытой панели.
            //
            // Полоса под него слева от панели отводилась всегда — ради того,
            // чтобы «фигура осталась видна», как сказано там же в коде, — но
            // видимость его гасила ровно на `menuOpen`. Читатель нажимал на
            // собеседника, собеседник исчезал, и разговаривать оставалось со
            // списком строк в пустой рамке. Ради этого списка отдельный
            // редактор внешности и характера не заводят.
            visible = revealed && !suppressed,
            // Только прозрачность: slide-контейнер обрезал волосы и одежду
            // своим промежуточным квадратом во время появления.
            enter = fadeIn(motion.paced(motion.quick)),
            exit = fadeOut(motion.paced(motion.instant)),
        ) {
            // Появление — тоже реплика: персонаж не возникает готовым, а
            // выглядывает из-за края, откуда его вытянули за ярлычок.
            LaunchedEffect(Unit) { play(CompanionMotion.Peek.code) }
            CompanionFigure(
                appearance = profile.appearance,
                pose = rememberCompanionPose(
                    gesture = gesture,
                    trigger = gestureTrigger,
                    // Зерно от профиля: два компаньона на одном экране не
                    // должны моргать в такт.
                    seed = profile.id.hashCode(),
                ),
                modifier = Modifier
                    .size(figureSize)
                    .semantics { contentDescription = "Компаньон ${profile.name}. Нажмите, чтобы открыть действия" }
                    .pressable(enabled = true) {
                        // Переключатель, а не выключатель: панель теперь
                        // открывается рядом с персонажем, и нажать на него
                        // второй раз — самый очевидный способ её закрыть.
                        if (menuOpen) {
                            menuOpen = false
                        } else {
                            menuOpen = true
                            bubble = null
                        }
                    },
            )
        }
    }
}

/**
 * Последнее непустое значение.
 *
 * `AnimatedVisibility` продолжает рисовать содержимое, пока идёт анимация
 * ухода, а состояние к этому моменту уже обнулено. Без этой памяти наружу
 * уезжала пустая рамка: содержимое исчезало мгновенно, а коробка вокруг него
 * гасла плавно.
 */
@Composable
private fun <T : Any> rememberLastNotNull(value: T?): T? {
    val holder = remember { mutableStateOf<T?>(null) }
    if (value != null) holder.value = value
    return holder.value
}

/** Два лица одной панели компаньона. */
private enum class CompanionPanel { None, Menu, Sheet }

/**
 * Панель растёт из угла, где живёт персонаж, а не из своего центра.
 *
 * Персонаж стоит справа внизу, панель раскладывается слева от него — значит
 * ближний к нему угол правый нижний, (1, 1). Стояло (0, 1): панель уезжала
 * из дальнего от собеседника угла, то есть выглядела не ответом персонажа, а
 * всплывшим окном рядом.
 */
private val PanelOrigin = TransformOrigin(1f, 1f)

/**
 * Меню действий.
 *
 * ## Почему у списка появилась голова и разделы
 *
 * Раньше это были девять одинаковых строк подряд: разговор, ИИ-запросы и
 * хозяйственные команды одним кеглем и одним цветом. Из такого списка не
 * следует ни с кем говорят, ни что из этого стоит запроса, ни что «Спрятать
 * компаньона» — не реплика.
 *
 * Теперь наверху имя и характер: панель раскрывается рядом с персонажем и
 * говорит от его лица, а не от лица приложения. Дальше два раздела с
 * подписями — то, что идёт к модели, и то, что живёт на устройстве, — и внизу
 * хозяйство, набранное тише всего остального. Пометку Beta несёт подпись
 * раздела, а не каждая строка: три «· Beta» подряд читались как часть реплики.
 */
@Composable
private fun CompanionMenu(
    name: String,
    description: String,
    reactionsEnabled: Boolean,
    onOpinion: () -> Unit,
    onAsk: () -> Unit,
    onRecap: () -> Unit,
    onCheerUp: () -> Unit,
    onHowIsItGoing: () -> Unit,
    onSaySomething: () -> Unit,
    onToggleReactions: () -> Unit,
    onEdit: () -> Unit,
    onHide: () -> Unit,
) {
    val spacing = WolfyTheme.spacing
    val colors = WolfyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            name.ifBlank { "Компаньон" },
            style = WolfyTheme.typography.bookTitle,
            color = colors.ink,
        )
        if (description.isNotBlank()) {
            Text(
                description,
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
                maxLines = 2,
            )
        }
        SectionLabel("СПРОСИТЬ · BETA", Modifier.padding(top = spacing.small))
        ActionRow("Что думаешь об этой странице?", onOpinion)
        ActionRow("Задать вопрос о книге", onAsk)
        ActionRow("Вспомнить сюжет", onRecap)
        // Ниже подписи - то, что не ходит в сеть и не тратит дневные запросы.
        // Подпись здесь не украшение: она и есть обещание, что за этими тремя
        // строками ничего не спишется.
        SectionLabel("ПРОСТО ТАК", Modifier.padding(top = spacing.small))
        ActionRow("Подбодри меня", onCheerUp)
        ActionRow("Как я читаю?", onHowIsItGoing)
        ActionRow("Скажи что-нибудь", onSaySomething)
        Rule(modifier = Modifier.padding(vertical = spacing.small))
        QuietRow(if (reactionsEnabled) "Помолчи пока" else "Включить реплики", onToggleReactions)
        QuietRow("Изменить компаньона", onEdit)
        QuietRow("Спрятать компаньона", onHide)
        Text(
            "ИИ может ошибаться.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
            modifier = Modifier.padding(top = spacing.small),
        )
    }
}

/** Лист согласия, ожидания, ответа или отказа. */
@Composable
private fun CompanionSheet(
    sheet: AiSheetState?,
    questionDraft: String,
    onQuestionDraft: (String) -> Unit,
    onAllowAi: () -> Unit,
    onDeclineAi: () -> Unit,
    onAsk: () -> Unit,
    onRetry: (PendingAiAction) -> Unit,
    onOpenPrivacy: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        when (sheet) {
            is AiSheetState.Consent -> {
                Text("Передать фрагмент ИИ?", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                Text(
                    "Фрагмент текущей или недавно прочитанной части книги будет отправлен серверному ИИ-провайдеру. Согласие можно отозвать в разделе компаньона.",
                    style = WolfyTheme.typography.body,
                    color = colors.ink,
                )
                SheetAction("Политика приватности", onOpenPrivacy, quiet = true)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    SheetAction("Разрешить", onAllowAi)
                    SheetAction("Не сейчас", onDeclineAi, quiet = true)
                }
            }

            is AiSheetState.Loading -> ThinkingRow(onClose)

            is AiSheetState.OpinionReady -> {
                Text(sheet.value.title, style = WolfyTheme.typography.bookTitle, color = colors.ink)
                Text(sheet.value.opinion, style = WolfyTheme.typography.body, color = colors.ink)
                for (detail in sheet.value.details) {
                    Aside(detail.label, detail.text)
                }
                sheet.value.uncertainty?.let { Text(it, style = WolfyTheme.typography.caption, color = colors.inkMuted) }
                RemainingLine(sheet.value.remaining, sheet.value.cached)
                SheetAction("Закрыть", onClose)
            }

            is AiSheetState.QuestionReady -> {
                Text("Ответ", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                Text(sheet.value.answer, style = WolfyTheme.typography.body, color = colors.ink)
                for (evidence in sheet.value.evidence) {
                    Aside(evidence.hint, evidence.text)
                }
                sheet.value.uncertainty?.let { Text(it, style = WolfyTheme.typography.caption, color = colors.inkMuted) }
                RemainingLine(sheet.value.remaining, sheet.value.cached)
                SheetAction("Закрыть", onClose)
            }

            is AiSheetState.Failed -> {
                // Отказ говорит персонаж, а не пустая строка. Раньше рядом с
                // сообщением рисовали его же маленькую копию — тогда сам он на
                // время разговора исчезал, и без копии говорить было некому.
                // Теперь он стоит рядом с панелью живьём, и вторая фигура в
                // полсотни точек была бы просто вторым таким же лицом на
                // экране.
                Text(sheet.message, style = WolfyTheme.typography.body, color = colors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    if (sheet.retryable) SheetAction("Повторить", onClick = { onRetry(sheet.action) })
                    SheetAction("Закрыть", onClose, quiet = true)
                }
            }

            is AiSheetState.Asking -> {
                Text("Вопрос о прочитанном", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                TextField(
                    value = questionDraft,
                    onValueChange = { onQuestionDraft(it.take(500)) },
                    placeholder = { Text("Что уже случилось в книге?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(
                        "Спросить",
                        enabled = questionDraft.trim().length in 3..500,
                        onClick = onAsk,
                        modifier = Modifier.weight(1f),
                    )
                    SheetAction("Отмена", onClose, quiet = true)
                }
            }

            null -> Unit
        }
    }
}

/**
 * Подпись и текст под ответом: деталь мнения или ссылка на место в книге.
 *
 * Раньше это была одна строка «подпись: текст» мелким серым — тем же кеглем и
 * цветом, что «ИИ может ошибаться» рядом. Читатель не отличал цитату из своей
 * книги от служебной оговорки. Подпись ушла в капслок, как в разборе слова, а
 * текст остался текстом.
 */
@Composable
private fun Aside(label: String, text: String) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.hair)) {
        SectionLabel(label.uppercase())
        Text(text, style = WolfyTheme.typography.body, color = WolfyTheme.colors.inkMuted)
    }
}

/** Остаток дневной квоты — служебная строка, а не действие. */
@Composable
private fun RemainingLine(remaining: Int, cached: Boolean = false) {
    Text(
        if (cached) "Ответ сохранён в памяти компаньона." else "Осталось запросов сегодня: $remaining",
        style = WolfyTheme.typography.caption,
        color = WolfyTheme.colors.inkMuted,
    )
}

/**
 * Действие в листе.
 *
 * Кеглем `button`, а не `caption`. Раньше «Закрыть» и «Повторить» набирались
 * тем же кеглем, что «ИИ может ошибаться» рядом, и отличались только цветом:
 * читатель не видел разницы между тем, что читают, и тем, что нажимают.
 */
@Composable
private fun SheetAction(label: String, onClick: () -> Unit, quiet: Boolean = false) {
    Text(
        label,
        style = WolfyTheme.typography.button,
        color = if (quiet) WolfyTheme.colors.inkMuted else WolfyTheme.colors.accent,
        modifier = Modifier
            .pressable(onClick = onClick)
            .padding(vertical = WolfyTheme.spacing.small),
    )
}

/**
 * Ожидание ответа модели.
 *
 * Две вещи, которых здесь не было. Полоса набора — чтобы экран показывал, что
 * занят. И честная смена подписи на восьмой секунде: ожидание в сорок секунд
 * нормально для бесплатной модели, но молчащий экран об этом не сообщает, и
 * читатель отменяет запрос, которому оставалось пять секунд. Отмена при этом
 * стоила ему запроса из дневных десяти.
 */
@Composable
private fun ThinkingRow(onCancel: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    var waitedLong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LONG_WAIT_MILLIS)
        waitedLong = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        Text(
            if (waitedLong) "Модель думает дольше обычного. Ещё немного." else "Думаю над страницей…",
            style = WolfyTheme.typography.body,
            color = colors.inkMuted,
        )
        TypesettingLine()
        SheetAction("Отменить", onCancel, quiet = true)
    }
}

/** Столько ждём, прежде чем признать ожидание долгим. */
private const val LONG_WAIT_MILLIS = 8_000L

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = WolfyTheme.typography.body,
        color = WolfyTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/**
 * Хозяйственная строка: спрятать, переделать, помолчать.
 *
 * Тише разговора и по кеглю, и по цвету. Это не то, что компаньону говорят, а
 * то, что делают с ним самим, и одинаковый набор с репликами каждый раз
 * заставлял перечитывать список целиком.
 */
@Composable
private fun QuietRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = WolfyTheme.typography.caption,
        color = WolfyTheme.colors.inkMuted,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** Состояния листа ИИ-действия: загрузка, готовые ответы, вопрос, ошибка. */
private sealed interface AiSheetState {
    data object Consent : AiSheetState
    data object Loading : AiSheetState
    data object Asking : AiSheetState
    data class OpinionReady(val value: CompanionOpinion) : AiSheetState
    data class QuestionReady(val value: CompanionQuestion) : AiSheetState
    /**
     * Ошибка запроса.
     *
     * Повтор описывается действием, а не готовым замыканием. Замыкание
     * захватывало текст страницы того прохода композиции, в котором ответ
     * пришёл: читатель листал дальше, нажимал «Повторить» и тратил запрос из
     * дневных десяти на страницу, которую уже перевернул.
     */
    data class Failed(
        val message: String,
        val action: PendingAiAction,
        val retryable: Boolean,
    ) : AiSheetState
}

internal enum class PendingAiAction { Opinion, Question, Recap }

/**
 * Из чего берётся «Скажи что-нибудь».
 *
 * Сценарии настроения и течения сессии, но не начало и не конец: «ну что,
 * почитаем?» посреди главы звучит так, будто компаньон не заметил, что её уже
 * читают.
 */
private val CHATTER = listOf(
    "steady_reading",
    "long_session",
    "mood_joy",
    "mood_mystery",
    "mood_tension",
    "page_completed",
)

/**
 * Рост компаньона в читалке.
 *
 * Прежние 54 точки на телефоне — это размер значка, а не собеседника. Лицо в
 * такой фигуре занимало около двадцати точек: мимики на нём не видно, глаз не
 * видно, и любая анимация пропадала вместе с ними. Персонаж, ради которого
 * заведён отдельный редактор с характером и внешностью, обязан быть по крайней
 * мере узнаваем.
 *
 * Больше делать нельзя: фигура стоит поверх страницы, и её место в углу
 * ограничено снизу нижней навигацией, а слева — панелью разговора.
 */
private val FIGURE_COMPACT = 108.dp
private val FIGURE_WIDE = 144.dp

/**
 * Полоса, отведённая фигуре компаньона у правого края.
 *
 * Панель раскрывается от левого края и упирается в эту полосу, а не в
 * собеседника: разговаривать с тем, кого закрыло окно разговора, странно.
 */
private val FIGURE_LANE_COMPACT = FIGURE_COMPACT + 12.dp
private val FIGURE_LANE_WIDE = FIGURE_WIDE + 24.dp

/** Уже этого панель не имеет смысла: строка станет в два слова. */
private val MIN_PANEL_WIDTH = 240.dp

/**
 * Шире этого - тоже. Длинная строка читается хуже короткой, и на планшете
 * панель во всю ширину была бы не щедростью, а неудобством.
 */
private val MAX_PANEL_WIDTH = 460.dp

/** Сколько ждём, прежде чем показать, как открывается компаньон. */
private const val HINT_DELAY_MILLIS = 4_000L

/** На сколько ярлычок отходит влево в подсказке. */
private val HINT_TRAVEL = 8.dp

/** Накопленное расстояние жеста вне снимка состояния. */
private class DragDistance(var value: Float = 0f)

private const val PRIVACY_URL = "https://wolfy.citavuk.ru/privacy"
