package com.wolfy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wolfy.data.Settings
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.createLibraryStore
import com.wolfy.data.writeDemoBook
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.WolfyCore
import com.wolfy.ffi.createWolfyCore
import com.wolfy.platform.PickedBook
import com.wolfy.platform.rememberBookPicker
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.ui.decks.DecksScreen
import com.wolfy.ui.library.LibraryScreen
import com.wolfy.ui.library.LibraryViewModel
import com.wolfy.ui.library.ShelvesScreen
import com.wolfy.ui.nav.BottomBar
import com.wolfy.ui.nav.Section
import com.wolfy.ui.reader.ReaderScreen
import com.wolfy.ui.reader.ReaderViewModel
import com.wolfy.ui.settings.SettingsScreen

/**
 * Корень приложения.
 *
 * Четыре раздела внизу и читалка внутри первого из них — так же, как на
 * макете. Читалка не отдельный раздел намеренно: книгу открывают из библиотеки
 * и в библиотеку же возвращаются, и делать из этого переход между равными
 * разделами значит заставлять читателя помнить, где он был.
 *
 * @param serverUrl адрес сервиса. Контекстный перевод идёт через свой сервер,
 *   потому что ключи провайдеров нельзя класть в приложение, которое можно
 *   распаковать.
 * @param sessionToken токен Читавука. Пока его нет, перевод честно скажет, что
 *   нужен вход, а чтение и разбор слов работают без него.
 */
@Composable
fun WolfyApplication(
    serverUrl: String = "http://localhost:8080",
    sessionToken: String? = null,
) {
    var failure by remember { mutableStateOf<String?>(null) }

    val parts = remember {
        try {
            val core = createWolfyCore()
            val store = createLibraryStore()
            val library = Library(store)
            Parts(
                core = core,
                library = library,
                settings = Settings(store),
                reader = ReaderViewModel(
                    core = core,
                    api = WolfyApi(baseUrl = serverUrl, tokenProvider = { sessionToken }),
                    library = library,
                ),
                catalogue = LibraryViewModel(library, core),
            )
        } catch (e: CoreException) {
            failure = e.message
            null
        }
    }

    val message = failure
    if (parts == null || message != null) {
        // Тема здесь ещё не прочитана — хранилище могло не открыться вместе с
        // ядром. Светлая подходит всем и не мешает прочитать сообщение.
        WolfyTheme(theme = ReadingTheme.Paper) {
            CoreUnavailable(message ?: "ядро недоступно")
        }
        return
    }

    val settings by parts.settings.state.collectAsState()

    WolfyTheme(theme = settings.readingTheme, fontScale = settings.fontScale) {

        // Первый запуск: библиотека пуста, и вместо пустого экрана в неё
        // кладётся демо-глава. Она проходит ровно тот же путь, что настоящая
        // книга, — никакого отдельного «режима примера» в читалке нет.
        LaunchedEffect(parts) {
            if (!parts.settings.current.demoAdded) {
                parts.settings.markDemoAdded()
                parts.catalogue.import(
                    PickedBook(path = writeDemoBook(), name = "Старая библиотека.txt"),
                )
            }
        }

        Shell(
            parts = parts,
            theme = settings.readingTheme,
            fontScale = settings.fontScale,
            onThemeChange = parts.settings::setTheme,
            onFontScaleChange = parts.settings::setFontScale,
            serverUrl = serverUrl,
            signedIn = sessionToken != null,
        )
    }
}

/** Всё, что живёт столько же, сколько само приложение. */
private class Parts(
    val core: WolfyCore,
    val library: Library,
    val settings: Settings,
    val reader: ReaderViewModel,
    val catalogue: LibraryViewModel,
)

@Composable
private fun Shell(
    parts: Parts,
    theme: ReadingTheme,
    fontScale: Float,
    onThemeChange: (ReadingTheme) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    serverUrl: String,
    signedIn: Boolean,
) {
    var section by remember { mutableStateOf(Section.Books) }
    // Открытая книга — состояние оболочки, а не читалки: по ней оболочка
    // решает, показывать библиотеку или страницу, и она же переживает переход
    // в другой раздел и обратно.
    var reading by remember { mutableStateOf<LibraryBook?>(null) }

    val catalogue by parts.catalogue.state.collectAsState()
    val readerState by parts.reader.state.collectAsState()

    val open: (LibraryBook) -> Unit = { book ->
        reading = book
        section = Section.Books
        parts.reader.open(book)
    }

    val pick = rememberBookPicker(onPicked = parts.catalogue::import)

    Column(
        Modifier
            .fillMaxSize()
            .background(WolfyTheme.colors.paper)
            // Системные панели: газетная полоса доходит до края экрана, но
            // текст под часами и жестовой полосой читать невозможно.
            .systemBarsPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            when (section) {
                Section.Books -> when (reading) {
                    null -> LibraryScreen(
                        state = catalogue,
                        onOpen = open,
                        onImport = pick,
                        onRemove = { parts.catalogue.remove(it.id) },
                    )

                    else -> ReaderScreen(
                        state = readerState,
                        onWordTap = parts.reader::onWordTap,
                        onDismissCard = parts.reader::dismissCard,
                        onSaveWord = parts.reader::saveWord,
                        onPreviousChapter = parts.reader::previousChapter,
                        onNextChapter = parts.reader::nextChapter,
                        onScrolled = parts.reader::rememberPlace,
                        onChapter = parts.reader::loadChapter,
                        onClose = {
                            parts.reader.closeCurrent()
                            reading = null
                        },
                    )
                }

                Section.Shelves -> ShelvesScreen(
                    state = catalogue,
                    onOpen = open,
                    onCreateShelf = parts.catalogue::addShelf,
                    onRemoveShelf = parts.catalogue::removeShelf,
                    onMove = parts.catalogue::moveToShelf,
                )

                Section.Srs -> DecksScreen(
                    books = catalogue.books,
                    onOpenBook = open,
                )

                Section.More -> SettingsScreen(
                    theme = theme,
                    onThemeChange = onThemeChange,
                    fontScale = fontScale,
                    onFontScaleChange = onFontScaleChange,
                    coreVersion = remember { runCatching { parts.core.version() }.getOrElse { "?" } },
                    serverUrl = serverUrl,
                    signedIn = signedIn,
                )
            }
        }

        BottomBar(selected = section, onSelect = { section = it })
    }
}

/**
 * Ядро не загрузилось.
 *
 * Отдельный экран, а не молчаливая пустота: без ядра приложение не умеет
 * ничего, и разработчику важно сразу увидеть, что библиотека не собрана.
 */
@Composable
private fun CoreUnavailable(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Ядро не загрузилось.\n$message",
            style = WolfyTheme.typography.body,
            color = WolfyTheme.colors.inkMuted,
            modifier = Modifier.padding(WolfyTheme.spacing.pageMargin),
        )
    }
}
