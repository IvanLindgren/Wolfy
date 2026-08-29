package com.wolfy.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.data.companion.CompanionPersonality
import com.wolfy.data.companion.CompanionAppearance
import com.wolfy.data.companion.CompanionProfile
import com.wolfy.data.companion.PHRASE_COUNT
import com.wolfy.data.companion.MAX_NAME
import com.wolfy.data.companion.MAX_PRONOUNS
import com.wolfy.data.companion.takeCodePoints
import com.wolfy.theme.WolfyTheme
import com.wolfy.ui.companion.CompanionFigure
import com.wolfy.ui.companion.CompanionPalettes
import com.wolfy.ui.companion.CompanionViewModel
import com.wolfy.ui.companion.PackRequest
import com.wolfy.widgets.PrimaryButton
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.pressable

/**
 * Раздел «Компаньон».
 *
 * Три состояния: компаньона нет (посадочная), мастер создания и созданный
 * компаньон. Компаньон всегда необязателен: с посадочной можно уйти одной
 * ссылкой и никогда его не видеть.
 */
@Composable
fun CompanionScreen(
    viewModel: CompanionViewModel,
    onGeneratePack: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState())
            .padding(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Text("Компаньон", style = WolfyTheme.typography.screenTitle, color = colors.ink)

        when (viewModel.step) {
            0 -> Landing(
                onCreate = viewModel::startCreation,
                onContinue = { viewModel.skipCreation(); onBack() },
            )
            in 1..5 -> Wizard(viewModel)
            6 -> Review(viewModel, viewModel::save)
            else -> Profile(
                viewModel = viewModel,
                onGeneratePack = onGeneratePack,
                onDeleteConfirmed = {
                    viewModel.delete()
                    onDeleteConfirmed()
                },
            )
        }
    }
}

/** Посадочная: газетный заголовок, пример и два спокойных предложения. */
@Composable
private fun Landing(onCreate: () -> Unit, onContinue: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        Text(
            "Читатель, рядом с которым кто-то есть",
            style = WolfyTheme.typography.bookTitle,
            color = colors.ink,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CompanionFigure(
                appearance = LandingExample,
                modifier = Modifier.size(160.dp),
            )
        }
        Text(
            "Компаньон это персонаж, которого вы создаёте и наряжаете. Он сидит рядом со страницей, редко говорит заготовленными фразами и поддерживает, а не отвлекает.",
            style = WolfyTheme.typography.body,
            color = colors.inkMuted,
        )
        Text(
            "Чтение работает и без него: это необязательный раздел.",
            style = WolfyTheme.typography.body,
            color = colors.inkMuted,
        )
        PrimaryButton("Создать компаньона", onCreate)
        Text(
            "Продолжить без компаньона",
            style = WolfyTheme.typography.body,
            color = colors.accent,
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = onContinue)
                .padding(vertical = spacing.medium),
            textAlign = TextAlign.Center,
        )
    }
}

/** Мастер создания: короткие шаги с возвратом без потери данных. */
@Composable
private fun Wizard(viewModel: CompanionViewModel) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val stepTitles = listOf(
        "", "Имя и обращение", "Внешность", "Одежда и аксессуары",
        "Характер", "Портрет словами", "Проверка",
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        Text(stepTitles.getOrElse(viewModel.step) { "" }, style = WolfyTheme.typography.bookTitle, color = colors.ink)
        when (viewModel.step) {
            1 -> StepName(viewModel)
            2 -> StepLook(viewModel, face = true)
            3 -> StepLook(viewModel, face = false)
            4 -> StepPersonality(viewModel)
            5 -> StepWords(viewModel)
        }
        Rule()
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
            Text(
                "Назад",
                style = WolfyTheme.typography.body,
                color = colors.accent,
                modifier = Modifier
                    .pressable(onClick = viewModel::back)
                    .padding(vertical = spacing.medium),
            )
            Box(Modifier.weight(1f))
            Text(
                "Дальше",
                style = WolfyTheme.typography.body,
                color = if (viewModel.step == 1 && !viewModel.draftValid()) colors.rule else colors.accent,
                modifier = Modifier
                    .pressable(enabled = viewModel.step != 1 || viewModel.draftValid(), onClick = viewModel::next)
                    .padding(vertical = spacing.medium),
            )
        }
    }
}

@Composable
private fun StepName(viewModel: CompanionViewModel) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val draft = viewModel.state.editing ?: return
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        SectionLabel("Имя")
        androidx.compose.material3.OutlinedTextField(
            value = draft.name,
            onValueChange = { name -> viewModel.updateDraft { it.copy(name = name.takeCodePoints(MAX_NAME)) } },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Как его зовут?") },
        )
        SectionLabel("Обращение, необязательно")
        androidx.compose.material3.OutlinedTextField(
            value = draft.pronouns.orEmpty(),
            onValueChange = { pronouns -> viewModel.updateDraft { it.copy(pronouns = pronouns.takeCodePoints(MAX_PRONOUNS).ifBlank { null }) } },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Например: он, она, они, по имени") },
        )
        SectionLabel("Какой образ собрать?")
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            for ((value, label) in PRESENTATION_OPTIONS) {
                val selected = draft.presentation == value
                Text(
                    label,
                    style = WolfyTheme.typography.caption,
                    color = if (selected) colors.paper else colors.ink,
                    modifier = Modifier
                        .pressable {
                            viewModel.updateDraft {
                                it.copy(
                                    presentation = value,
                                    appearance = presentationAppearance(it.appearance, value),
                                )
                            }
                        }
                        .clip(RoundedCornerShape(spacing.huge))
                        .background(if (selected) colors.inverse else colors.surface)
                        .border(1.dp, colors.rule, RoundedCornerShape(spacing.huge))
                        .padding(horizontal = spacing.medium, vertical = 8.dp),
                )
            }
        }
        Text("Это только стартовый вид. Дальше любую причёску, одежду и обращение можно выбрать независимо.", style = WolfyTheme.typography.caption, color = colors.inkMuted)
    }
}

/** Шаги внешности: лицо и одежда листаются одинаково, разница только в слотах. */
@Composable
private fun StepLook(viewModel: CompanionViewModel, face: Boolean) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val slots = if (face) listOf("hair", "brows", "eyes", "nose", "mouth", "beard") else listOf("body", "accessoryFront")
    val assetsBySlot = rememberCompanionCatalog()
    val draft = viewModel.state.editing ?: return
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        CompanionFigure(
            appearance = draft.appearance,
            modifier = Modifier
                .size(160.dp)
                .align(Alignment.CenterHorizontally),
        )
        for (slot in slots) {
            SectionLabel(CompanionCatalog.slotTitle(slot))
            val options = assetsBySlot[slot].orEmpty().map { it.id }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                items(options, key = { it }) { assetId ->
                    val selected = draft.appearance.asset(slot) == assetId
                    // Превью пересобирается только при смене внешности, а не на
                    // каждой рекомпозиции ряда.
                    val preview = remember(draft.appearance, slot, assetId) {
                        draft.appearance.withAsset(slot, assetId)
                    }
                    Box(
                        Modifier
                            .width(82.dp)
                            .height(92.dp)
                            .pressable(enabled = true) {
                                viewModel.updateDraft { draft ->
                                    draft.copy(appearance = draft.appearance.withAsset(slot, assetId))
                                }
                            }
                            .clip(RoundedCornerShape(spacing.medium))
                            .background(colors.surface)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) colors.accent else colors.rule,
                                shape = RoundedCornerShape(spacing.medium),
                            )
                            .semantics { contentDescription = CompanionCatalog.label(assetId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        CompanionFigure(
                            appearance = preview,
                            modifier = Modifier.size(76.dp),
                        )
                        if (assetId.endsWith(".none")) {
                            Text("Нет", style = WolfyTheme.typography.caption, color = colors.inkMuted)
                        }
                    }
                }
            }
        }
        if (face) {
            SectionLabel("Кожа")
            PaletteRow(CompanionPalettes.skinOptions, viewModel.state.editing?.appearance?.skin.orEmpty()) { name ->
                viewModel.updateDraft { it.copy(appearance = it.appearance.copy(skin = name)) }
            }
            SectionLabel("Волосы")
            PaletteRow(CompanionPalettes.hairOptions, viewModel.state.editing?.appearance?.hairColor.orEmpty()) { name ->
                viewModel.updateDraft { it.copy(appearance = it.appearance.copy(hairColor = name)) }
            }
        } else {
            SectionLabel("Одежда")
            PaletteRow(CompanionPalettes.outfitOptions, viewModel.state.editing?.appearance?.outfitColor.orEmpty()) { name ->
                viewModel.updateDraft { it.copy(appearance = it.appearance.copy(outfitColor = name)) }
            }
            SectionLabel("Акцент")
            PaletteRow(CompanionPalettes.accentOptions, viewModel.state.editing?.appearance?.accentColor.orEmpty()) { name ->
                viewModel.updateDraft { it.copy(appearance = it.appearance.copy(accentColor = name)) }
            }
        }
    }
}

private val PRESENTATION_OPTIONS = listOf(
    "masculine" to "Мужской",
    "feminine" to "Женский",
    "neutral" to "Нейтральный",
)

/** Стартовые варианты не закрывают ни один предмет в следующих шагах. */
private fun presentationAppearance(current: CompanionAppearance, presentation: String): CompanionAppearance = when (presentation) {
    "masculine" -> current.copy(hair = "hair.11", brows = "brows.04", eyes = "eyes.17", mouth = "mouth.01", beard = "beard.none", body = "body.20")
    "feminine" -> current.copy(hair = "hair.01", brows = "brows.02", eyes = "eyes.17", mouth = "mouth.01", beard = "beard.none", body = "body.17")
    else -> current.copy(hair = "hair.23", brows = "brows.01", eyes = "eyes.16", mouth = "mouth.02", beard = "beard.none", body = "body.22")
}

@Composable
private fun PaletteRow(options: List<Pair<String, androidx.compose.ui.graphics.Color>>, selected: String, onSelect: (String) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
        items(options) { (name, color) ->
            val isSelected = name == selected
            // `pressable`, а не `clickable`: у второго остаётся материальный
            // ripple, отключённый во всём остальном приложении.
            Box(
                Modifier
                    .size(48.dp)
                    .pressable { onSelect(name) }
                    .clip(RoundedCornerShape(spacing.medium))
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) colors.accent else colors.rule,
                        shape = RoundedCornerShape(spacing.medium),
                    )
                    .semantics { contentDescription = name },
            )
        }
    }
}

/** Десять шкал: два полюса, без чисел, всё на местном языке. */
@Composable
private fun StepPersonality(viewModel: CompanionViewModel) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val draft = viewModel.state.editing ?: return
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        for (key in CompanionPersonality.KEYS) {
            val scale = SCALES[key] ?: continue
            PersonalityScale(
                scale = scale,
                value = draft.personality.get(key),
                onValue = { viewModel.setPersonality(key, it) },
                onCommit = viewModel::commitPersonality,
            )
        }
    }
}

/**
 * Одна шкала характера.
 *
 * Значение во время перетаскивания живёт здесь и в черновике в памяти, а на
 * диск уходит один раз по отпусканию пальца. Раньше каждый кадр протяжки
 * сериализовал профиль целиком (вместе с набором из ста реплик) и делал
 * fsync на потоке интерфейса: одна шкала стоила примерно шестидесяти
 * синхронных записей в секунду.
 */
@Composable
private fun PersonalityScale(
    scale: Scale,
    value: Int,
    onValue: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    val colors = WolfyTheme.colors
    Column {
        Text(scale.title, style = WolfyTheme.typography.sectionLabel, color = colors.ink)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt()) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..100f,
            modifier = Modifier.semantics {
                contentDescription = "${scale.title}: от «${scale.low}» до «${scale.high}»"
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(scale.low, style = WolfyTheme.typography.caption, color = colors.inkMuted)
            Text(scale.high, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        }
    }
}

@Composable
private fun StepWords(viewModel: CompanionViewModel) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val draft = viewModel.state.editing ?: return
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        SectionLabel("MBTI, необязательно")
        Text("Подсказка стиля, а не диагноз.", style = WolfyTheme.typography.caption, color = colors.inkMuted)
        for (row in viewModel.mbtiOptions().chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                for (code in row) {
                    val selected = draft.mbti == code
                    Text(
                        code,
                        style = WolfyTheme.typography.caption,
                        color = if (selected) colors.paper else colors.ink,
                        modifier = Modifier
                            .pressable(enabled = true) {
                                viewModel.updateDraft { it.copy(mbti = if (selected) null else code) }
                            }
                            .clip(RoundedCornerShape(spacing.huge))
                            .background(if (selected) colors.inverse else colors.surface)
                            .border(1.dp, colors.rule, RoundedCornerShape(spacing.huge))
                            .padding(horizontal = spacing.medium, vertical = 8.dp),
                    )
                }
            }
        }
        Rule()
        SectionLabel("Описание, необязательно")
        androidx.compose.material3.OutlinedTextField(
            value = draft.description,
            onValueChange = { text ->
                viewModel.updateDraft { it.copy(description = text.takeCodePoints(viewModel.descriptionLimit())) }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Как он говорит? Что ему нравится?") },
            minLines = 3,
        )
        Text(
            "${viewModel.descriptionLength()} из ${viewModel.descriptionLimit()}",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )
    }
}

/** Предпросмотр перед созданием: один запрос сделает набор реплик. */
@Composable
private fun Review(viewModel: CompanionViewModel, onSave: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val draft = viewModel.state.editing ?: return
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompanionFigure(appearance = draft.appearance, modifier = Modifier.size(180.dp))
        Text(draft.name, style = WolfyTheme.typography.bookTitle, color = colors.ink)
        CharacterLine(draft)
        PrimaryButton("Сохранить", onSave, enabled = viewModel.draftValid())
        Text(
            "После сохранения можно одним запросом создать набор из $PHRASE_COUNT коротких реплик. Обычное чтение запросов не тратит: реплики выбираются на устройстве.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )
    }
}

/** Созданный компаньон: живой предпросмотр и спокойные действия. */
@Composable
private fun Profile(
    viewModel: CompanionViewModel,
    onGeneratePack: () -> Unit,
    onDeleteConfirmed: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val uriHandler = LocalUriHandler.current
    val profile = viewModel.state.profile ?: return
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        CompanionFigure(
            appearance = profile.appearance,
            modifier = Modifier
                .size(180.dp)
                .align(Alignment.CenterHorizontally),
        )
        Text(
            profile.name,
            style = WolfyTheme.typography.bookTitle,
            color = colors.ink,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            CharacterLineText(profile),
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Изменить внешность", color = colors.accent, modifier = Modifier.pressable(onClick = { viewModel.editAppearance() }).padding(vertical = spacing.medium))
            Text("Изменить характер", color = colors.accent, modifier = Modifier.pressable(onClick = { viewModel.editPersonality() }).padding(vertical = spacing.medium))
        }
        Rule()
        SwitchRow(
            title = "Реплики при чтении",
            hint = "Персонаж останется, но молча. Ручные вопросы продолжат работать.",
            on = viewModel.reactionsEnabled(),
            onChange = viewModel::setReactionsEnabled,
        )
        if (viewModel.state.profile?.phrasePack == null) {
            PrimaryButton("Создать набор реплик", onGeneratePack)
        } else {
            PrimaryButton("Обновить набор реплик", onGeneratePack)
            Text(
                "Это один запрос к ИИ. Обычное чтение запросов не тратит.",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }
        when (val request = viewModel.packRequest) {
            is PackRequest.Loading -> Text("Создаём набор реплик. Это минута.", style = WolfyTheme.typography.caption, color = colors.inkMuted)
            is PackRequest.Failure -> Text(request.message, style = WolfyTheme.typography.caption, color = colors.accent)
            else -> Unit
        }
        Text(
            text = if (viewModel.confirmDelete) "Удалить компаньона? Профиль и набор реплик исчезнут со всех устройств." else "Удалить компаньона",
            style = WolfyTheme.typography.body,
            color = if (viewModel.confirmDelete) colors.ink else colors.accent,
            modifier = Modifier
                .pressable(onClick = { if (viewModel.confirmDelete) onDeleteConfirmed() else viewModel.askDelete() })
                .padding(vertical = spacing.medium),
        )
        if (viewModel.confirmDelete) {
            Text(
                "Оставить",
                style = WolfyTheme.typography.body,
                color = colors.inkMuted,
                modifier = Modifier
                    .pressable(onClick = viewModel::cancelDelete)
                    .padding(vertical = spacing.medium),
            )
        }
        val warning = "ИИ может ошибаться. До 10 запросов в день."
        Text(warning, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        Text(
            "Политика приватности",
            style = WolfyTheme.typography.caption,
            color = colors.accent,
            modifier = Modifier.pressable { uriHandler.openUri(PRIVACY_URL) },
        )
        if (profile.aiConsentAt > 0) {
            Text(
                "Отозвать согласие на ИИ",
                style = WolfyTheme.typography.caption,
                color = colors.accent,
                modifier = Modifier.pressable { viewModel.revokeAiConsent() },
            )
        }
    }
}

@Composable
private fun CharacterLine(profile: CompanionProfile) {
    val colors = WolfyTheme.colors
    Text(
        CharacterLineText(profile),
        style = WolfyTheme.typography.caption,
        color = colors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Короткая строка характера: два заметных полюса без чисел. */
internal fun CharacterLineText(profile: CompanionProfile): String {
    val p = profile.personality
    val parts = mutableListOf<String>()
    if (p.warmth >= 65) parts.add("тёплый") else if (p.warmth <= 35) parts.add("сдержанный")
    if (p.playfulness >= 65) parts.add("игривый") else if (p.playfulness <= 35) parts.add("серьёзный")
    if (p.energy >= 65) parts.add("энергичный") else if (p.energy <= 35) parts.add("спокойный")
    if (p.verbosity >= 65) parts.add("разговорчивый") else if (p.verbosity <= 35) parts.add("лаконичный")
    if (parts.isEmpty()) parts.add("ровный и внимательный")
    return parts.joinToString(", ")
}

@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = WolfyTheme.typography.body, color = colors.ink)
            Text(hint, style = WolfyTheme.typography.caption, color = colors.inkMuted)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Пример на посадочной: нейтральный персонаж пака. */
private val LandingExample = com.wolfy.data.companion.CompanionAppearance(
    hair = "hair.01",
    brows = "brows.02",
    eyes = "eyes.17",
    mouth = "mouth.01",
    body = "body.17",
    skin = "light",
    hairColor = "ink",
    outfitColor = "brick",
)

/**
 * Подписи шкал характера.
 *
 * Привязаны к ключу, а не к порядковому номеру. Раньше это были два списка
 * рядом, и одиннадцатая шкала в модели уронила бы экран характера обращением
 * за границу второго списка. Заодно у шкалы появилось имя: по одним полюсам
 * «поддерживает / бросает вызов» не понять, что настраивается.
 */
private data class Scale(val title: String, val low: String, val high: String)

private val SCALES: Map<String, Scale> = mapOf(
    "warmth" to Scale("Теплота", "сдержанный", "тёплый"),
    "playfulness" to Scale("Игривость", "серьёзный", "игривый"),
    "energy" to Scale("Энергия", "спокойный", "энергичный"),
    "directness" to Scale("Прямота", "тактичный", "прямой"),
    "optimism" to Scale("Взгляд", "скептичный", "оптимистичный"),
    "emotionality" to Scale("Чувства", "рациональный", "эмоциональный"),
    "supportStyle" to Scale("Поддержка", "поддерживает", "бросает вызов"),
    "verbosity" to Scale("Многословность", "лаконичный", "разговорчивый"),
    "curiosity" to Scale("Любопытство", "практичный", "любопытный"),
    "formality" to Scale("Тон", "дружеский", "формальный"),
)

private const val PRIVACY_URL = "https://wolfy.citavuk.ru/privacy"
