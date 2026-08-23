package com.wolfy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wolfy.data.Settings
import com.wolfy.data.AccountSession
import com.wolfy.data.SyncService
import com.wolfy.data.WolfyApi
import com.wolfy.data.library.CoreSession
import com.wolfy.data.library.Library
import com.wolfy.data.library.LibraryBook
import com.wolfy.data.library.createLibraryStore
import com.wolfy.data.dictionary.DictionaryManager
import com.wolfy.data.dictionary.DictionaryStatus
import com.wolfy.data.writeDemoBook
import com.wolfy.ffi.WolfyCore
import com.wolfy.ffi.createWolfyCore
import com.wolfy.platform.PickedBook
import com.wolfy.platform.PickedPhoto
import com.wolfy.platform.compressPhoto
import com.wolfy.platform.fileDropTarget
import com.wolfy.platform.fileNameOf
import com.wolfy.platform.looksLikePhoto
import com.wolfy.platform.readBytes
import com.wolfy.platform.rememberReminderPermission
import com.wolfy.platform.rememberBookPicker
import com.wolfy.platform.rememberPhotoPicker
import com.wolfy.platform.rememberPronouncer
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.srs.Deck
import com.wolfy.srs.TrainingViewModel
import com.wolfy.ui.decks.DecksScreen
import com.wolfy.ui.discovery.DiscoveryScreen
import com.wolfy.ui.discovery.DiscoveryViewModel
import com.wolfy.ui.library.LibraryScreen
import com.wolfy.ui.library.LibraryViewModel
import com.wolfy.ui.library.ShelvesScreen
import com.wolfy.ui.nav.BottomBar
import com.wolfy.ui.nav.Section
import com.wolfy.ui.reader.ReaderScreen
import com.wolfy.ui.reader.ReaderViewModel
import com.wolfy.ui.reference.ReferenceScreen
import com.wolfy.ui.settings.SettingsScreen
import com.wolfy.ui.srs.SrsScreen
import com.wolfy.ui.srs.TrainingScreen
import com.wolfy.widgets.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /**
     * Есть ли у устройства камера, которой снимают страницу.
     *
     * Не «Android или Windows»: на планшете с клавиатурой камера есть, а на
     * настольной машине — веб-камера, которой страницу книги не снять.
     * Решает тот, кто запускает приложение.
     */
    onPhone: Boolean = false,
) {
    var failure by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<Parts?>(null) }

    // Нативная библиотека и сохранённая сессия открываются после первого
    // кадра. Даже на холодном запуске окно появляется сразу, а тяжёлая работа
    // идёт на фоновом потоке вместо блокировки UI.
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.Default) {
            runCatching {
            val core = createWolfyCore()
            val store = createLibraryStore()
            // Одна сессия ядра на приложение: она держит библиотеку и
            // настройки, а Library и Settings — только вид на них.
            val coreSession = CoreSession(core, store)
            val library = Library(coreSession, store)
            val settings = Settings(coreSession)
            val session = AccountSession(store, sessionToken)
            val api = WolfyApi(baseUrl = serverUrl, tokenProvider = { session.token.value })
            val dictionary = DictionaryManager(coreSession, store, api)
            Parts(
                core = core,
                library = library,
                settings = settings,
                sync = SyncService(library, settings, api),
                reader = ReaderViewModel(
                    core = core,
                    api = api,
                    library = library,
                    dictionary = dictionary,
                ),
                catalogue = LibraryViewModel(library, core, api),
                training = TrainingViewModel(library, settings, coreSession),
                session = session,
                discovery = DiscoveryViewModel(api, session, library),
                dictionary = dictionary,
            )
            }
        }
        result.fold(
            onSuccess = { loaded = it },
            onFailure = { failure = it.message ?: "ядро недоступно" },
        )
    }

    val parts = loaded
    val message = failure
    if (parts == null) {
        // Тема здесь ещё не прочитана — хранилище могло не открыться вместе с
        // ядром. Светлая подходит всем и не мешает прочитать сообщение.
        WolfyTheme(theme = ReadingTheme.Paper) {
            if (message == null) Starting() else CoreUnavailable(message)
        }
        return
    }

    val settings by parts.settings.state.collectAsState()
    val activeToken by parts.session.token.collectAsState()
    val dictionaryStatus by parts.dictionary.status.collectAsState()
    val scope = rememberCoroutineScope()

    WolfyTheme(
        theme = settings.readingTheme,
        fontScale = settings.fontScale,
        lineScale = settings.lineScale,
    ) {

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

        Box(Modifier.fillMaxSize()) {
            Shell(
                parts = parts,
                onPhone = onPhone,
                theme = settings.readingTheme,
                fontScale = settings.fontScale,
                lineScale = settings.lineScale,
                onThemeChange = parts.settings::setTheme,
                onFontScaleChange = parts.settings::setFontScale,
                onLineScaleChange = parts.settings::setLineScale,
                serverUrl = serverUrl,
                signedIn = activeToken != null,
            )
            DictionaryOffer(
                status = dictionaryStatus,
                onDownload = { scope.launch { parts.dictionary.download() } },
                onLater = parts.dictionary::dismissOffer,
            )
        }
    }
}

/** Всё, что живёт столько же, сколько само приложение. */
private class Parts(
    val core: WolfyCore,
    val library: Library,
    val settings: Settings,
    val sync: SyncService,
    val reader: ReaderViewModel,
    val catalogue: LibraryViewModel,
    val training: TrainingViewModel,
    val session: AccountSession,
    val discovery: DiscoveryViewModel,
    val dictionary: DictionaryManager,
)

@Composable
private fun Shell(
    parts: Parts,
    onPhone: Boolean,
    theme: ReadingTheme,
    fontScale: Float,
    lineScale: Float,
    onThemeChange: (ReadingTheme) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLineScaleChange: (Float) -> Unit,
    serverUrl: String,
    signedIn: Boolean,
) {
    var section by remember { mutableStateOf(Section.Books) }
    // Открытая книга — состояние оболочки, а не читалки: по ней оболочка
    // решает, показывать библиотеку или страницу, и она же переживает переход
    // в другой раздел и обратно.
    var reading by remember { mutableStateOf<LibraryBook?>(null) }
    val scope = rememberCoroutineScope()
    val pronouncer = rememberPronouncer()
    val dictionaryStatus by parts.dictionary.status.collectAsState()
    // Открытый справочник. Пустая строка — открыт целиком, непустая — на
    // конкретном правиле, ради которого его и позвали из карточки слова.
    var reference by remember { mutableStateOf<String?>(null) }
    // Первый разбор встроенного лексикона заметно тяжелее отрисовки окна.
    // Строим справочник после первого кадра в фоне: библиотека появляется
    // сразу, а справочник к моменту обычного открытия уже готов.
    var articles by remember { mutableStateOf(emptyList<com.wolfy.ffi.Article>()) }
    LaunchedEffect(parts.core) {
        articles = withContext(Dispatchers.Default) {
            runCatching { parts.core.reference() }.getOrElse { emptyList() }
        }
    }

    val catalogue by parts.catalogue.state.collectAsState()
    val readerState by parts.reader.state.collectAsState()
    val hub by parts.training.hub.collectAsState()
    val training by parts.training.training.collectAsState()
    // Разрешение на уведомления спрашивается перед первой тренировкой, а не
    // при запуске: системный диалог показывают один раз за установку, и
    // потратить его на пустой экран — значит не получить разрешения никогда.
    val askForNotifications = rememberReminderPermission()
    // Список слов по книгам: он подробнее хаба и потому лежит под ним.
    var decksOpen by remember { mutableStateOf(false) }

    // Книга, которой не хватает файла: она приехала синхронизацией, и читатель
    // сейчас покажет, где держит его на этом устройстве.
    var attachTo by remember { mutableStateOf<String?>(null) }
    val attach = rememberBookPicker { picked ->
        attachTo?.let { parts.catalogue.attachFile(it, picked) }
        attachTo = null
    }

    val open: (LibraryBook) -> Unit = { book ->
        if (book.readable) {
            reading = book
            section = Section.Books
            parts.reader.open(book)
        } else {
            // Открыть нечего: сервер знает, что читатель на четвёртой главе,
            // но файла у него нет и не будет. Спрашиваем файл вместо того,
            // чтобы молча ничего не сделать.
            attachTo = book.id
            attach()
        }
    }

    val pick = rememberBookPicker(onPicked = parts.catalogue::import)
    // Съёмка страницы: на телефоне камерой, на компьютере — выбором файла.
    // Камеры у настольной машины обычно нет, а страницу всё равно снимают
    // телефоном и переносят.
    val shoot = rememberPhotoPicker(fromCamera = onPhone, onPicked = parts.catalogue::recognize)

    // Распознанную страницу открываем сразу: читатель снимал её, чтобы читать,
    // а не чтобы найти в списке.
    LaunchedEffect(parts) {
        parts.catalogue.recognized.collect { book ->
            reading = book
            section = Section.Books
            parts.reader.open(book)
        }
    }
    val syncStatus by parts.sync.status.collectAsState()

    // Обмен с сервером: при запуске и потом, пока есть что отправлять.
    //
    // Минута, а не секунда: синхронизация нужна, чтобы вечером продолжить на
    // телефоне то, что читал днём за компьютером, и опаздывать на минуту в
    // этой задаче нечем. Опрос чаще жёг бы батарею ради ничего.
    LaunchedEffect(parts, signedIn) {
        parts.sync.sync()
        while (true) {
            delay(60_000)
            if (parts.sync.hasPending()) parts.sync.sync()
        }
    }

    // Экран, который сейчас показан. Отдельным значением, а не набором
    // переменных: анимации перехода нужно знать не только куда идём, но и
    // откуда, а «откуда» к моменту перехода из переменных уже стёрто.
    val route: Route = when (section) {
        Section.Books -> reading?.let(Route::Reader) ?: Route.Library
        Section.Shelves -> Route.Shelves
        Section.Discover -> Route.Discover
        Section.Cards -> when {
            training.running -> Route.Training
            decksOpen -> Route.WordList
            else -> Route.Cards
        }
        Section.More -> reference?.let(Route::Reference) ?: Route.Settings
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(WolfyTheme.colors.paper)
            // Системные панели: газетная полоса доходит до края экрана, но
            // текст под часами и жестовой полосой читать невозможно.
            .systemBarsPadding()
            // Файл, брошенный в окно. Самый естественный способ добавить книгу
            // на компьютере: она лежит в «Загрузках», окно открыто рядом, и
            // диалог выбора после этого — лишний шаг.
            .fileDropTarget { paths -> paths.forEach { drop(parts, it) } },
    ) {
        // Переход между экранами показывается движением, а не подменой.
        // Мгновенная замена содержимого не отвечает на вопрос, что случилось:
        // открылся другой раздел или перерисовался этот. Направление отвечает
        // без единого слова — вглубь приходит справа, назад уходит туда же.
        AnimatedContent(
            targetState = route,
            transitionSpec = { screenTransition(initialState.depth, targetState.depth) },
            label = "screen",
            modifier = Modifier.weight(1f),
        ) { screen ->
            when (screen) {
                Route.Library -> LibraryScreen(
                    state = catalogue,
                    onOpen = open,
                    onImport = pick,
                    onShoot = shoot,
                    onRemove = { parts.catalogue.remove(it.id) },
                )

                is Route.Reader -> ReaderScreen(
                    state = readerState,
                    onWordTap = parts.reader::onWordTap,
                    onDismissCard = parts.reader::dismissCard,
                    onSaveWord = parts.reader::toggleWord,
                    onSavePhrase = parts.reader::savePhrase,
                    onPronounce = {
                        readerState.card?.analysis?.lemma?.let(pronouncer::speak)
                    },
                    onPreviousChapter = parts.reader::previousChapter,
                    onNextChapter = parts.reader::nextChapter,
                    onScrolled = parts.reader::rememberPlace,
                    onChapter = parts.reader::loadChapter,
                    onClose = {
                        parts.reader.closeCurrent()
                        reading = null
                    },
                    onOpenRule = { rule ->
                        parts.reader.dismissCard()
                        reference = rule
                        section = Section.More
                    },
                    theme = theme,
                    fontScale = fontScale,
                    lineScale = lineScale,
                    onThemeChange = onThemeChange,
                    onFontScaleChange = onFontScaleChange,
                    onLineScaleChange = onLineScaleChange,
                )

                Route.Shelves -> ShelvesScreen(
                    state = catalogue,
                    onOpen = open,
                    onCreateShelf = parts.catalogue::addShelf,
                    onRemoveShelf = parts.catalogue::removeShelf,
                    onMove = parts.catalogue::moveToShelf,
                )

                Route.Discover -> DiscoveryScreen(parts.discovery)

                Route.Training -> TrainingScreen(
                    state = training,
                    onAnswer = parts.training::answer,
                    onNext = parts.training::next,
                    onClose = parts.training::stop,
                )

                Route.WordList -> DecksScreen(
                    state = catalogue,
                    onOpenBook = open,
                    onRemoveWord = parts.library::removeWord,
                    onBack = { decksOpen = false },
                )

                Route.Cards -> SrsScreen(
                    state = hub,
                    onTrain = { deck: Deck ->
                        askForNotifications()
                        parts.training.start(deck)
                    },
                    onIntensity = parts.training::setIntensity,
                    onOpenDecks = { decksOpen = true },
                )

                Route.Settings -> SettingsScreen(
                    theme = theme,
                    onThemeChange = onThemeChange,
                    fontScale = fontScale,
                    onFontScaleChange = onFontScaleChange,
                    sync = syncStatus,
                    onSyncNow = { scope.launch { parts.sync.sync() } },
                    coreVersion = remember {
                        runCatching { parts.core.version() }.getOrElse { "?" }
                    },
                    serverUrl = serverUrl,
                    signedIn = signedIn,
                    onOpenReference = { reference = "" },
                    dictionary = dictionaryStatus,
                    onDownloadDictionary = { scope.launch { parts.dictionary.download() } },
                )

                is Route.Reference -> ReferenceScreen(
                    articles = articles,
                    openAt = screen.rule.takeIf { it.isNotEmpty() },
                    onBack = { reference = null },
                )
            }
        }

        BottomBar(selected = section, onSelect = { section = it })
    }
}

/**
 * Экран, показанный сейчас.
 *
 * Не «раздел»: разделов четыре, а экранов восемь — внутри раздела бывает
 * читалка поверх библиотеки, тренировка поверх колод, статья поверх настроек.
 * Анимации перехода нужен именно экран, и вместе с ним — глубина.
 *
 * Глубина говорит, в какую сторону ехать. Библиотека и читалка — не соседи:
 * из первой во вторую входят, и движение обязано это показывать. А два
 * раздела нижней панели равны, между ними никакого «глубже» нет, и подмена
 * там честнее всего выглядит простым проявлением.
 */
@Immutable
private sealed interface Route {
    val depth: Int

    data object Library : Route {
        override val depth: Int get() = 0
    }

    data class Reader(val book: LibraryBook) : Route {
        override val depth: Int get() = 1
    }

    data object Shelves : Route {
        override val depth: Int get() = 0
    }

    data object Discover : Route {
        override val depth: Int get() = 0
    }

    data object Cards : Route {
        override val depth: Int get() = 0
    }

    data object WordList : Route {
        override val depth: Int get() = 1
    }

    data object Training : Route {
        override val depth: Int get() = 1
    }

    data object Settings : Route {
        override val depth: Int get() = 0
    }

    data class Reference(val rule: String) : Route {
        override val depth: Int get() = 1
    }
}

/**
 * Как один экран сменяет другой.
 *
 * Уходящий гасится быстрее, чем приходит новый, и это намеренно: если оба
 * длятся одинаково, посередине перехода видно оба сразу и получается каша.
 * Сдвиг — шестая часть ширины, не вся: экран должен подъехать, а не
 * пролететь. Целая ширина на большом окне превращается в заметную поездку,
 * которую приходится пережидать.
 */
private fun screenTransition(from: Int, to: Int): ContentTransform {
    val appear = tween<Float>(durationMillis = 220)
    val vanish = tween<Float>(durationMillis = 120)
    val slide = tween<IntOffset>(durationMillis = 220)

    val enter = when {
        to > from -> slideInHorizontally(slide) { width -> width / 6 } + fadeIn(appear)
        to < from -> slideInHorizontally(slide) { width -> -width / 6 } + fadeIn(appear)
        else -> fadeIn(appear)
    }

    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = fadeOut(vanish),
        // Без обрезки по размеру: экраны занимают всё доступное место, и
        // подгонять его анимацией не нужно — иначе содержимое дёргается, пока
        // размер едет от старого к новому.
        sizeTransform = SizeTransform(clip = false),
    )
}

/**
 * Разбирает брошенный в окно файл.
 *
 * Снимок отправляется на распознавание, всё остальное добавляется как книга.
 * Различаем по расширению, а не по содержимому: заголовок файла пришлось бы
 * читать целиком, а ошибка здесь дешёвая — читатель сразу увидит, что
 * получилось не то.
 */
private fun drop(parts: Parts, path: String) {
    val name = fileNameOf(path)
    if (looksLikePhoto(name)) {
        val bytes = readBytes(path) ?: return
        parts.catalogue.recognize(PickedPhoto(compressPhoto(bytes), "image/jpeg"))
    } else {
        parts.catalogue.import(PickedBook(path = path, name = name))
    }
}

/** Ненавязчивое предложение: словарь вложен в пакет, но ставится по выбору. */
@Composable
private fun DictionaryOffer(
    status: DictionaryStatus,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    if (status is DictionaryStatus.Ready || status is DictionaryStatus.Declined) return

    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.ink.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(spacing.large)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(spacing.large))
                .padding(spacing.xlarge),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Text("Офлайн-словарь", style = WolfyTheme.typography.bookTitle, color = colors.ink)
            Text(
                "Установить русские переводы слов, английские толкования и МФА? " +
                    "Архив уже входит в приложение, сеть не нужна. После установки " +
                    "словарь занимает около 9 МБ.",
                style = WolfyTheme.typography.body,
                color = colors.inkMuted,
            )

            when (status) {
                is DictionaryStatus.Downloading -> {
                    val progress = status.progress
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Подготовлено ${(progress * 100).toInt()}%",
                            style = WolfyTheme.typography.caption,
                            color = colors.inkMuted,
                        )
                    }
                }
                is DictionaryStatus.Failed -> Text(
                    status.message,
                    style = WolfyTheme.typography.caption,
                    color = colors.accent,
                )
                else -> Unit
            }

            if (status !is DictionaryStatus.Downloading) {
                Text(
                    text = if (status is DictionaryStatus.Failed) "попробовать снова" else "установить",
                    style = WolfyTheme.typography.button,
                    color = colors.accent,
                    modifier = Modifier.pressable(onClick = onDownload).padding(vertical = spacing.small),
                )
                Text(
                    text = "позже",
                    style = WolfyTheme.typography.button,
                    color = colors.inkMuted,
                    modifier = Modifier.pressable(onClick = onLater).padding(vertical = spacing.small),
                )
            }
        }
    }
}

/**
 * Ядро не загрузилось.
 *
 * Отдельный экран, а не молчаливая пустота: без ядра приложение не умеет
 * ничего, и разработчику важно сразу увидеть, что библиотека не собрана.
 */
@Composable
private fun Starting() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(WolfyTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Wolfy", style = WolfyTheme.typography.screenTitle, color = WolfyTheme.colors.ink)
            Text(
                "Готовим библиотеку",
                style = WolfyTheme.typography.body,
                color = WolfyTheme.colors.inkMuted,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

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
