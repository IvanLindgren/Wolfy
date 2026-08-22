package com.wolfy.ui.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.wolfy.ffi.Article
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.Sticker
import com.wolfy.widgets.WolfySticker
import com.wolfy.widgets.pressable

/**
 * Справочник грамматики.
 *
 * Статьи приходят из ядра вместе с объяснениями — теми же, что читатель видит
 * в карточке слова. Написать справочник отдельным текстом было бы проще, но он
 * разошёлся бы с движком на второй же правке, и одно правило объяснялось бы в
 * приложении двумя разными способами.
 *
 * Список плоский, а не по вкладкам разделов: разделов пять, статей двадцать
 * с небольшим, и прокрутка находит нужное быстрее, чем выбор вкладки, за
 * которым всё равно следует прокрутка.
 */
@Composable
fun ReferenceScreen(
    articles: List<Article>,
    /** Правило, ради которого справочник открыли: оно раскрывается сразу. */
    openAt: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    var query by remember { mutableStateOf("") }
    var expanded by remember(openAt) { mutableStateOf(openAt) }

    val visible = remember(articles, query) { filter(articles, query) }
    val list = rememberLazyListState()

    // Открытие по правилу: справочник вызвали из карточки слова, и нужная
    // статья должна оказаться перед глазами, а не в середине прокрутки.
    LaunchedEffect(openAt, visible) {
        val index = visible.indexOfFirst { it.rule == openAt }
        if (index >= 0) list.scrollToItem(index + 1)
    }

    LazyColumn(
        state = list,
        modifier = modifier.fillMaxSize().background(colors.paper),
        contentPadding = PaddingValues(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    text = "‹ назад",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                    modifier = Modifier.pressable(onClick = onBack),
                )
                Text(
                    text = "Справочник",
                    style = WolfyTheme.typography.screenTitle,
                    color = colors.ink,
                )
                Text(
                    text = "Те же объяснения, что в карточке слова: разбор и справочник " +
                        "берут их из одного места.",
                    style = WolfyTheme.typography.caption,
                    color = colors.inkMuted,
                )
                Rule(thick = true)
                SearchRow(query = query, onChange = { query = it })
            }
        }

        if (visible.isEmpty()) {
            item { NothingFound(query) }
        }

        items(visible, key = { it.rule }) { article ->
            ArticleCard(
                article = article,
                first = visible.firstOrNull { it.topic == article.topic } === article,
                expanded = expanded == article.rule,
                onToggle = { expanded = if (expanded == article.rule) null else article.rule },
            )
        }
    }
}

/**
 * Статья.
 *
 * Свёрнутая показывает название и формулу — по ним правило и узнают. Развёрнутая
 * добавляет объяснение, пример с переводом и совет, когда правило уместно:
 * последнего нет в разборе готовой фразы, потому что там читатель смотрит на
 * уже написанное, а здесь — выбирает, как написать самому.
 */
@Composable
private fun ArticleCard(
    article: Article,
    first: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        if (first) {
            SectionLabel(article.topicTitle, modifier = Modifier.padding(top = spacing.medium))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(spacing.small))
                .border(
                    spacing.rule,
                    if (expanded) colors.accent else colors.rule,
                    RoundedCornerShape(spacing.small),
                )
                .pressable(onClick = onToggle)
                .padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = article.title,
                    style = WolfyTheme.typography.bookTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = article.formula,
                    style = WolfyTheme.typography.caption,
                    color = colors.accent,
                )
            }

            if (!expanded) {
                Text(
                    text = article.example,
                    style = WolfyTheme.typography.translation,
                    color = colors.inkMuted,
                    maxLines = 1,
                )
                return@Column
            }

            Text(
                text = article.explanation,
                style = WolfyTheme.typography.body,
                color = colors.ink,
            )

            Rule()
            SectionLabel("Пример")
            Text(
                text = article.example,
                style = WolfyTheme.typography.reader,
                color = colors.ink,
            )
            Text(
                text = "«${article.translation}»",
                style = WolfyTheme.typography.translation,
                color = colors.inkMuted,
            )

            Rule()
            SectionLabel("Когда уместно")
            Text(
                text = article.usage,
                style = WolfyTheme.typography.body,
                color = colors.ink,
            )
        }
    }
}

@Composable
private fun SearchRow(query: String, onChange: (String) -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing

    Row(
        Modifier
            .fillMaxWidth()
            .border(spacing.rule, colors.rule, RoundedCornerShape(spacing.small))
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = WolfyTheme.typography.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f).padding(vertical = spacing.small),
            decorationBox = { field ->
                if (query.isEmpty()) {
                    Text(
                        text = "Найти: perfect, условие, have + V3…",
                        style = WolfyTheme.typography.body,
                        color = colors.inkMuted,
                    )
                }
                field()
            },
        )
        if (query.isNotEmpty()) {
            Text(
                text = "×",
                style = WolfyTheme.typography.screenTitle,
                color = colors.inkMuted,
                modifier = Modifier.pressable { onChange("") },
            )
        }
    }
}

@Composable
private fun NothingFound(query: String) {
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(vertical = spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        WolfySticker(Sticker.Thinking, size = 120.dp)
        Text(
            text = "По запросу «$query» ничего нет",
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.ink,
        )
        Text(
            text = "Справочник знает времена, залог, модальные, неличные формы и условные.",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
        )
    }
}

/**
 * Отбор по запросу.
 *
 * Ищем по всему, что в статье есть, включая формулу: «have + V3» — такой же
 * законный запрос, как «перфект», и человек, помнящий схему, но забывший
 * название, встречается чаще, чем наоборот.
 */
private fun filter(articles: List<Article>, query: String): List<Article> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return articles

    return articles.filter { article ->
        listOf(
            article.title,
            article.formula,
            article.explanation,
            article.example,
            article.translation,
            article.usage,
            article.topicTitle,
        ).any { it.lowercase().contains(needle) }
    }
}
