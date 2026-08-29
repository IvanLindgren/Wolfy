package com.wolfy.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wolfy.data.NewsArticle
import com.wolfy.data.NewsIssue
import com.wolfy.data.NewsSection
import com.wolfy.data.library.LibraryBook
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.pressable

@Composable
fun NewspaperScreen(
    viewModel: NewspaperViewModel,
    onOpenBook: (LibraryBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    val spacing = WolfyTheme.spacing
    Box(modifier.fillMaxSize().background(WolfyTheme.colors.paper)) {
        val issue = state.issue
        if (issue != null && issue.sections.any { it.articles.isNotEmpty() }) {
            NewspaperIssue(
                issue = issue,
                opening = state.opening,
                message = state.message,
                onOpen = { article -> viewModel.open(article, onOpenBook) },
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(spacing.pageMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.large),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(color = WolfyTheme.colors.accent)
                    Text("Собираем свежий номер", style = WolfyTheme.typography.body, color = WolfyTheme.colors.inkMuted)
                } else {
                    Text(
                        state.message ?: "В этом номере пока нет заметок.",
                        style = WolfyTheme.typography.body,
                        color = WolfyTheme.colors.inkMuted,
                    )
                    Text(
                        "обновить газету",
                        style = WolfyTheme.typography.button,
                        color = WolfyTheme.colors.accent,
                        modifier = Modifier.pressable(onClick = viewModel::refresh).padding(spacing.small),
                    )
                }
            }
        }
    }
}

@Composable
private fun NewspaperIssue(
    issue: NewsIssue,
    opening: String?,
    message: String?,
    onOpen: (NewsArticle) -> Unit,
) {
    val spacing = WolfyTheme.spacing
    var chosen by remember(issue.date) { mutableStateOf(emptySet<String>()) }
    var sheet by remember(issue.date) { mutableIntStateOf(0) }
    val sections = if (chosen.isEmpty()) issue.sections else issue.sections.filter { it.topic in chosen }
    val current = sections.getOrNull(sheet.coerceIn(0, (sections.size - 1).coerceAtLeast(0)))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        item { Masthead(issue.date) }
        if (issue.topics.isNotEmpty()) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    TopicChip("Весь номер", chosen.isEmpty()) {
                        chosen = emptySet()
                        sheet = 0
                    }
                    issue.topics.forEach { topic ->
                        TopicChip(topic.title, topic.code in chosen) {
                            chosen = if (topic.code in chosen) chosen - topic.code else chosen + topic.code
                            sheet = 0
                        }
                    }
                }
            }
        }
        if (current != null) {
            item { NewspaperSheet(current, opening, onOpen) }
            if (sections.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = spacing.medium),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TurnButton("← Назад", sheet > 0) { sheet-- }
                        Text(
                            "Полоса ${sheet + 1} из ${sections.size} · ${current.title}",
                            style = WolfyTheme.typography.caption,
                            color = WolfyTheme.colors.inkMuted,
                            textAlign = TextAlign.Center,
                        )
                        TurnButton("Дальше →", sheet < sections.lastIndex) { sheet++ }
                    }
                }
            }
        }
        message?.let { item { Text(it, style = WolfyTheme.typography.caption, color = WolfyTheme.colors.accent) } }
    }
}

@Composable
private fun Masthead(date: String) {
    val spacing = WolfyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Rule(thick = true)
        Text(
            "The Wolfy Times",
            style = WolfyTheme.typography.screenTitle.copy(fontSize = 48.sp, lineHeight = 50.sp),
            color = WolfyTheme.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.small),
        )
        Rule()
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(spacing.tight),
        ) {
            Text("Свежие заметки на английском", style = WolfyTheme.typography.caption, color = WolfyTheme.colors.inkMuted)
            Text(date.ifBlank { "Свежий номер" }, style = WolfyTheme.typography.caption, color = WolfyTheme.colors.ink, fontWeight = FontWeight.Bold)
            Text("Слова идут в вашу колоду", style = WolfyTheme.typography.caption, color = WolfyTheme.colors.inkMuted)
        }
        Rule(thick = true)
    }
}

@Composable
private fun TopicChip(title: String, active: Boolean, onClick: () -> Unit) {
    val spacing = WolfyTheme.spacing
    Text(
        title,
        style = WolfyTheme.typography.caption,
        color = if (active) WolfyTheme.colors.paper else WolfyTheme.colors.inkMuted,
        modifier = Modifier
            .pressable(onClick = onClick)
            .background(if (active) WolfyTheme.colors.ink else WolfyTheme.colors.surface, RoundedCornerShape(spacing.huge))
            .padding(horizontal = spacing.medium, vertical = spacing.small),
    )
}

@Composable
private fun NewspaperSheet(section: NewsSection, opening: String?, onOpen: (NewsArticle) -> Unit) {
    val spacing = WolfyTheme.spacing
    val lead = section.articles.firstOrNull() ?: return
    val rest = section.articles.drop(1)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(section.title.ifBlank { section.topic })
            Rule(thick = true, modifier = Modifier.weight(1f).padding(start = spacing.medium))
        }
        LeadStory(lead, opening == lead.id) { onOpen(lead) }
        if (rest.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 900.dp -> 3
                    maxWidth >= 620.dp -> 2
                    else -> 1
                }
                val storyWidth = (maxWidth - spacing.large * (columns - 1)) / columns
                FlowRow(
                    maxItemsInEachRow = columns,
                    horizontalArrangement = Arrangement.spacedBy(spacing.large),
                    verticalArrangement = Arrangement.spacedBy(spacing.large),
                ) {
                    rest.forEach { article ->
                        SmallStory(
                            article,
                            opening == article.id,
                            { onOpen(article) },
                            Modifier.width(storyWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeadStory(article: NewsArticle, opening: Boolean, onOpen: () -> Unit) {
    val spacing = WolfyTheme.spacing
    Column(
        Modifier.fillMaxWidth().pressable(enabled = !opening, onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            article.title,
            style = WolfyTheme.typography.screenTitle,
            color = WolfyTheme.colors.ink,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Byline(article)
        if (article.summary.isNotBlank()) {
            Text(article.summary, style = WolfyTheme.typography.body, color = WolfyTheme.colors.ink, maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
        ReadLink(opening)
        Rule()
    }
}

@Composable
private fun SmallStory(article: NewsArticle, opening: Boolean, onOpen: () -> Unit, modifier: Modifier) {
    val spacing = WolfyTheme.spacing
    Column(
        modifier.pressable(enabled = !opening, onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(article.title, style = WolfyTheme.typography.bookTitle, color = WolfyTheme.colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Byline(article)
        if (article.summary.isNotBlank()) {
            Text(article.summary, style = WolfyTheme.typography.body, color = WolfyTheme.colors.inkMuted, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        ReadLink(opening)
    }
}

@Composable
private fun Byline(article: NewsArticle) {
    val line = listOf(article.source, article.author, article.words.takeIf { it > 0 }?.let { "$it слов" }.orEmpty())
        .filter(String::isNotBlank)
        .joinToString(" · ")
    Text(line, style = WolfyTheme.typography.caption, color = WolfyTheme.colors.inkMuted)
}

@Composable
private fun ReadLink(opening: Boolean) {
    Text(
        if (opening) "Набираем…" else "Читать целиком",
        style = WolfyTheme.typography.button,
        color = if (opening) WolfyTheme.colors.inkMuted else WolfyTheme.colors.accent,
    )
}

@Composable
private fun TurnButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style = WolfyTheme.typography.button,
        color = if (enabled) WolfyTheme.colors.accent else WolfyTheme.colors.rule,
        modifier = Modifier.pressable(enabled = enabled, onClick = onClick).padding(WolfyTheme.spacing.small),
    )
}
