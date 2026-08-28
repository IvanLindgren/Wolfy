package com.wolfy.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wolfy.data.CompanionAiResult
import com.wolfy.data.CompanionOpinion
import com.wolfy.data.CompanionPersonaIn
import com.wolfy.data.CompanionQuestion
import com.wolfy.data.WolfyApi
import com.wolfy.data.companion.CompanionPhrasePack
import com.wolfy.data.companion.CompanionProfile
import com.wolfy.data.companion.CompanionReactionEngine
import com.wolfy.data.companion.CompanionRepository
import com.wolfy.data.companion.FallbackPhrases
import com.wolfy.data.companion.MoodScorer
import com.wolfy.data.companion.PHRASE_COUNT
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.theme.WolfyTheme
import com.wolfy.ui.companion.CompanionFigure
import com.wolfy.widgets.PrimaryButton
import com.wolfy.widgets.Rule
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
    suppressed: Boolean,
    scrolling: Boolean,
    compact: Boolean,
    reduceMotion: Boolean,
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
    val engine = remember(profile.id, pack.profileHash, pack.source, pack.phrases) {
        CompanionReactionEngine(pack, ::currentTimeMillis, CompanionReactionEngine.seedFor(profile.id, 0))
    }

    var bubble by remember { mutableStateOf<String?>(null) }
    var revealed by remember(profile.id, bookId) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var aiSheet by remember { mutableStateOf<AiSheetState?>(null) }
    var questionDraft by remember { mutableStateOf("") }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    var pendingAiAction by remember { mutableStateOf<PendingAiAction?>(null) }
    val uriHandler = LocalUriHandler.current

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
    LaunchedEffect(scrolling, activeBlock) {
        if (scrolling || activeBlock == restedBlock) return@LaunchedEffect
        delay(2_000)
        if (scrolling || suppressed) return@LaunchedEffect
        restedBlock = activeBlock
        rests += 1
        val context = CompanionReactionEngine.Context(
            sessionMinutes = rests / 2,
            overlayOpen = suppressed,
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

    fun runOpinion(consentGranted: Boolean = false): Unit {
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
                is CompanionAiResult.Failed -> AiSheetState.Failed(result.message, retryable = result.code != "quota", retry = ::runOpinion)
            }
        }
    }

    fun runQuestion(consentGranted: Boolean = false): Unit {
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
            val result = api.companionQuestion(bookId, bookTitle, chapter, offset(), question, pageText(), persona)
            aiSheet = when (result) {
                is CompanionAiResult.Ready -> AiSheetState.QuestionReady(result.value)
                is CompanionAiResult.Failed -> AiSheetState.Failed(result.message, retryable = result.code != "quota", retry = ::runQuestion)
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

    val ribbonEnter = if (reduceMotion) fadeIn() else fadeIn() + slideInHorizontally { it }
    val ribbonExit = if (reduceMotion) fadeOut() else fadeOut() + slideOutHorizontally { it }

    Box(modifier, contentAlignment = Alignment.BottomEnd) {
        // Пузырь. Карточка слова и любые оверлеи полностью его скрывают.
        AnimatedVisibility(
            visible = revealed && bubble != null && !suppressed && !menuOpen && aiSheet == null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = if (compact) 62.dp else 106.dp),
        ) {
            Text(
                bubble.orEmpty(),
                style = WolfyTheme.typography.caption,
                color = colors.ink,
                maxLines = 2,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(spacing.medium))
                    .background(colors.surface)
                    .border(1.dp, colors.rule, RoundedCornerShape(spacing.medium))
                    .pressable(enabled = true) {
                        menuOpen = true
                        bubble = null
                    }
                    .padding(spacing.medium),
            )
        }

        // Меню действий по тапу. Предупреждение о Beta стоит один раз под
        // действиями, а не под каждой кнопкой.
        AnimatedVisibility(
            visible = menuOpen && !suppressed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Column(
                Modifier
                    .widthIn(max = if (compact) 260.dp else 300.dp)
                    .clip(RoundedCornerShape(spacing.medium))
                    .background(colors.paper)
                    .border(1.dp, colors.rule, RoundedCornerShape(spacing.medium))
                    .padding(spacing.large),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                ActionRow("Что думаешь об этой странице? · Beta") {
                    menuOpen = false
                    runOpinion()
                }
                ActionRow("Задать вопрос о книге · Beta") {
                    menuOpen = false
                    questionDraft = ""
                    aiSheet = AiSheetState.Asking
                }
                ActionRow("Вспомнить сюжет · Beta") {
                    menuOpen = false
                    runRecap()
                }
                ActionRow(if (profile.reactionsEnabled) "Помолчи пока" else "Включить реплики") {
                    onProfileChange(profile.copy(reactionsEnabled = !profile.reactionsEnabled))
                    menuOpen = false
                }
                ActionRow("Изменить компаньона") {
                    menuOpen = false
                    onEditCompanion()
                }
                ActionRow("Спрятать компаньона") {
                    menuOpen = false
                    bubble = null
                    revealed = false
                }
                Rule()
                Text(
                    "ИИ может ошибаться. До 10 запросов в день.",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
        }

        // Лист с ответом: мнение или вопрос. Отмена и повтор всегда доступны.
        AnimatedVisibility(
            visible = aiSheet != null && !suppressed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            val sheet = aiSheet
            Column(
                Modifier
                    .widthIn(max = if (compact) 300.dp else 340.dp)
                    .clip(RoundedCornerShape(spacing.medium))
                    .background(colors.paper)
                    .border(1.dp, colors.rule, RoundedCornerShape(spacing.medium))
                    .padding(spacing.large),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                when (sheet) {
                    is AiSheetState.Consent -> {
                        Text("Передать фрагмент ИИ?", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                        Text(
                            "Фрагмент текущей или недавно прочитанной части книги будет отправлен серверному ИИ-провайдеру. Согласие можно отозвать в разделе компаньона.",
                            style = WolfyTheme.typography.body,
                            color = colors.ink,
                        )
                        Text(
                            "Политика приватности",
                            style = WolfyTheme.typography.caption,
                            color = colors.accent,
                            modifier = Modifier.pressable { uriHandler.openUri(PRIVACY_URL) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.large)) {
                            Text(
                                "Разрешить",
                                style = WolfyTheme.typography.caption,
                                color = colors.accent,
                                modifier = Modifier.pressable {
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
                            )
                            Text("Не сейчас", style = WolfyTheme.typography.caption, color = colors.inkMuted, modifier = Modifier.pressable {
                                pendingAiAction = null
                                closeAiSheet()
                            })
                        }
                    }
                    is AiSheetState.Loading -> {
                        Text("Думаю над страницей…", style = WolfyTheme.typography.body, color = colors.inkMuted)
                        Text("Отменить", style = WolfyTheme.typography.caption, color = colors.accent, modifier = Modifier.pressable { closeAiSheet() })
                    }
                    is AiSheetState.OpinionReady -> {
                        Text(sheet.value.title, style = WolfyTheme.typography.bookTitle, color = colors.ink)
                        Text(sheet.value.opinion, style = WolfyTheme.typography.body, color = colors.ink)
                        for (detail in sheet.value.details) {
                            Text("${detail.label}: ${detail.text}", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                        }
                        sheet.value.uncertainty?.let { Text(it, style = WolfyTheme.typography.caption, color = colors.inkMuted) }
                        Text("Осталось запросов сегодня: ${sheet.value.remaining}", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                        Text("Закрыть", style = WolfyTheme.typography.caption, color = colors.accent, modifier = Modifier.pressable { closeAiSheet() })
                    }
                    is AiSheetState.QuestionReady -> {
                        Text("Ответ", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                        Text(sheet.value.answer, style = WolfyTheme.typography.body, color = colors.ink)
                        for (evidence in sheet.value.evidence) {
                            Text("${evidence.hint}: ${evidence.text}", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                        }
                        sheet.value.uncertainty?.let { Text(it, style = WolfyTheme.typography.caption, color = colors.inkMuted) }
                        Text("Осталось запросов сегодня: ${sheet.value.remaining}", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                        Text("Закрыть", style = WolfyTheme.typography.caption, color = colors.accent, modifier = Modifier.pressable { closeAiSheet() })
                    }
                    is AiSheetState.Failed -> {
                        Text(sheet.message, style = WolfyTheme.typography.body, color = colors.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.large)) {
                            if (sheet.retryable) {
                                Text("Повторить", style = WolfyTheme.typography.caption, color = colors.accent, modifier = Modifier.pressable {
                                    val retry = sheet.retry
                                    closeAiSheet()
                                    retry()
                                })
                            }
                            Text("Закрыть", style = WolfyTheme.typography.caption, color = colors.inkMuted, modifier = Modifier.pressable { closeAiSheet() })
                        }
                    }
                    is AiSheetState.Asking -> {
                        Text("Вопрос о прочитанном", style = WolfyTheme.typography.bookTitle, color = colors.ink)
                        TextField(
                            value = questionDraft,
                            onValueChange = { questionDraft = it.take(500) },
                            placeholder = { Text("Что уже случилось в книге?") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.large), verticalAlignment = Alignment.CenterVertically) {
                            PrimaryButton(
                                "Спросить",
                                enabled = questionDraft.trim().length in 3..500,
                                onClick = {
                                    menuOpen = false
                                    runQuestion()
                                },
                            )
                            Text("Отмена", style = WolfyTheme.typography.caption, color = colors.inkMuted, modifier = Modifier.pressable { closeAiSheet() })
                        }
                    }
                    null -> Unit
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
            var dragged by remember { mutableStateOf(0f) }
            Box(
                Modifier
                    .size(width = 30.dp, height = 68.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(colors.accent)
                    .border(1.dp, colors.rule, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .semantics { contentDescription = "Потяните влево, чтобы открыть компаньона" }
                    .pointerInput(profile.id) {
                        val threshold = 22.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragCancel = { dragged = 0f },
                            onDragEnd = {
                                if (dragged <= -threshold) revealed = true
                                dragged = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            dragged += amount
                            if (dragged <= -threshold) revealed = true
                        }
                    }
                    .pressable { revealed = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = WolfyTheme.typography.bookTitle, color = colors.paper)
            }
        }

        // Полная фигура появляется только после осознанного действия. Тап по
        // ней открывает меню, а прокрутка или отдельная команда снова прячут.
        AnimatedVisibility(
            visible = revealed && !suppressed && !menuOpen && aiSheet == null,
            enter = ribbonEnter,
            exit = ribbonExit,
        ) {
            CompanionFigure(
                appearance = profile.appearance,
                modifier = Modifier
                    .size(if (compact) 54.dp else 96.dp)
                    .semantics { contentDescription = "Компаньон ${profile.name}. Нажмите, чтобы открыть действия" }
                    .pressable(enabled = true) {
                        menuOpen = true
                        bubble = null
                    },
            )
        }
    }
}

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
    data class Failed(val message: String, val retryable: Boolean, val retry: () -> Unit) : AiSheetState
}

private enum class PendingAiAction { Opinion, Question, Recap }

private const val PRIVACY_URL = "https://wolfy.citavuk.ru/privacy"
