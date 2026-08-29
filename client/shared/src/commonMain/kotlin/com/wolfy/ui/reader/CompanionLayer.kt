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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import com.wolfy.data.companion.CompanionReactionEngine
import com.wolfy.data.companion.CompanionRepository
import com.wolfy.data.companion.FallbackPhrases
import com.wolfy.data.companion.MoodScorer
import com.wolfy.data.companion.PHRASE_COUNT
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.platform.CompanionSound
import com.wolfy.platform.playCompanionSound
import com.wolfy.theme.Curves
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.paced
import com.wolfy.theme.settling
import com.wolfy.theme.still
import com.wolfy.ui.companion.CompanionFigure
import com.wolfy.widgets.PrimaryButton
import com.wolfy.widgets.Rule
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
    // Намёк про протяжку показывается один раз за сессию чтения.
    var hintShown by remember(profile.id) { mutableStateOf(false) }
    // Начало сессии чтения: по нему считаются настоящие минуты для реплик.
    var sessionStartedAt by remember(profile.id) { mutableStateOf(currentTimeMillis()) }
    val uriHandler = LocalUriHandler.current
    val motion = WolfyTheme.motion

    // Покачивание заводится только если движение вообще разрешено. Раньше при
    // выключенном движении обе анимации шли из нуля в ноль: персонаж стоял на
    // вид, а бесконечный переход продолжал считать кадры до закрытия книги.
    // Читатель просил тишины, а получал её только глазами, но не батареей.
    var bob = 0f
    var tilt = 0f
    if (!motion.still) {
        val idleMotion = rememberInfiniteTransition(label = "companion-idle")
        bob = idleMotion.animateFloat(
            initialValue = 0f,
            targetValue = -3f,
            animationSpec = infiniteRepeatable(tween(motion.flight * 2, easing = Curves.Paper), RepeatMode.Reverse),
            label = "companion-bob",
        ).value
        tilt = idleMotion.animateFloat(
            initialValue = -0.7f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(motion.flight * 3, easing = Curves.Paper), RepeatMode.Reverse),
            label = "companion-tilt",
        ).value
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
        ).phrase?.let { bubble = it.text }
    }

    // Завершение главы: событие только при реальном переходе вперёд. При
    // первом открытии книги поздравление показывать нельзя.
    var previousChapter by remember(profile.id, bookId) { mutableStateOf(chapterKey) }
    LaunchedEffect(chapterKey) {
        if (chapterKey > previousChapter) {
            engine.decide(
                CompanionReactionEngine.Event.ChapterCompleted,
                CompanionReactionEngine.Context(0, overlayOpen = false, scrolling = false, reactionsEnabled = reactionsAllowed),
            ).phrase?.let { bubble = it.text }
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
        engine.decide(CompanionReactionEngine.Event.PageCompleted, context).phrase?.let { bubble = it.text }
        if (rests % 8 == 0 && bubble == null) {
            val mood = MoodScorer.analyze(pageText())
            if (mood.mood != MoodScorer.NEUTRAL) {
                engine.decide(CompanionReactionEngine.Event.Mood(mood.mood), context).phrase?.let { bubble = it.text }
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
        aiSheet = AiSheetState.Loading
        aiJob = scope.launch {
            val result = api.companionOpinion(bookId, bookTitle, chapter, offset(), pageText(), persona)
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
            val result = api.companionQuestion(bookId, bookTitle, chapter, offset(), question, history, persona)
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
                .padding(bottom = if (compact) 62.dp else 106.dp),
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
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            // Потолок в три четверти экрана и прокрутка целиком — тот же
            // приём, что у листа сюжета и панели настроек. Мнение на три
            // абзаца с деталями иначе уезжало за экран вместе с «Закрыть»,
            // а закрыть лист больше нечем: тап мимо он не ловит.
            BoxWithConstraints {
                val cap = maxHeight * 0.75f
                Column(
                    Modifier
                        .widthIn(max = if (compact) 300.dp else 340.dp)
                        .heightIn(max = cap)
                        .clip(RoundedCornerShape(spacing.medium))
                        .background(colors.paper)
                        .border(1.dp, colors.rule, RoundedCornerShape(spacing.medium))
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
                                    reactionsEnabled = profile.reactionsEnabled,
                                    onOpinion = { menuOpen = false; runOpinion() },
                                    onAsk = {
                                        menuOpen = false
                                        questionDraft = ""
                                        aiSheet = AiSheetState.Asking
                                    },
                                    onRecap = { menuOpen = false; runRecap() },
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
                                    appearance = profile.appearance,
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

        // Полная фигура появляется только после осознанного действия. Тап по
        // ней открывает меню, а прокрутка или отдельная команда снова прячут.
        AnimatedVisibility(
            visible = revealed && !suppressed && !menuOpen && aiSheet == null,
            // Только прозрачность: slide-контейнер обрезал волосы и одежду
            // своим промежуточным квадратом во время появления.
            enter = fadeIn(motion.paced(motion.quick)),
            exit = fadeOut(motion.paced(motion.instant)),
        ) {
            CompanionFigure(
                appearance = profile.appearance,
                modifier = Modifier
                    .size(if (compact) 54.dp else 96.dp)
                    .graphicsLayer {
                        translationY = bob
                        rotationZ = tilt
                        clip = false
                    }
                    .semantics { contentDescription = "Компаньон ${profile.name}. Нажмите, чтобы открыть действия" }
                    .pressable(enabled = true) {
                        menuOpen = true
                        bubble = null
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

/** Панель растёт из угла, где живёт персонаж, а не из своего центра. */
private val PanelOrigin = TransformOrigin(1f, 1f)

/**
 * Меню действий.
 *
 * Предупреждение о Beta стоит один раз под действиями, а не под каждой кнопкой.
 */
@Composable
private fun CompanionMenu(
    reactionsEnabled: Boolean,
    onOpinion: () -> Unit,
    onAsk: () -> Unit,
    onRecap: () -> Unit,
    onToggleReactions: () -> Unit,
    onEdit: () -> Unit,
    onHide: () -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        ActionRow("Что думаешь об этой странице? · Beta", onOpinion)
        ActionRow("Задать вопрос о книге · Beta", onAsk)
        ActionRow("Вспомнить сюжет · Beta", onRecap)
        ActionRow(if (reactionsEnabled) "Помолчи пока" else "Включить реплики", onToggleReactions)
        ActionRow("Изменить компаньона", onEdit)
        ActionRow("Спрятать компаньона", onHide)
        Rule()
        Text(
            "ИИ может ошибаться. До 10 запросов в день.",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
    }
}

/** Лист согласия, ожидания, ответа или отказа. */
@Composable
private fun CompanionSheet(
    sheet: AiSheetState?,
    appearance: CompanionAppearance,
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
                    Text(
                        "${detail.label}: ${detail.text}",
                        style = WolfyTheme.typography.caption,
                        color = colors.inkMuted,
                    )
                }
                sheet.value.uncertainty?.let { Text(it, style = WolfyTheme.typography.caption, color = colors.inkMuted) }
                RemainingLine(sheet.value.remaining)
                SheetAction("Закрыть", onClose)
            }

            is AiSheetState.QuestionReady -> {
                Text("Ответ", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                Text(sheet.value.answer, style = WolfyTheme.typography.body, color = colors.ink)
                for (evidence in sheet.value.evidence) {
                    Text(
                        "${evidence.hint}: ${evidence.text}",
                        style = WolfyTheme.typography.caption,
                        color = colors.inkMuted,
                    )
                }
                sheet.value.uncertainty?.let { Text(it, style = WolfyTheme.typography.caption, color = colors.inkMuted) }
                RemainingLine(sheet.value.remaining)
                SheetAction("Закрыть", onClose)
            }

            is AiSheetState.Failed -> {
                // Отказ говорит персонаж, а не пустая строка. Экран, на
                // котором что-то не получилось, — единственное место, где
                // компаньон нужнее всего, и единственное, куда его не звали.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.size(width = 52.dp, height = 58.dp), contentAlignment = Alignment.Center) {
                        CompanionFigure(appearance, Modifier.fillMaxSize())
                    }
                    Text(
                        sheet.message,
                        style = WolfyTheme.typography.body,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                }
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

/** Остаток дневной квоты — служебная строка, а не действие. */
@Composable
private fun RemainingLine(remaining: Int) {
    Text(
        "Осталось запросов сегодня: $remaining",
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

/** Сколько ждём, прежде чем показать, как открывается компаньон. */
private const val HINT_DELAY_MILLIS = 4_000L

/** На сколько ярлычок отходит влево в подсказке. */
private val HINT_TRAVEL = 8.dp

/** Накопленное расстояние жеста вне снимка состояния. */
private class DragDistance(var value: Float = 0f)

private const val PRIVACY_URL = "https://wolfy.citavuk.ru/privacy"
