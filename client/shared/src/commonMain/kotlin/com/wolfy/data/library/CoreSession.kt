package com.wolfy.data.library

import com.wolfy.data.AppSettings
import com.wolfy.ffi.WolfyCore
import com.wolfy.ffi.DictionaryEntry
import com.wolfy.srs.DeckCount
import com.wolfy.srs.Drill
import com.wolfy.srs.Queue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Сессия ядра: библиотека и настройки читателя.
 *
 * Правила — прочность карточек, сроки повторений, слияние с сервером,
 * дедупликация книг, серия дней, сборка заданий — живут в ядре на Rust и там
 * же проверены тестами. Здесь только перевод: команда собирается в JSON,
 * ответ разбирается обратно.
 *
 * Состояние тоже держит ядро. Отдавать его туда-сюда было бы проще, но
 * библиотека это десятки килобайт, а прогресс чтения пишется при каждой
 * прокрутке.
 *
 * Ни одного правила в этом файле быть не должно. Всё, что похоже на решение —
 * «считать ли это той же книгой», «продлить ли серию» — принимается за
 * границей; если такое решение появится здесь, оно немедленно разойдётся с
 * тем, что делает Android или сервер.
 *
 * Потоки состояния живут здесь же, а не в обёртках над ними. Иначе команда,
 * посланная мимо обёртки — а тренировка шлёт свои напрямую, — меняла бы
 * состояние в ядре, не обновив экран.
 */
class CoreSession(
    private val core: WolfyCore,
    private val store: LibraryStore,
) {
    // Строгое открытие: `null`/empty -> Default, а непустой битый JSON -> ошибка,
    // а не молчаливый Default. После ошибки клиент не должен автоматически
    // сохранять пустое состояние поверх повреждённого (P12). Нативный atomic
    // save (temp -> fsync -> rename) уже гарантирует, что обрыв записи не
    // оставит обрезанный файл, а strict гарантирует, что бит rot не станет
    // пустой библиотекой.
    // Practice (§6) хранится отдельным файлом `practice.json`; пробуем открыть с ним,
    // фолбэк — без него (миграция через settings).
    private val handle: Long = try {
        val lib = store.load(LIBRARY)
        val set = store.load(SETTINGS)
        val prac = store.load(PRACTICE)
        if (prac != null) {
            try {
                core.openSessionStrictWithPractice(lib, set, prac)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                // Если метод отсутствует (старое ядро/мок) — пробуем без practice.
                if (msg.contains("Unresolved") || msg.contains("not implemented") || msg.contains("NoSuchMethod")) {
                    core.openSessionStrict(lib, set)
                } else {
                    throw e
                }
            }
        } else {
            core.openSessionStrict(lib, set)
        }
    } catch (e: Exception) {
        // Совместимость: если strict недоступен (старое ядро) — падаем в lenient.
        // Но если strict бросил из-за повреждённого JSON, не маскируем ошибку
        // пустым состоянием: пробрасываем её наверх, чтобы WolfyApplication
        // показал explicit error, а не перезаписал файл пустым.
        val msg = e.message.orEmpty()
        if (msg.contains("corrupted") || msg.contains("corrupted") || msg.contains("поврежд")) {
            throw e
        }
        // Fallback для ядер без strict (например, в тестах с моком).
        try {
            // пробуем lenient с practice если есть
            val lib = store.load(LIBRARY)
            val set = store.load(SETTINGS)
            val prac = store.load(PRACTICE)
            if (prac != null) {
                try {
                    core.openSessionWithPractice(lib, set, prac)
                } catch (_: Exception) {
                    core.openSession(lib, set)
                }
            } else {
                core.openSession(lib, set)
            }
        } catch (_: Exception) {
            throw e
        }
    }

    private val _library = MutableStateFlow(readLibrary())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // --- §17 Persist performance: generation-aware + conflated background write ---

    // Для тестов можно переопределить scope; в проде — IO.
    // Используем GlobalScope + IO для простоты, но с контролем через Mutex.
    private val persistScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val persistMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingLibraryGen: Long? = null
    private var pendingSettingsGen: Long? = null
    private var pendingPracticeGen: Long? = null
    @Volatile private var isWriting: Boolean = false

    /**
     * Выполняет команду, обновляет экраны и планирует запись изменённого на диск.
     *
     * Писать целиком при каждом изменении выглядит расточительно, но
     * библиотека это десятки килобайт, а отложенная запись теряет прогресс
     * при закрытии приложения свайпом. Прочитанная страница обязана пережить
     * закрытие, и это дороже пары миллисекунд.
     *
     * Что именно изменилось, говорит ядро: смена темы не переписывает список
     * книг, а прокрутка страницы не переписывает настройки. Повторное
     * сохранение слова, которое уже в колоде, не пишет ничего.
     *
     * §17: запись теперь generation-aware и фоновая. `Outcome` несёт поколения
     * снапшота; после успешной атомарной записи `ackSaved(N)` снимает dirty
     * только до N, сохраняя грязность для N+1. Очередь коалесцируется: если
     * пока пишется 20, пришло 21..24 — после 20 достаточно записать 24.
     * Сериализация (`sessionLibrary`/`sessionSettings`) идёт на `Dispatchers.IO`,
     * а не на главном потоке.
     */
    fun run(command: JsonObject): Outcome {
        val outcome = json.decodeFromString<Outcome>(
            core.runCommand(handle, json.encodeToString(JsonObject.serializer(), command)),
        )

        if (outcome.libraryChanged) {
            _library.value = readLibrary()
        }
        if (outcome.settingsChanged) {
            _settings.value = readSettings()
        }
        // Планируем фоновую запись только после обновления потоков, чтобы UI
        // увидел изменения сразу, а диск догнал позже.
        if (outcome.changed) {
            schedulePersist(outcome)
        }
        return outcome
    }

    private fun schedulePersist(outcome: Outcome) {
        // Извлекаем поколения из outcome; фолбэк — через generations() если ядро старое и не отдало.
        val libGen = outcome.libraryGeneration
        val setGen = outcome.settingsGeneration
        val pracGen = outcome.practiceGeneration
        // Если ядро не прислало поколения (старый билд), считаем что запись нужна
        // и используем текущие поколения через sessionGenerations.
        val fallback = try {
            json.decodeFromString<GenerationsDto>(core.sessionGenerations(handle))
        } catch (_: Exception) {
            null
        }
        val effLibGen = libGen ?: if (outcome.libraryChanged) fallback?.library ?: 0L else null
        val effSetGen = setGen ?: if (outcome.settingsChanged) fallback?.settings ?: 0L else null
        val effPracGen = pracGen ?: if (outcome.practiceChanged) fallback?.practice ?: 0L else null
        if (effLibGen == null && effSetGen == null && effPracGen == null) return

        persistScope.launch {
            var shouldStart = false
            persistMutex.withLock {
                if (effLibGen != null) pendingLibraryGen = effLibGen
                if (effSetGen != null) pendingSettingsGen = effSetGen
                if (effPracGen != null) pendingPracticeGen = effPracGen
                if (!isWriting) {
                    isWriting = true
                    shouldStart = true
                }
            }
            if (shouldStart) persistLoop()
        }
    }

    private suspend fun persistLoop() {
        while (true) {
            val libGen: Long?
            val setGen: Long?
            val pracGen: Long?
            persistMutex.withLock {
                libGen = pendingLibraryGen
                setGen = pendingSettingsGen
                pracGen = pendingPracticeGen
                if (libGen == null && setGen == null && pracGen == null) {
                    isWriting = false
                    return
                }
                pendingLibraryGen = null
                pendingSettingsGen = null
                pendingPracticeGen = null
            }
            // Запись каждого домена — атомарно через FileLibraryStore (temp->fsync->rename).
            // Сериализация идёт на IO-потоке (внутри core.sessionLibrary), не на главном.
            libGen?.let { gen ->
                try {
                    // Берём свежий JSON на момент записи; если за время ожидания
                    // пришло новое поколение, мы всё равно запишем свежий снапшот,
                    // но подтвердим именно gen — dirty для более нового останется true
                    // и следующий виток запишет его. Это сохраняет корректность
                    // (§17) ценой одной лишней записи, но без потери данных.
                    val snapshot = core.sessionLibrary(handle)
                    store.save(LIBRARY, snapshot)
                    try {
                        core.sessionAckSaved(handle, gen, -1, -1)
                    } catch (_: Exception) {
                        // fallback для старого ядра без ack: используем bool API
                        try { core.sessionSaved(handle, true, false) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    // Запись не удалась — не подтверждаем, dirty остаётся true,
                    // следующий вызов run снова поставит в очередь.
                }
            }
            setGen?.let { gen ->
                try {
                    val snapshot = core.sessionSettings(handle)
                    store.save(SETTINGS, snapshot)
                    try {
                        core.sessionAckSaved(handle, -1, gen, -1)
                    } catch (_: Exception) {
                        try { core.sessionSaved(handle, false, true) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            pracGen?.let { gen ->
                try {
                    val snapshot = try { core.sessionPractice(handle) } catch (_: Exception) { null }
                    if (snapshot != null) {
                        store.save(PRACTICE, snapshot)
                        try {
                            core.sessionAckSaved(handle, -1, -1, gen)
                        } catch (_: Exception) {
                            try { core.sessionSavedWithPractice(handle, false, false, true) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
            // Проверяем, не появилось ли новое pending пока писали — loop повторит.
        }
    }

    /** Дождаться окончания фоновых записей (для тестов / закрытия). */
    fun flushBlocking(timeoutMs: Long = 5_000L) {
        val deadline = currentTimeMillis() + timeoutMs
        while (currentTimeMillis() < deadline) {
            val busy = runBlocking {
                persistMutex.withLock { isWriting || pendingLibraryGen != null || pendingSettingsGen != null || pendingPracticeGen != null }
            }
            if (!busy) break
            // Небольшая пауза без платформенного Thread — просто спин.
            // На JVM это быстро, проверка каждые несколько мс достаточна.
        }
    }

    /** Корутинная версия flush для тестов. */
    suspend fun flush() {
        while (true) {
            val busy = persistMutex.withLock { isWriting || pendingLibraryGen != null || pendingSettingsGen != null || pendingPracticeGen != null }
            if (!busy) break
            kotlinx.coroutines.delay(10)
        }
    }

    /** Ищет слово в скачанном словаре, не перенося правило поиска в Kotlin. */
    fun define(word: String, path: String): Outcome = run(
        command("define") {
            put("word", word)
            put("path", path)
        },
    )

    fun close() {
        // Дожидаемся фоновых записей, иначе потеряем последнее изменение при закрытии свайпом.
        try { flushBlocking(2000L) } catch (_: Exception) {}
        // Отменяем scope после flush
        try { persistScope.cancel() } catch (_: Exception) {}
        core.closeSession(handle)
    }

    private fun readLibrary(): LibraryState = json.decodeFromString(core.sessionLibrary(handle))

    private fun readSettings(): AppSettings = json.decodeFromString(core.sessionSettings(handle))

    private companion object {
        /** Имена записей в хранилище — те же, что были до переезда логики. */
        const val LIBRARY = "library"
        const val SETTINGS = "settings"
        const val PRACTICE = "practice"
    }
}

/**
 * Ответ ядра на команду.
 *
 * Одна форма на все команды, и лишние поля просто не приходят: разные формы
 * заставили бы вызывающего знать, чего ждать от каждой, — а он и так знает,
 * какую команду послал.
 */
@Serializable
data class Outcome(
    /** Изменилось ли хоть что-нибудь. */
    val changed: Boolean = false,
    val libraryChanged: Boolean = false,
    val settingsChanged: Boolean = false,
    val practiceChanged: Boolean = false,
    /** Что делать с добавляемой книгой: `known`, `attach` или `fresh`. */
    val plan: String? = null,
    val bookId: String? = null,
    val book: LibraryBook? = null,
    val card: Card? = null,
    val cards: List<Card>? = null,
    val books: List<LibraryBook>? = null,
    val shelf: Shelf? = null,
    /** Момент напоминания или `null`, если напоминать не о чем. */
    val at: Long? = null,
    /** Серия дней после засчитанного ответа. */
    val streak: Int? = null,
    val status: DeckCount? = null,
    val queue: Queue? = null,
    val drill: Drill? = null,
    /** Верен ли ответ. */
    val right: Boolean? = null,
    /** Готовый текст — например, снимки страниц, склеенные по правилу. */
    val text: String? = null,
    val definition: DictionaryEntry? = null,
    /** Файл словаря исправен; отсутствие статьи при `true` — обычный ответ. */
    val dictionaryAvailable: Boolean? = null,
    /** Поколения §17 — для generation-aware ack. */
    val libraryGeneration: Long? = null,
    val settingsGeneration: Long? = null,
    val practiceGeneration: Long? = null,
)

@Serializable
private data class GenerationsDto(
    val library: Long = 0L,
    val settings: Long = 0L,
    val practice: Long = 0L,
    val librarySaved: Long = 0L,
    val settingsSaved: Long = 0L,
    val practiceSaved: Long = 0L,
)

/** Собирает команду ядру. */
fun command(op: String, body: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("op", op)
        body()
    }

/**
 * Разбор ответов ядра.
 *
 * `ignoreUnknownKeys` включён намеренно: ядро обновляется вместе с
 * приложением, но пользователь может остаться на старой версии клиента с
 * новым ядром внутри — новое поле не должно ронять разбор.
 */
val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
