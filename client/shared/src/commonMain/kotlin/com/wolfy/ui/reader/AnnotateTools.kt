package com.wolfy.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.wolfy.data.annotations.Annotation
import com.wolfy.data.annotations.TONES
import com.wolfy.data.annotations.toneTitle
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.highlightColor
import com.wolfy.widgets.pressable

/** Чем сейчас занят палец: обычным чтением, маркером или заметкой. */
enum class ReaderTool { Read, Pencil, Note }

/**
 * Полоса инструментов чтения.
 *
 * Она нужна затем, что на телефоне не существует «выделить и выбрать из
 * контекстного меню»: меню там перекрывает текст, а долгое нажатие уже занято
 * выделением. Вместо меню читатель заранее говорит, чем он сейчас водит, - и
 * дальше просто ведёт пальцем.
 *
 * Карандаш работает и ластиком: провёл - покрасил, нажал по покрашенному -
 * снял. Отдельная кнопка ластика была бы лишней: тем же карандашом
 * зачёркивают и на бумаге.
 *
 * Полоса того же вида, что и в вебе, и краски у неё те же по номеру и по
 * цвету: у читателя с двумя устройствами жёлтый обязан быть жёлтым на обоих.
 */
@Composable
fun AnnotateDock(
    tool: ReaderTool,
    tone: Int,
    onTool: (ReaderTool) -> Unit,
    onTone: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(
        modifier
            .clip(RoundedCornerShape(spacing.medium))
            .background(colors.paper)
            .border(1.dp, colors.rule, RoundedCornerShape(spacing.medium))
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
            ToolLabel("Чтение", tool == ReaderTool.Read) { onTool(ReaderTool.Read) }
            ToolLabel("Маркер", tool == ReaderTool.Pencil) { onTool(ReaderTool.Pencil) }
            ToolLabel("Заметка", tool == ReaderTool.Note) { onTool(ReaderTool.Note) }
        }
        // Краски показываются только при выбранном маркере: десять кружков
        // в режиме чтения - десять кнопок, которые ничего не делают.
        if (tool == ReaderTool.Pencil) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                for (value in TONES) {
                    ToneDot(value, chosen = value == tone, onPick = { onTone(value) })
                }
            }
        }
    }
}

@Composable
private fun ToolLabel(title: String, active: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style = WolfyTheme.typography.button,
        color = if (active) WolfyTheme.colors.accent else WolfyTheme.colors.inkMuted,
        modifier = Modifier
            .pressable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/**
 * Кружок краски.
 *
 * Тридцать шесть точек на кружок в двадцать: попасть пальцем в двадцать точек
 * можно только прицелившись, а красок здесь десять в ряд.
 */
@Composable
private fun ToneDot(tone: Int, chosen: Boolean, onPick: () -> Unit) {
    val colors = WolfyTheme.colors
    Box(
        Modifier
            .size(36.dp)
            .pressable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (chosen) 24.dp else 20.dp)
                .clip(CircleShape)
                .background(highlightColor(tone))
                .border(
                    width = if (chosen) 2.dp else 1.dp,
                    color = if (chosen) colors.accent else colors.rule,
                    shape = CircleShape,
                ),
        )
    }
}

/**
 * Лист заметки: цитата, поле текста и краска.
 *
 * Поле сразу под цитатой, а не за отдельным нажатием: заметку пишут по горячим
 * следам, и лишний шаг между «выделил» и «написал» - это и есть то, из-за чего
 * заметки перестают ставить.
 *
 * Текст уезжает наружу по каждому изменению, а не по кнопке «Сохранить».
 * Кнопка сохранения здесь была бы обещанием, которое некому выполнить: лист
 * закрывают жестом назад, и нажать её половина читателей не успеет.
 */
@Composable
fun NoteSheet(
    item: Annotation,
    onNote: (String) -> Unit,
    onTone: (Int) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    var draft by remember(item.id) { mutableStateOf(item.note) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = spacing.medium, topEnd = spacing.medium))
            .background(colors.paper)
            .border(1.dp, colors.rule, RoundedCornerShape(topStart = spacing.medium, topEnd = spacing.medium))
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        if (item.quote.isNotBlank()) {
            Text(
                item.quote,
                style = WolfyTheme.typography.translation,
                color = colors.inkMuted,
                maxLines = 4,
            )
        }
        TextField(
            value = draft,
            onValueChange = {
                draft = it
                onNote(it)
            },
            placeholder = { Text("Что здесь важного?", color = colors.inkMuted) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            for (value in TONES) {
                ToneDot(value, chosen = value == item.tone, onPick = { onTone(value) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.large)) {
            Text(
                "Закрыть",
                style = WolfyTheme.typography.button,
                color = colors.accent,
                modifier = Modifier.pressable(onClick = onClose),
            )
            Text(
                "Удалить",
                style = WolfyTheme.typography.button,
                color = colors.inkMuted,
                modifier = Modifier.pressable(onClick = onRemove),
            )
            item.tone?.let {
                Text(
                    toneTitle(it),
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
            }
        }
    }
}
