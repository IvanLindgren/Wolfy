package com.wolfy.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.NavGlyph
import com.wolfy.widgets.NavIcon
import com.wolfy.widgets.Rule

/**
 * Раздел приложения.
 *
 * Четыре и ровно четыре. Пятый раздел означал бы, что какой-то из этих четырёх
 * недостаточно самостоятелен, а нижняя панель с пятью подписями перестаёт
 * читаться на телефоне.
 */
enum class Section(val title: String, val icon: NavIcon) {
    /** Библиотека и чтение — то, ради чего приложение открывают. */
    Books("Книги", NavIcon.Books),

    /** Полки: как читатель разложил свои книги. */
    Shelves("Полки", NavIcon.Shelves),

    /** Повторения: колоды слов из прочитанного. */
    Srs("SRS", NavIcon.Srs),

    /** Настройки и всё остальное. */
    More("Ещё", NavIcon.More),
}

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

                Column(
                    Modifier
                        .clickable { onSelect(section) }
                        .padding(horizontal = spacing.medium, vertical = spacing.tight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.tight),
                ) {
                    NavGlyph(section.icon, tint = tint)
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
