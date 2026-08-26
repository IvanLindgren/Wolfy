package com.wolfy.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import com.wolfy.platform.DefaultStations
import com.wolfy.platform.RadioState
import com.wolfy.platform.RadioStation
import com.wolfy.platform.ownStation
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.pressable

/**
 * Радио: список станций, громкость и своя станция.
 *
 * Устроено как выключатель, а не как проигрыватель. Здесь нет ни обложек, ни
 * названий треков, ни перемотки — всё это заставляло бы смотреть на панель,
 * тогда как её задача противоположная: включить фон и забыть о нём.
 *
 * Играющая станция помечена подписью, а не значком: значок «пауза» на месте
 * значка «играть» читатели путают каждый раз, а слово — нет.
 */
@Composable
fun RadioPanel(
    state: RadioState,
    ownUrl: String,
    onStation: (RadioStation) -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    onOwnUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        SectionLabel("Радио под чтение")
        Text(
            text = "Ровный фон помогает не всем, поэтому по умолчанию тихо и " +
                "выключено. Станции инструментальные. Голос в фоне становится вторым " +
                "текст, и читать под него нельзя.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )

        state.failure?.let { failure ->
            Text(failure, style = WolfyTheme.typography.caption, color = colors.accent)
        }

        DefaultStations.forEach { station ->
            StationRow(
                station = station,
                state = state,
                onPick = { onStation(station) },
                onStop = onStop,
            )
        }

        Rule()

        Text(
            text = "Своя станция",
            style = WolfyTheme.typography.body,
            color = colors.ink,
        )
        Text(
            text = "HTTPS-адрес потока. У того, кто слушает под чтение, любимая " +
                "станция обычно уже есть. Наш список пригодится, если своей ещё нет.",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
        )

        var draft by remember(ownUrl) { mutableStateOf(ownUrl) }
        Row(
            Modifier
                .fillMaxWidth()
                .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
                .padding(horizontal = spacing.medium, vertical = spacing.small),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                },
                singleLine = true,
                textStyle = WolfyTheme.typography.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .weight(1f)
                    // Файл настроек синхронизируется с диском. Писать его на
                    // каждый символ URL означает fsync на каждый keypress;
                    // черновик живёт в поле, а сохраняется при уходе фокуса
                    // или перед запуском своей станции.
                    .onFocusChanged { focus -> if (!focus.isFocused) onOwnUrl(draft.trim()) },
                decorationBox = { field ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                text = "https://…",
                                style = WolfyTheme.typography.body,
                                color = colors.inkMuted,
                            )
                        }
                        field()
                    }
                },
            )
            val own = ownStation(draft)
            Text(
                text = if (state.station?.id == "own" && state.playing) "выключить" else "включить",
                style = WolfyTheme.typography.button,
                color = if (own == null) colors.inkMuted else colors.accent,
            modifier = Modifier.pressable(enabled = own != null) {
                    onOwnUrl(draft.trim())
                    if (state.station?.id == "own" && state.playing) onStop() else own?.let(onStation)
                },
            )
        }

        Rule()
        Volume(value = state.volume, onChange = onVolume)
    }
}

@Composable
private fun StationRow(
    station: RadioStation,
    state: RadioState,
    onPick: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val current = state.station?.id == station.id
    val label = when {
        current && state.connecting -> "соединяемся…"
        current && state.playing -> "выключить"
        else -> "включить"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .pressable { if (current && state.playing) onStop() else onPick() }
            .padding(vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.hair)) {
            Text(station.title, style = WolfyTheme.typography.body, color = colors.ink)
            Text(
                text = if (station.source.isBlank()) {
                    station.hint
                } else {
                    // Имя вещателя обязательно: на этом условии станции и
                    // разрешено слушать в чужом приложении.
                    "${station.hint} · ${station.source}"
                },
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }
        Text(
            text = label,
            style = WolfyTheme.typography.button,
            color = if (current && state.playing) colors.accent else colors.inkMuted,
        )
    }
}

/**
 * Громкость.
 *
 * Полосой с делениями, а не системным ползунком: ползунок Material тянет за
 * собой свою пластику, а панель должна выглядеть частью книги. Делений семь —
 * столько положений человек различает на слух, не подбирая.
 */
@Composable
private fun Volume(value: Float, onChange: (Float) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    val steps = 7

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Громче", style = WolfyTheme.typography.caption, color = colors.inkMuted)
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing.tight),
        ) {
            repeat(steps) { step ->
                val share = (step + 1).toFloat() / steps
                val on = value >= share - 0.001f
                Box(
                    Modifier
                        .weight(1f)
                        .height(spacing.medium)
                        .background(
                            if (on) colors.accent else colors.rule,
                            RoundedCornerShape(spacing.hair),
                        )
                        .pressable { onChange(share) },
                )
            }
        }
        Text(
            text = "тише",
            style = WolfyTheme.typography.caption,
            color = colors.inkMuted,
            modifier = Modifier.pressable { onChange(0f) },
        )
    }
}
