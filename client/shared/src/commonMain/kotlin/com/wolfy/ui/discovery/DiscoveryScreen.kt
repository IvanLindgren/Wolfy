package com.wolfy.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wolfy.data.DiscoveryItem
import com.wolfy.data.library.LibraryBook
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Caption
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.pressable

val DiscoveryGenres = listOf(
    "Fiction",
    "Mystery",
    "Horror",
    "Romance",
    "Science Fiction",
    "Fantasy",
    "Adventure",
    "Travel",
    "History",
    "Philosophy",
    "Biography",
    "Nonfiction",
)

@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel,
    newspaper: NewspaperViewModel,
    onOpenBook: (LibraryBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(DiscoveryPage.Feed) }
    Column(modifier.fillMaxSize().background(WolfyTheme.colors.paper)) {
        DiscoverySwitcher(page = page, onPage = { page = it })
        Box(Modifier.weight(1f)) {
            if (page == DiscoveryPage.Newspaper) {
                NewspaperScreen(newspaper, onOpenBook)
            } else when {
                !state.signedIn -> LoginScreen(
                    state = state,
                    onEmail = viewModel::setEmail,
                    onPassword = viewModel::setPassword,
                    onLogin = viewModel::login,
                    modifier = Modifier,
                )
                state.needsOnboarding -> OnboardingScreen(
                    state = state,
                    onLevel = viewModel::setLevel,
                    onGenre = viewModel::toggleGenre,
                    onContinue = viewModel::saveOnboarding,
                    onLogout = viewModel::logout,
                    modifier = Modifier,
                )
                else -> FeedScreen(
                    state = state,
                    onLike = viewModel::like,
                    onAdd = viewModel::add,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier,
                )
            }
        }
    }
}

private enum class DiscoveryPage { Feed, Newspaper }

@Composable
private fun DiscoverySwitcher(page: DiscoveryPage, onPage: (DiscoveryPage) -> Unit) {
    val spacing = WolfyTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.pageMargin, vertical = spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        listOf(DiscoveryPage.Feed to "Лента", DiscoveryPage.Newspaper to "Газета").forEach { (item, title) ->
            val selected = item == page
            Text(
                text = title,
                style = WolfyTheme.typography.button,
                color = if (selected) WolfyTheme.colors.paper else WolfyTheme.colors.ink,
                modifier = Modifier
                    .background(
                        if (selected) WolfyTheme.colors.ink else WolfyTheme.colors.surface,
                        RoundedCornerShape(spacing.huge),
                    )
                    .border(spacing.rule, WolfyTheme.colors.rule, RoundedCornerShape(spacing.huge))
                    .pressable { onPage(item) }
                    .padding(horizontal = spacing.large, vertical = spacing.small),
            )
        }
    }
}

@Composable
private fun LoginScreen(
    state: DiscoveryUiState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(WolfyTheme.colors.paper), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(WolfyTheme.spacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.large),
        ) {
            SectionLabel("Персональная лента")
            Text("Откройте следующую книгу", style = WolfyTheme.typography.screenTitle, color = WolfyTheme.colors.ink)
            Text(
                "Лента доступна только с общим аккаунтом Читавука. Wolfy не создаёт отдельную учётную запись.",
                style = WolfyTheme.typography.body,
                color = WolfyTheme.colors.inkMuted,
            )
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmail,
                label = { Text("Почта") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPassword,
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            state.message?.let { Message(it) }
            ActionButton(if (state.loading) "Входим…" else "Войти", !state.loading, onLogin)
        }
    }
}

@Composable
private fun OnboardingScreen(
    state: DiscoveryUiState,
    onLevel: (String) -> Unit,
    onGenre: (String) -> Unit,
    onContinue: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(WolfyTheme.colors.paper)
            .verticalScroll(rememberScrollState())
            .padding(WolfyTheme.spacing.pageMargin),
        verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.large),
    ) {
        SectionLabel("Настройка ленты")
        Text("Что вам подходит", style = WolfyTheme.typography.screenTitle, color = WolfyTheme.colors.ink)
        Text(
            "Уровень и жанры сохраняются в аккаунте и появятся на остальных устройствах.",
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.inkMuted,
        )
        Rule()
        SectionLabel("Уровень английского")
        ChoiceRow(values = listOf("A1", "A2", "B1", "B2", "C1", "C2"), selected = setOf(state.level), onSelect = onLevel)
        SectionLabel("Интересующие жанры")
        ChoiceRow(values = DiscoveryGenres, selected = state.genres, onSelect = onGenre)
        state.message?.let { Message(it) }
        ActionButton(if (state.loading) "Сохраняем…" else "Собрать ленту", !state.loading, onContinue)
        Text(
            "Выйти из аккаунта",
            style = WolfyTheme.typography.caption,
            color = WolfyTheme.colors.inkMuted,
            modifier = Modifier.pressable(onClick = onLogout).padding(WolfyTheme.spacing.small),
        )
    }
}

@Composable
private fun FeedScreen(
    state: DiscoveryUiState,
    onLike: (DiscoveryItem) -> Unit,
    onAdd: (DiscoveryItem) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(WolfyTheme.colors.paper)) {
        when {
            state.loading && state.items.isEmpty() -> CircularProgressIndicator(
                color = WolfyTheme.colors.accent,
                modifier = Modifier.align(Alignment.Center),
            )
            state.items.isEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(WolfyTheme.spacing.pageMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.large),
            ) {
                Text(
                    state.message ?: "В ленте пока нет книг.",
                    style = WolfyTheme.typography.body,
                    color = WolfyTheme.colors.inkMuted,
                    textAlign = TextAlign.Center,
                )
                ActionButton("Обновить", true, onRefresh)
            }
            else -> {
                val pager = rememberPagerState(pageCount = { state.items.size })
                VerticalPager(
                    state = pager,
                    contentPadding = PaddingValues(WolfyTheme.spacing.medium),
                    pageSpacing = WolfyTheme.spacing.medium,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    BookStory(
                        item = state.items[page],
                        adding = state.items[page].id in state.adding,
                        onLike = onLike,
                        onAdd = onAdd,
                    )
                }
                Caption(
                    text = "${pager.currentPage + 1} / ${state.items.size}",
                    modifier = Modifier.align(Alignment.TopEnd).padding(WolfyTheme.spacing.large),
                )
                state.message?.let {
                    Text(
                        it,
                        style = WolfyTheme.typography.caption,
                        color = WolfyTheme.colors.ink,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(WolfyTheme.colors.surface, RoundedCornerShape(WolfyTheme.spacing.small))
                            .padding(WolfyTheme.spacing.medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookStory(
    item: DiscoveryItem,
    adding: Boolean,
    onLike: (DiscoveryItem) -> Unit,
    onAdd: (DiscoveryItem) -> Unit,
) {
    val spacing = WolfyTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .background(WolfyTheme.colors.surface, RoundedCornerShape(spacing.large))
            .border(spacing.rule, WolfyTheme.colors.rule, RoundedCornerShape(spacing.large))
            .padding(spacing.xlarge),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel(item.contentType.ifBlank { "book" })
                Text(item.level, style = WolfyTheme.typography.sectionLabel, color = WolfyTheme.colors.accent)
            }
            Rule(thick = true)
            Text(
                item.title,
                style = WolfyTheme.typography.screenTitle,
                color = WolfyTheme.colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.author,
                style = WolfyTheme.typography.bookTitle,
                color = WolfyTheme.colors.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ChoiceRow(values = item.genres.take(5), selected = item.genres.toSet(), onSelect = {})
            Text(
                item.summary,
                style = WolfyTheme.typography.body,
                color = WolfyTheme.colors.ink,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
            Rule()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                ActionButton(
                    text = if (item.liked) "Понравилось" else "Нравится",
                    enabled = !item.liked,
                    onClick = { onLike(item) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = when {
                        item.added -> "В библиотеке"
                        adding -> "Загружаем…"
                        else -> "Добавить книгу"
                    },
                    enabled = !item.added && !adding,
                    onClick = { onAdd(item) },
                    modifier = Modifier.weight(1f),
                )
            }
            Caption("Добавление автоматически отмечает книгу как понравившуюся и уточняет рекомендации.")
        }
    }
}

@Composable
private fun ChoiceRow(values: List<String>, selected: Set<String>, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.small),
    ) {
        values.forEach { value ->
            val active = value in selected
            Text(
                value,
                style = WolfyTheme.typography.caption,
                color = if (active) WolfyTheme.colors.onInverse else WolfyTheme.colors.inkMuted,
                modifier = Modifier
                    .background(
                        if (active) WolfyTheme.colors.inverse else WolfyTheme.colors.paper,
                        RoundedCornerShape(WolfyTheme.spacing.huge),
                    )
                    .border(
                        WolfyTheme.spacing.rule,
                        WolfyTheme.colors.rule,
                        RoundedCornerShape(WolfyTheme.spacing.huge),
                    )
                    .pressable(onClick = { onSelect(value) })
                    .padding(horizontal = WolfyTheme.spacing.medium, vertical = WolfyTheme.spacing.small),
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = WolfyTheme.typography.button,
        color = if (enabled) WolfyTheme.colors.onInverse else WolfyTheme.colors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(
                if (enabled) WolfyTheme.colors.inverse else WolfyTheme.colors.rule,
                RoundedCornerShape(WolfyTheme.spacing.huge),
            )
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = WolfyTheme.spacing.large, vertical = WolfyTheme.spacing.medium),
    )
}

@Composable
private fun Message(text: String) {
    Text(text, style = WolfyTheme.typography.caption, color = WolfyTheme.colors.accent)
}
