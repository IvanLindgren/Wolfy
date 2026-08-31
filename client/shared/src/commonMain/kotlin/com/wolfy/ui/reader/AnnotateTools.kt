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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wolfy.data.annotations.Annotation
import com.wolfy.data.annotations.TONES
import com.wolfy.data.annotations.toneTitle
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.highlightColor
import com.wolfy.widgets.pressable

/**
 * Что можно сделать с только что выделенным куском.
 *
 * ## Почему это заменило полку инструментов
 *
 * Раньше над текстом постоянно висела полка «Чтение · Маркер · Заметка»:
 * читатель заранее объявлял, чем он сейчас водит, и дальше просто вёл пальцем.
 * У этого было три беды, и все три об одном — режим живёт дольше намерения.
 *
 * Полка **всегда занимала верх страницы**. Роман открывался не первой строкой,
 * а тремя словами интерфейса поверх неё; ради оснастки, к которой обращаются
 * несколько раз за книгу, страница переставала быть страницей.
 *
 * Режим **оставался включённым после действия**. Покрасив фразу, читатель
 * оставался с маркером в руке: следующее выделение красилось молча, а разбор,
 * за которым он тянулся, не открывался.
 *
 * И режим **надо было выбрать заранее**, до того как читателю стало что
 * выделять. Он видит фразу, ведёт по ней пальцем — и только тут узнаёт, что
 * нужно было сперва сказать «маркер». Чтобы покрасить, выделение приходилось
 * делать дважды.
 *
 * Здесь выбор идёт после жеста, как во всём остальном на этом экране: сначала
 * читатель берёт кусок, потом решает, что с ним делать, и после решения ничего
 * включённым не остаётся. Ни одного невидимого состояния на странице.
 *
 * ## Почему краски открываются, а не лежат сразу
 *
 * Десять кружков рядом с тремя подписями — ряд шире телефона; прежняя полка
 * ровно так и не помещалась в 360 точек. Поэтому «Маркер» сперва красит
 * последней краской — одно нажатие на самый частый случай, — и только потом
 * показывает остальные, чтобы цвет можно было переменить уже покрашенному.
 * Выбор цвета до покраски был бы обязательным шагом ради решения, которое
 * читателю обычно безразлично.
 */
@Composable
fun SelectionActions(
    tone: Int,
    /** Уже покрашено: тогда «Маркер» превращается в «Цвет». */
    painted: Boolean,
    /** Выделение накрывает отметку — значит её есть чем убрать. */
    removable: Boolean,
    onExplain: () -> Unit,
    onPaint: (Int) -> Unit,
    onNote: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    var tonesOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .clip(RoundedCornerShape(spacing.medium))
            .background(colors.paper)
            .border(1.dp, colors.rule, RoundedCornerShape(spacing.medium))
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Разбор идёт первым и набран цветом: это то, ради чего фразу
            // выделяют в девяти случаях из десяти, и единственное здесь, что
            // ходит в сеть.
            Action("Разбор", accent = true, onClick = onExplain)
            Row(
                Modifier
                    .pressable {
                        onPaint(tone)
                        tonesOpen = true
                    }
                    .semantics { contentDescription = "Покрасить: " + toneTitle(tone) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(highlightColor(tone))
                        .border(1.dp, colors.rule, CircleShape),
                )
                Text(
                    if (painted) "Цвет" else "Маркер",
                    style = WolfyTheme.typography.button,
                    color = colors.ink,
                )
            }
            Action("Заметка", accent = false, onClick = onNote)
            if (removable) Action("Убрать", accent = false, quiet = true, onClick = onRemove)
        }
        if (tonesOpen) {
            ToneRows(chosen = tone, onPick = onPaint)
        }
    }
}

/**
 * Краски двумя рядами по пять.
 *
 * Не десять в строку: десять кружков по 36 точек — это 360 точек, ровно ширина
 * телефона без полей. Прежняя полка так и стояла, и последние краски у неё
 * уезжали за экран.
 */
@Composable
private fun ToneRows(chosen: Int?, onPick: (Int) -> Unit) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        for (half in TONES.chunked(5)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                for (value in half) {
                    ToneDot(value, chosen = value == chosen, onPick = { onPick(value) })
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, accent: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = WolfyTheme.typography.button,
        color = when {
            accent -> WolfyTheme.colors.accent
            quiet -> WolfyTheme.colors.inkMuted
            else -> WolfyTheme.colors.ink
        },
        modifier = Modifier
            .pressable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/**
 * Кружок краски.
 *
 * Тридцать шесть точек на кружок в двадцать: попасть пальцем в двадцать точек
 * можно только прицелившись.
 */
@Composable
private fun ToneDot(tone: Int, chosen: Boolean, onPick: () -> Unit) {
    val colors = WolfyTheme.colors
    Box(
        Modifier
            .size(36.dp)
            .pressable(onClick = onPick)
            .semantics { contentDescription = toneTitle(tone) },
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
        ToneRows(chosen = item.tone, onPick = onTone)
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
