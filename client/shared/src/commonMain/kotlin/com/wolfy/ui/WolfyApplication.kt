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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wolfy.data.Settings
import com.wolfy.data.AccountSession
import com.wolfy.data.SyncService
import com.wolfy.data.WolfyApi
import com.wolfy.data.DeviceInfo
import com.wolfy.data.AuthOutcome
import com.wolfy.data.Capabilities
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
import com.wolfy.platform.rememberCoverPicker
import com.wolfy.platform.rememberReminderPermission
import com.wolfy.platform.rememberBookPicker
import com.wolfy.platform.rememberPhotoPicker
import com.wolfy.platform.rememberPronouncer
import com.wolfy.platform.rememberBrowserAuthLauncher
import com.wolfy.platform.deviceName
import com.wolfy.platform.devicePlatform
import com.wolfy.platform.rememberAppUpdateController
import com.wolfy.platform.AppUpdateController
import androidx.compose.runtime.DisposableEffect
import com.wolfy.data.library.LibraryStore
import com.wolfy.data.loadRadio
import com.wolfy.data.saveRadio
import com.wolfy.platform.createRadioPlayer
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.theme.WolfyMotion
import com.wolfy.theme.Curves
import com.wolfy.srs.Deck
import com.wolfy.srs.TrainingViewModel
import com.wolfy.ui.decks.DecksScreen
import com.wolfy.ui.discovery.DiscoveryScreen
import com.wolfy.ui.discovery.DiscoveryViewModel
import com.wolfy.ui.discovery.NewspaperViewModel
import com.wolfy.ui.library.CatalogScreen
import com.wolfy.ui.library.LibraryScreen
import com.wolfy.ui.library.LibraryViewModel
import com.wolfy.ui.library.ShelvesScreen
import com.wolfy.ui.nav.BottomBar
import com.wolfy.ui.nav.LocalKeyboard
import com.wolfy.ui.nav.globalShortcuts
import com.wolfy.ui.nav.digitOf
import com.wolfy.ui.nav.ShortcutsSheet
import com.wolfy.ui.nav.Section
import com.wolfy.ui.nav.FLIGHT_CARDS
import com.wolfy.ui.reader.ReaderScreen
import com.wolfy.ui.reader.ReaderViewModel
import com.wolfy.ui.reference.ReferenceScreen
import com.wolfy.ui.settings.SettingsScreen
import com.wolfy.ui.account.AuthMode
import com.wolfy.ui.account.SignInScreen
import com.wolfy.ui.onboarding.WelcomeScreen
import com.wolfy.ui.srs.SrsScreen
import com.wolfy.ui.srs.TrainingScreen
import com.wolfy.widgets.pressable
import com.wolfy.widgets.FlightController
import com.wolfy.widgets.FlightOverlay
import com.wolfy.widgets.LocalFlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope

/**
 * Версия по умолчанию — та, что зашита в общий модуль.
 *
 * Обычно её перебивает платформа: Android передаёт `BuildConfig.VERSION_NAME`,
 * десктоп — запечённое сборкой `-Dwolfy.version`. Значение здесь остаётся
 * последним доводом для запуска из исходников и тестов.
 */
const val APP_VERSION = "1.0.10"

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
    currentVersion: String = APP_VERSION,
    onExitForUpdate: () -> Unit = {},
    /**
     * Есть ли у устройства камера, которой снимают страницу.
     *
     * Не «Android или Windows»: на планшете с клавиатурой камера есть, а на
     * настольной машине — веб-камера, которой страницу книги не снять.
     * Решает тот, кто запускает приложение.
     */
    onPhone: Boolean = false,
) {
    val updater = rememberAppUpdateController(serverUrl, currentVersion)
    LaunchedEffect(updater) { updater.monitor() }
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
            val api = WolfyApi(
                baseUrl = serverUrl,
                tokenProvider = { session.token.value },
                deviceProvider = {
                    DeviceInfo(session.deviceId(), deviceName(), devicePlatform())
                },
            )
            val dictionary = DictionaryManager(coreSession, store, api)
            Parts(
                core = core,
                coreSession = coreSession,
                library = library,
                settings = settings,
                sync = SyncService(library, settings, api),
                reader = ReaderViewModel(
                    core = core,
                    api = api,
                    library = library,
                    dictionary = dictionary,
                ),
                catalogue = LibraryViewModel(library, core, api, store),
                store = store,
                training = TrainingViewModel(library, settings, coreSession),
                session = session,
                discovery = DiscoveryViewModel(api, session, library),
                newspaper = NewspaperViewModel(api, library),
                dictionary = dictionary,
                api = api,
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

    // `remember` не владеет нативными ресурсами: при закрытии окна Compose
    // просто выбросит дерево. Явно закрываем сессию Rust, фоновые записи и
    // HTTP-пул, чтобы последний прогресс успел попасть на диск, а файл книги
    // не остался открытым в Windows.
    DisposableEffect(parts) {
        onDispose { parts.close() }
    }

    val settings by parts.settings.state.collectAsState()
    val activeToken by parts.session.token.collectAsState()
    val accountProfile by parts.session.profile.collectAsState()
    val dictionaryStatus by parts.dictionary.status.collectAsState()
    val scope = rememberCoroutineScope()
    var authMode by remember { mutableStateOf(AuthMode.SignIn) }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var awaitingEmail by remember { mutableStateOf("") }
    var capabilities by remember { mutableStateOf(Capabilities()) }
    val browserAuth = rememberBrowserAuthLauncher()
    LaunchedEffect(parts.api) { capabilities = parts.api.capabilities() }

    fun acceptAuth(outcome: AuthOutcome) {
        when (outcome) {
            is AuthOutcome.SignedIn -> parts.session.save(outcome.token, outcome.email, outcome.name)
            is AuthOutcome.AwaitingEmail -> {
                awaitingEmail = outcome.email
                authMode = AuthMode.AwaitingEmail
            }
            is AuthOutcome.EmailNotConfirmed -> {
                awaitingEmail = outcome.email
                authMode = AuthMode.AwaitingEmail
            }
            is AuthOutcome.Refused -> authError = outcome.message
            AuthOutcome.Offline -> authError = "Нет связи с сервером."
        }
    }

    // Клавиатура объявляется один раз на всё приложение: от неё зависят и
    // сочетания клавиш, и подсказки к ним. `onPhone` уже отвечает на тот же
    // вопрос с другой стороны — у телефона её нет.
    val flight = remember { FlightController() }
    CompositionLocalProvider(
        LocalKeyboard provides !onPhone,
        LocalFlight provides flight,
    ) {
    WolfyTheme(
        theme = settings.readingTheme,
        fontScale = settings.fontScale,
        lineScale = settings.lineScale,
        reduceMotion = settings.reduceMotion,
    ) {
        Box(Modifier.fillMaxSize()) {
        when {
            !settings.onboardingSeen -> WelcomeScreen(
                analysis = remember(parts.core) { parts.core.analyzeWord("serendipity") },
                exercise = remember(parts.core) { parts.core.exercises().firstOrNull() },
                dictionary = dictionaryStatus,
                onDownloadDictionary = { scope.launch { parts.dictionary.download() } },
                onFinish = {
                    parts.settings.seenOnboarding()
                    parts.settings.seenVersion(APP_VERSION)
                },
                onSkip = {
                    parts.settings.seenOnboarding()
                    parts.settings.seenVersion(APP_VERSION)
                },
            )

            activeToken == null && !accountProfile.skipped -> SignInScreen(
                mode = authMode,
                busy = authBusy,
                error = authError,
                canRegister = capabilities.register,
                canGoogle = capabilities.google,
                canYandex = capabilities.yandex,
                awaitingEmail = awaitingEmail,
                onMode = {
                    authMode = it
                    authError = null
                },
                onSkip = parts.session::skip,
                onGoogle = {
                    scope.launch {
                        authBusy = true
                        authError = null
                        acceptAuth(parts.api.signInWithGoogle(browserAuth))
                        authBusy = false
                    }
                },
                onYandex = {
                    scope.launch {
                        authBusy = true
                        authError = null
                        acceptAuth(parts.api.signInWithYandex(browserAuth))
                        authBusy = false
                    }
                },
                onResend = { email ->
                    scope.launch {
                        authBusy = true
                        authError = if (parts.api.resendVerification(email)) {
                            "Письмо отправлено ещё раз."
                        } else {
                            "Не получилось отправить письмо. Проверьте соединение."
                        }
                        authBusy = false
                    }
                },
                onSubmit = { email, password, name ->
                    scope.launch {
                        authBusy = true
                        authError = null
                        val outcome = if (authMode == AuthMode.SignUp) {
                            parts.api.signUp(email, password, name)
                        } else {
                            parts.api.signIn(email, password)
                        }
                        acceptAuth(outcome)
                        authBusy = false
                    }
                },
            )

            else -> {
                // Первый запуск: библиотека пуста, и вместо пустого экрана в неё
                // кладётся демо-глава. Она проходит тот же путь, что настоящая.
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
                accountEmail = accountProfile.email,
                reduceMotion = settings.reduceMotion,
                onReduceMotion = parts.settings::setReduceMotion,
                onSignIn = parts.session::requestSignIn,
                onSignOut = parts.session::clear,
                updateController = updater,
            )
                    DictionaryOffer(
                        status = dictionaryStatus,
                        onDownload = { scope.launch { parts.dictionary.download() } },
                        onLater = parts.dictionary::dismissOffer,
                    )
                }
            }
        }
        UpdateReadyButton(
            controller = updater,
            onRestart = {
                scope.launch {
                    if (runCatching { updater.install() }.getOrDefault(false)) {
                        onExitForUpdate()
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(WolfyTheme.spacing.pageMargin),
        )
        }
    }
    }
}

/** Всё, что живёт столько же, сколько само приложение. */
private class Parts(
    val core: WolfyCore,
    val coreSession: CoreSession,
    val library: Library,
    val settings: Settings,
    val sync: SyncService,
    val reader: ReaderViewModel,
    val catalogue: LibraryViewModel,
    val training: TrainingViewModel,
    val session: AccountSession,
    val discovery: DiscoveryViewModel,
    val newspaper: NewspaperViewModel,
    /**
     * Хранилище устройства.
     *
     * Нужно тем настройкам, которые нарочно не синхронизируются: громкость
     * радио, подобранная в тишине кабинета, в метро оказывается неслышной.
     */
    val store: LibraryStore,
    val dictionary: DictionaryManager,
    val api: WolfyApi,
) {
    /**
     * Ресурсы, не принадлежащие Compose: файл открытой книги, нативная
     * сессия и HTTP-пул. Закрываются один раз вместе с корнем приложения.
     */
    fun close() {
        reader.closeCurrent()
        reader.flushProgressBlocking()
        coreSession.close()
        api.close()
    }
}

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
    accountEmail: String,
    reduceMotion: Boolean,
    onReduceMotion: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    updateController: AppUpdateController,
) {
    var section by remember { mutableStateOf(Section.Books) }
    val flightController = LocalFlight.current
    LaunchedEffect(section) {
        if (section == Section.Cards) flightController.clearArrivals(FLIGHT_CARDS)
    }
    // Открытая книга — состояние оболочки, а не читалки: по ней оболочка
    // решает, показывать библиотеку или страницу, и она же переживает переход
    // в другой раздел и обратно.
    var reading by remember { mutableStateOf<LibraryBook?>(null) }
    val scope = rememberCoroutineScope()
    var homeRefreshing by remember { mutableStateOf(false) }
    fun refreshHome() {
        if (homeRefreshing) return
        scope.launch {
            homeRefreshing = true
            try {
                // Проверка обновления не зависит от учётной записи. Обмен
                // библиотекой, наоборот, не уходит в сеть без токена.
                coroutineScope {
                    if (signedIn) launch { parts.sync.sync() }
                    launch { updateController.checkNow() }
                }
            } finally {
                homeRefreshing = false
            }
        }
    }
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

    /*
     * Настройки чтения: якорь слова, окно, ведущая строка, отрезок.
     *
     * Читалке передаётся не весь объект настроек, а только то, что ей нужно:
     * иначе смена темы перерисовывала бы главу.
     */
    /*
     * Радио.
     *
     * Проигрыватель живёт при приложении, а не при экране: фон не должен
     * замолкать оттого, что читатель ушёл из книги в колоды. Отпускается он
     * вместе с приложением — звуковое устройство надо вернуть системе.
     */
    val radio = remember { createRadioPlayer() }
    val radioState by radio.state.collectAsState()
    var radioPreferences by remember { mutableStateOf(parts.store.loadRadio()) }
    var radioSaveJob by remember { mutableStateOf<Job?>(null) }
    fun saveRadio(preferences: com.wolfy.data.RadioPreferences) {
        // При нескольких быстрых кликах старая ожидающая запись уже не нужна.
        // Сам I/O живёт вне главного потока; FileLibraryStore всё равно пишет
        // атомарно, поэтому последняя завершившаяся запись остаётся целой.
        radioSaveJob?.cancel()
        radioSaveJob = scope.launch(Dispatchers.IO) { parts.store.saveRadio(preferences) }
    }
    LaunchedEffect(radio) { radio.setVolume(radioPreferences.volume) }
    DisposableEffect(radio) { onDispose { radio.release() } }

    val readingSettings by parts.settings.state.collectAsState()
    LaunchedEffect(readingSettings.emphasizeStems) {
        parts.reader.setEmphasizeStems(readingSettings.emphasizeStems)
    }
    LaunchedEffect(readingSettings.segmentWords) {
        parts.reader.setSegmentWords(readingSettings.segmentWords)
    }

    val catalogue by parts.catalogue.state.collectAsState()
    val readerState by parts.reader.state.collectAsState()
    val readerProgress by parts.reader.withinChapterProgress.collectAsState()
    val readerImages by parts.reader.images.collectAsState()
    val hub by parts.training.hub.collectAsState()
    val training by parts.training.training.collectAsState()
    // Разрешение на уведомления спрашивается перед первой тренировкой, а не
    // при запуске: системный диалог показывают один раз за установку, и
    // потратить его на пустой экран — значит не получить разрешения никогда.
    val askForNotifications = rememberReminderPermission()
    // Список слов по книгам: он подробнее хаба и потому лежит под ним.
    var decksOpen by remember { mutableStateOf(false) }

    // Список сочетаний клавиш. Только там, где эти клавиши есть.
    val hasKeyboard = LocalKeyboard.current
    var helpOpen by remember { mutableStateOf(false) }

    // Книга, которой не хватает файла: она приехала синхронизацией, и читатель
    // сейчас покажет, где держит его на этом устройстве.
    var attachTo by remember { mutableStateOf<String?>(null) }
    val attach = rememberBookPicker { picked ->
        attachTo?.let { parts.catalogue.attachFile(it, picked) }
        attachTo = null
    }

    // Каталог Открытой библиотеки и обложки. Обе истории живут внутри раздела
    // книг: каталог — второй способ пополнить библиотеку, обложка — способ
    // узнавать её книги в лицо.
    var catalogOpen by remember { mutableStateOf(false) }
    var coverTarget by remember { mutableStateOf<String?>(null) }
    val pickCover = rememberCoverPicker { picked ->
        coverTarget?.let { parts.catalogue.setCover(it, picked) }
        coverTarget = null
    }
    val catalog by parts.catalogue.catalog.collectAsState()

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

    // Распознанную страницу и только что скачанную книгу открываем сразу:
    // читатель их добыл ради чтения, а не ради строчки в списке.
    LaunchedEffect(parts) {
        launch {
            parts.catalogue.recognized.collect { open(it) }
        }
        launch {
            parts.catalogue.addedFromCatalog.collect { book ->
                catalogOpen = false
                open(book)
            }
        }
    }
    val syncStatus by parts.sync.status.collectAsState()
    val motion = WolfyTheme.motion

    // Обмен с сервером: только после входа. Вышедшему из аккаунта незачем
    // каждую минуту посылать библиотеку без токена; проверка обновления
    // остаётся независимой и живёт выше, в AppUpdateController.
    //
    // Минута, а не секунда: синхронизация нужна, чтобы вечером продолжить на
    // телефоне то, что читал днём за компьютером, и опаздывать на минуту в
    // этой задаче нечем. Опрос чаще жёг бы батарею ради ничего.
    LaunchedEffect(parts, signedIn) {
        if (!signedIn) return@LaunchedEffect
        parts.sync.sync()
        while (true) {
            delay(60_000)
            if (parts.sync.hasPending()) parts.sync.sync(waitForRunning = false)
        }
    }

    // Экран, который сейчас показан. Отдельным значением, а не набором
    // переменных: анимации перехода нужно знать не только куда идём, но и
    // откуда, а «откуда» к моменту перехода из переменных уже стёрто.
    val route: Route = when (section) {
        Section.Books -> when {
            catalogOpen -> Route.Catalog
            reading != null -> Route.Reader(reading!!)
            else -> Route.Library
        }
        Section.Shelves -> Route.Shelves
        Section.Discover -> Route.Discover
        Section.Cards -> when {
            training.running -> Route.Training
            decksOpen -> Route.WordList
            else -> Route.Cards
        }
        Section.More -> reference?.let(Route::Reference) ?: Route.Settings
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(WolfyTheme.colors.paper)
            // Клавиши всего приложения — последними в очереди: сначала своё
            // разбирает открытый экран, сюда доходит только то, что он не
            // взял. Иначе Ctrl+2 из читалки уводил бы в раздел прямо посреди
            // набора, а пробел переставал бы листать страницу.
            .globalShortcuts { event ->
                when {
                    event.isCtrlPressed -> {
                        val target = digitOf(event.key)?.let { Section.entries.getOrNull(it - 1) }
                        if (target != null) {
                            section = target
                            // Переход в раздел выводит из книги: иначе
                            // «Книги» показали бы ту же открытую страницу, и
                            // казалось бы, что нажатие не сработало.
                            if (target != Section.Books) reading = null
                            true
                        } else {
                            false
                        }
                    }

                    // «?» — это Shift и косая черта на большинстве раскладок.
                    hasKeyboard && event.key == Key.Slash && event.isShiftPressed -> {
                        helpOpen = !helpOpen
                        true
                    }

                    else -> false
                }
            }
            // Системные панели: газетная полоса доходит до края экрана, но
            // текст под часами и жестовой полосой читать невозможно.
            .systemBarsPadding()
            // Файл, брошенный в окно. Самый естественный способ добавить книгу
            // на компьютере: она лежит в «Загрузках», окно открыто рядом, и
            // диалог выбора после этого — лишний шаг.
            .fileDropTarget { paths -> scope.launch { paths.forEach { drop(parts, it) } } },
    ) {
        // Переход между экранами показывается движением, а не подменой.
        // Мгновенная замена содержимого не отвечает на вопрос, что случилось:
        // открылся другой раздел или перерисовался этот. Направление отвечает
        // без единого слова — вглубь приходит справа, назад уходит туда же.
        AnimatedContent(
            targetState = route,
            transitionSpec = { screenTransition(initialState.depth, targetState.depth, motion) },
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
                    onRequestCover = { book ->
                        coverTarget = book.id
                        pickCover()
                    },
                    onClearCover = { parts.catalogue.clearCover(it.id) },
                    onCatalog = { catalogOpen = true },
                    coverOf = parts.catalogue::coverFor,
                    onCoverVisible = parts.catalogue::requestCover,
                    isRefreshing = homeRefreshing,
                    onRefresh = ::refreshHome,
                )

                is Route.Catalog -> CatalogScreen(
                    state = catalog,
                    onQuery = parts.catalogue::typeQuery,
                    onSearch = { parts.catalogue.searchCatalogue(catalog.query) },
                    onDownload = parts.catalogue::downloadCatalogue,
                    onOpen = open,
                    onBack = { catalogOpen = false },
                )

                is Route.Reader -> ReaderScreen(
                    state = readerState,
                    withinChapterProgress = readerProgress,
                    images = readerImages,
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
                    onImageVisible = parts.reader::loadImage,
                    theme = theme,
                    fontScale = fontScale,
                    lineScale = lineScale,
                    onThemeChange = onThemeChange,
                    onFontScaleChange = onFontScaleChange,
                    onLineScaleChange = onLineScaleChange,
                    emphasizeStems = readingSettings.emphasizeStems,
                    onEmphasizeStems = parts.settings::setEmphasizeStems,
                    focusMode = readingSettings.focus,
                    onFocusModeChange = parts.settings::setFocusMode,
                    pacerWpm = readingSettings.pacerWpm,
                    onPacerChange = parts.settings::setPacer,
                    segmentWords = readingSettings.segmentWords,
                    onSegmentWordsChange = parts.settings::setSegmentWords,
                    onNextSegment = parts.reader::planSegment,
                    onStopSegments = { parts.settings.setSegmentWords(0) },
                )

                Route.Shelves -> ShelvesScreen(
                    state = catalogue,
                    onOpen = open,
                    onCreateShelf = parts.catalogue::addShelf,
                    onRemoveShelf = parts.catalogue::removeShelf,
                    onMove = parts.catalogue::moveToShelf,
                )

                Route.Discover -> DiscoveryScreen(parts.discovery, parts.newspaper, open)

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
                    appVersion = APP_VERSION,
                    signedIn = signedIn,
                    accountEmail = accountEmail,
                    reduceMotion = reduceMotion,
                    onReduceMotion = onReduceMotion,
                    emphasizeStems = readingSettings.emphasizeStems,
                    onEmphasizeStems = parts.settings::setEmphasizeStems,
                    focusMode = readingSettings.focus,
                    onFocusMode = parts.settings::setFocusMode,
                    pacerWpm = readingSettings.pacerWpm,
                    onPacer = parts.settings::setPacer,
                    segmentWords = readingSettings.segmentWords,
                    onSegmentWords = parts.settings::setSegmentWords,
                    radio = radioState,
                    radioOwnUrl = radioPreferences.ownUrl,
                    onRadioStation = { station ->
                        radio.play(station)
                        radioPreferences = radioPreferences.copy(stationId = station.id)
                        saveRadio(radioPreferences)
                    },
                    onRadioStop = radio::stop,
                    onRadioVolume = { volume ->
                        radio.setVolume(volume)
                        radioPreferences = radioPreferences.copy(volume = volume)
                        saveRadio(radioPreferences)
                    },
                    onRadioOwnUrl = { url ->
                        radioPreferences = radioPreferences.copy(ownUrl = url)
                        saveRadio(radioPreferences)
                    },
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
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

        ShortcutsSheet(visible = helpOpen, onDismiss = { helpOpen = false })
        FlightOverlay()
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

    /** Каталог Открытой библиотеки — внутри раздела книг, глубже библиотеки. */
    data object Catalog : Route {
        override val depth: Int get() = 1
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
private fun screenTransition(from: Int, to: Int, motion: WolfyMotion): ContentTransform {
    val appear = tween<Float>(durationMillis = motion.calm, easing = Curves.Paper)
    val vanish = tween<Float>(durationMillis = motion.quick, easing = Curves.Paper)
    val slide = tween<IntOffset>(durationMillis = motion.calm, easing = Curves.Paper)

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
private suspend fun drop(parts: Parts, path: String) {
    val name = fileNameOf(path)
    if (looksLikePhoto(name)) {
        val photo = withContext(Dispatchers.Default) {
            val bytes = readBytes(path) ?: return@withContext null
            PickedPhoto(compressPhoto(bytes), "image/jpeg")
        } ?: return
        parts.catalogue.recognize(photo)
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
