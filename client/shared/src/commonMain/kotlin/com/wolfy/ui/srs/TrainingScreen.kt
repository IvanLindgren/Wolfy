package com.wolfy.ui.srs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wolfy.srs.Deck
import com.wolfy.srs.Drill
import com.wolfy.srs.DrillKind
import com.wolfy.srs.Scheduler
import com.wolfy.srs.TrainingState
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.widgets.pressable

/**
 * Тренировка.
 *
 * Один экран на все три колоды: шапка, прочность карточки и задание. Способ
 * спросить меняется, обрамление — нет, и это сделано нарочно. Читатель,
 * перешедший от слов к грамматике, не должен заново искать, где здесь «дальше»
 * и сколько ещё осталось.
 *
 * Проверка происходит сама, как только ответ дособран: отдельная кнопка
 * «Проверить» нужна там, где ответ можно передумать по частям — в конструкторе
 * фраз, — а в выборе из четырёх и в пятнашках она лишний щелчок между
 * читателем и ответом.
 */
@Composable
fun TrainingScreen(
    state: TrainingState,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val deck = state.deck ?: return

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.paper),
        contentPadding = PaddingValues(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        item {
            Header(
                title = when (deck) {
                    Deck.Words -> "Тренировка слов"
                    Deck.Phrases -> "Конструктор фраз"
                    Deck.Rules -> "Грамматика"
                },
                subtitle = subtitle(state),
                onClose = onClose,
            )
        }

        val drill = state.drill
        if (state.finished || drill == null) {
            item { Finished(state = state, onClose = onClose) }
            return@LazyColumn
        }

        if (deck != Deck.Rules) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    SectionLabel("Прочность карточки")
                    Hearts(state.hp)
                }
            }
        }

        item {
            // key по заданию: у пятнашек и конструктора внутри живёт свой
            // набор — выбранные буквы, собранные блоки. Без ключа следующее
            // задание досталось бы состоянию предыдущего.
            key(drill.cardId, drill.kind) {
                when (drill.kind) {
                    DrillKind.Choice, DrillKind.Gap -> ChoiceDrill(
                        drill = drill,
                        answered = state.verdict != null,
                        onAnswer = onAnswer,
                    )

                    DrillKind.Letters -> LettersDrill(
                        drill = drill,
                        answered = state.verdict != null,
                        onAnswer = onAnswer,
                    )

                    DrillKind.Typing -> TypingDrill(
                        drill = drill,
                        answered = state.verdict != null,
                        onAnswer = onAnswer,
                    )

                    DrillKind.Builder -> BuilderDrill(
                        drill = drill,
                        answered = state.verdict != null,
                        onAnswer = onAnswer,
                    )
                }
            }
        }

        state.verdict?.let { verdict ->
            item { VerdictBlock(right = verdict.right, answer = verdict.answer, explanation = verdict.explanation) }
            item { PrimaryButton(text = "Дальше", onClick = onNext) }
        }
    }
}

private fun subtitle(state: TrainingState): String {
    val place = if (state.total > 0) "карточка ${state.position} из ${state.total}" else ""
    val source = state.source
    return when {
        source.isBlank() -> place
        place.isBlank() -> source
        else -> "$source · $place"
    }
}

@Composable
private fun Header(title: String, subtitle: String, onClose: () -> Unit) {
    val colors = WolfyTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = WolfyTheme.typography.screenTitle, color = colors.ink)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
        }
        Text(
            text = "×",
            style = WolfyTheme.typography.screenTitle,
            color = colors.inkMuted,
            modifier = Modifier.pressable(onClick = onClose),
        )
    }
}

/**
 * Прочность карточки сердцами.
 *
 * Пять сердец на сто очков: у карточки, которую видят впервые, полны все, у
 * выученной — ни одного. Метафора перевёрнута нарочно и объяснена в
 * [Scheduler]: карточка здесь противник, и её прочность сбивают.
 */
@Composable
private fun Hearts(hp: Int) {
    val colors = WolfyTheme.colors
    val full = ((hp + 19) / 20).coerceIn(0, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small)) {
        repeat(5) { index ->
            Text(
                text = if (index < full) "♥" else "♡",
                style = WolfyTheme.typography.body,
                color = if (index < full) colors.accent else colors.rule,
            )
        }
    }
}

/** Выбор одного из четырёх: перевод слова или название правила. */
@Composable
private fun ChoiceDrill(drill: Drill, answered: Boolean, onAnswer: (String) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    var chosen by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        Prompt(drill.question, drill.subject)
        if (drill.formula.isNotBlank() && drill.kind == DrillKind.Gap) {
            Text(
                text = drill.formula,
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }

        drill.pieces.forEach { option ->
            val picked = chosen == option
            val right = option == drill.answer
            // Цвет появляется только после ответа: до него все варианты равны,
            // а подсвеченный «выбранный» вариант читается как подсказка.
            val border = when {
                !answered -> colors.rule
                right -> colors.partsOfSpeech.adjective
                picked -> colors.accent
                else -> colors.rule
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(spacing.small))
                    .border(
                        width = if (answered && (right || picked)) 2.dp else spacing.rule,
                        color = border,
                        shape = RoundedCornerShape(spacing.small),
                    )
                    .pressable(enabled = !answered) {
                        chosen = option
                        onAnswer(option)
                    }
                    .padding(spacing.large),
            ) {
                Text(text = option, style = WolfyTheme.typography.body, color = colors.ink)
            }
        }
    }
}

/**
 * Пятнашки: собрать слово из букв.
 *
 * Часть букв стоит на месте с самого начала — иначе слово из двенадцати букв
 * превращается в головоломку про перебор, а проверяется в ней терпение, а не
 * память. Какие именно открыты, решает [com.wolfy.srs.Drills], и решает
 * одинаково при каждом показе.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LettersDrill(drill: Drill, answered: Boolean, onAnswer: (String) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    val word = drill.answer
    // Буквы, поставленные читателем: номер плитки из банка на каждую позицию
    // слова. Хранится номер, а не сама буква, — иначе одинаковые буквы в банке
    // стали бы неразличимы и вернуть в него можно было бы не ту.
    val slots = remember(word) { mutableStateListOf<Int?>(*arrayOfNulls<Int>(word.length)) }
    var typed by remember(word) { mutableStateOf("") }

    val assembled = word.indices.joinToString("") { index ->
        when {
            index in drill.given -> word[index].toString()
            else -> slots[index]?.let { drill.pieces[it] } ?: ""
        }
    }
    val full = word.indices.all { it in drill.given || slots[it] != null }

    // Собранное слово проверяется само: отдельная кнопка здесь была бы лишним
    // щелчком между читателем и ответом.
    LaunchedEffect(full, answered) {
        if (full && !answered) onAnswer(assembled)
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        Prompt(drill.question, drill.subject)
        Text(
            text = "Соберите слово:",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.tight),
            verticalArrangement = Arrangement.spacedBy(spacing.tight),
        ) {
            word.indices.forEach { index ->
                val fixed = index in drill.given
                val slot = slots[index]
                LetterTile(
                    letter = when {
                        fixed -> word[index].toString()
                        slot != null -> drill.pieces[slot]
                        else -> ""
                    },
                    dark = fixed || slot != null,
                    empty = !fixed && slot == null,
                    onClick = { if (!fixed && slot != null && !answered) slots[index] = null },
                )
            }
        }

        val used = slots.filterNotNull().toSet()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            drill.pieces.forEachIndexed { index, letter ->
                val spent = index in used
                LetterTile(
                    letter = letter,
                    dark = false,
                    empty = false,
                    faded = spent,
                    onClick = {
                        if (spent || answered) return@LetterTile
                        val free = word.indices.firstOrNull {
                            it !in drill.given && slots[it] == null
                        } ?: return@LetterTile
                        slots[free] = index
                    },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            SectionLabel("Или введите вручную")
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                enabled = !answered,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAnswer(typed) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Одна плитка: буква слова или буква банка. */
@Composable
private fun LetterTile(
    letter: String,
    dark: Boolean,
    empty: Boolean,
    faded: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Box(
        Modifier
            .size(width = 40.dp, height = 46.dp)
            .background(
                when {
                    faded -> colors.rule
                    dark -> colors.inverse
                    else -> colors.surface
                },
                RoundedCornerShape(spacing.tight),
            )
            .border(
                width = spacing.rule,
                // Пустое место обведено красным: оно и есть то, чего не
                // хватает, и глаз должен находить его без поиска.
                color = if (empty) colors.accent else colors.rule,
                shape = RoundedCornerShape(spacing.tight),
            )
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = WolfyTheme.typography.bookTitle,
            color = if (dark) colors.onInverse else colors.ink,
        )
    }
}

/** Ввод по памяти: ни букв, ни вариантов. */
@Composable
private fun TypingDrill(drill: Drill, answered: Boolean, onAnswer: (String) -> Unit) {
    val spacing = WolfyTheme.spacing
    var typed by remember(drill.cardId) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        Prompt(drill.question, drill.subject)
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            enabled = !answered,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAnswer(typed) }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!answered) PrimaryButton(text = "Проверить", onClick = { onAnswer(typed) })
    }
}

/**
 * Конструктор фраз.
 *
 * Здесь кнопка «Проверить» на месте: фразу собирают по частям и передумывают
 * тоже по частям, и проверять её на каждый блок значило бы отвечать за
 * читателя раньше, чем он закончил.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderDrill(drill: Drill, answered: Boolean, onAnswer: (String) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    // Номера блоков в порядке сборки. Номера, а не строки: в банке бывают два
    // одинаковых блока, и вернуть надо тот, по которому нажали.
    val picked = remember(drill.cardId) { mutableStateListOf<Int>() }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.rule, RoundedCornerShape(spacing.small))
                .padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            SectionLabel("Переведите")
            Text(
                text = "«${drill.question}»",
                style = WolfyTheme.typography.translation,
                fontStyle = FontStyle.Italic,
                color = colors.ink,
            )
        }

        // Место сборки. Пустое оно или полное, рамка на месте: иначе первый
        // блок появлялся бы в пустоте, и куда его класть, было бы непонятно.
        FlowRow(
            Modifier
                .fillMaxWidth()
                .border(spacing.rule, colors.accent, RoundedCornerShape(spacing.small))
                .padding(spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            if (picked.isEmpty()) {
                Text(
                    text = "соберите фразу из блоков",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
            picked.forEach { index ->
                Chip(
                    text = drill.pieces[index],
                    dark = true,
                    onClick = { if (!answered) picked.remove(index) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            SectionLabel("Банк слов")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                drill.pieces.forEachIndexed { index, block ->
                    if (index in picked) return@forEachIndexed
                    Chip(
                        text = block,
                        dark = false,
                        onClick = { if (!answered) picked.add(index) },
                    )
                }
            }
        }

        if (!answered) {
            PrimaryButton(
                text = "Проверить",
                onClick = { onAnswer(picked.joinToString(" ") { drill.pieces[it] }) },
            )
        }
    }
}

/** Блок конструктора. */
@Composable
private fun Chip(text: String, dark: Boolean, onClick: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Text(
        text = text,
        style = WolfyTheme.typography.body,
        color = if (dark) colors.onInverse else colors.ink,
        modifier = Modifier
            .background(
                if (dark) colors.inverse else colors.surface,
                RoundedCornerShape(spacing.tight),
            )
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.tight))
            .pressable(onClick = onClick)
            .padding(horizontal = spacing.medium, vertical = spacing.small),
    )
}

/** Крупный вопрос и строка помельче под ним. */
@Composable
private fun Prompt(question: String, subject: String) {
    val colors = WolfyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small)) {
        Text(
            text = question,
            style = WolfyTheme.typography.screenTitle,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subject.isNotBlank()) {
            Text(
                text = subject,
                style = WolfyTheme.typography.translation,
                fontStyle = FontStyle.Italic,
                color = colors.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Что было верно.
 *
 * Верный ответ показывается и когда читатель ответил правильно: он видит, что
 * именно засчитано, и следующая встреча со словом начинается не с сомнения.
 */
@Composable
private fun VerdictBlock(right: Boolean, answer: String, explanation: String) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val tone: Color = if (right) colors.partsOfSpeech.adjective else colors.accent

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(spacing.small))
            .padding(spacing.large),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(48.dp)
                .background(tone, CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.tight)) {
            Text(
                text = if (right) "Верно" else "Верный ответ: $answer",
                style = WolfyTheme.typography.body,
                color = tone,
            )
            if (explanation.isNotBlank()) {
                Text(
                    text = explanation,
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

/** Итог порции. */
@Composable
private fun Finished(state: TrainingState, onClose: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val nothing = state.total == 0

    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        WolfySticker(if (nothing) Sticker.Sleep else Sticker.Celebrate, size = 130.dp)
        Text(
            text = if (nothing) "Здесь пока пусто" else "Порция закрыта",
            style = WolfyTheme.typography.bookTitle,
            color = colors.ink,
        )
        Text(
            text = if (nothing) {
                "Сохраняйте слова и фразы при чтении — они появятся тут."
            } else {
                plural(state.total, "карточка", "карточки", "карточек") +
                    " позади. Остальное дождётся следующего захода."
            },
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(text = "К колодам", onClick = onClose)
    }
}

/** Чёрная кнопка во всю ширину — та же, что «В колоду книги» на карточке. */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.inverse, RoundedCornerShape(spacing.huge))
            .pressable(onClick = onClick)
            .padding(vertical = spacing.medium),
    ) {
        Text(
            text = text,
            style = WolfyTheme.typography.button,
            color = colors.onInverse,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
