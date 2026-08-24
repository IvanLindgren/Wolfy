package com.wolfy.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.Curves
import com.wolfy.widgets.LocalFlight
import com.wolfy.widgets.flightTarget
import com.wolfy.widgets.NavGlyph
import com.wolfy.widgets.NavIcon
import com.wolfy.widgets.Rule
import com.wolfy.widgets.pressable

/**
 * Раздел приложения.
 *
 * Пять самостоятельных разделов. Лента вынесена отдельно: это ежедневный
 * сценарий выбора следующего чтения, а не настройка библиотеки.
 */
enum class Section(val title: String, val icon: NavIcon) {
    /** Библиотека и чтение — то, ради чего приложение открывают. */
    Books("Книги", NavIcon.Books),

    /** Полки: как читатель разложил свои книги. */
    Shelves("Полки", NavIcon.Shelves),

    /** Вертикальная персональная лента материалов. */
    Discover("Лента", NavIcon.Discover),

    /**
     * Карточки: колоды слов, фраз и правил из прочитанного.
     *
     * Не «SRS»: аббревиатура называет механизм интервальных повторений, а
     * читателю в нижней панели нужно название того, что он там найдёт.
     */
    Cards("Карточки", NavIcon.Cards),

    /** Настройки и всё остальное. */
    More("Ещё", NavIcon.More),
}

const val FLIGHT_CARDS = "cards"

/**
 * Нижняя панель разделов.
 *
 * Активный раздел отмечен цветом и значка, и подписи — одного цвета значка
 * мало: на маленьком экране он занимает две десятых доли подписи по площади, и
 * взгляд его не находит.
 *
 * Толстая линейка сверху — та же, что отбивает разделы газетной полосы: панель
 * должна читаться как колонтитул, а не как всплывшая поверх страницы плашка.
 */
@Composable
fun BottomBar(
    selected: Section,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(modifier.fillMaxWidth().background(colors.paper)) {
        Rule(thick = true)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.small),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Section.entries.forEach { section ->
                val active = section == selected
                val tint = if (active) colors.accent else colors.inkMuted
                val flight = LocalFlight.current
                val arrived = if (section == Section.Cards) flight.arrivals[FLIGHT_CARDS] ?: 0 else 0
                val pop = remember(section) { Animatable(1f) }
                val motion = WolfyTheme.motion
                LaunchedEffect(arrived) {
                    if (arrived == 0 || motion.instant == 0) return@LaunchedEffect
                    pop.animateTo(1.12f, tween(motion.instant, easing = Curves.Paper))
                    pop.animateTo(1f, tween(motion.quick, easing = Curves.Paper))
                }

                Column(
                    Modifier
                        .then(if (section == Section.Cards) Modifier.flightTarget(FLIGHT_CARDS) else Modifier)
                        .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                        .pressable {
                            if (section == Section.Cards) flight.clearArrivals(FLIGHT_CARDS)
                            onSelect(section)
                        }
                        .padding(horizontal = spacing.small, vertical = spacing.tight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.tight),
                ) {
                    Box {
                        NavGlyph(section.icon, tint = tint)
                        if (arrived > 0) {
                            Text(
                                text = arrived.coerceAtMost(99).toString(),
                                style = WolfyTheme.typography.caption,
                                color = colors.onAccent,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(colors.accent, CircleShape)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Text(
                        text = section.title,
                        style = WolfyTheme.typography.caption,
                        color = tint,
                    )
                }
            }
        }
    }
}
