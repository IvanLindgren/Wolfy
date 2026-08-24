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
    private val handle: Long = try {
        core.openSessionStrict(
            library = store.load(LIBRARY),
            settings = store.load(SETTINGS),
        )
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
            core.openSession(store.load(LIBRARY), store.load(SETTINGS))
        } catch (_: Exception) {
            throw e
        }
    }

    private val _library = MutableStateFlow(readLibrary())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Выполняет команду, обновляет экраны и пишет изменённое на диск.
     *
     * Писать целиком при каждом изменении выглядит расточительно, но
     * библиотека это десятки килобайт, а отложенная запись теряет прогресс
     * при закрытии приложения свайпом. Прочитанная страница обязана пережить
     * закрытие, и это дороже пары миллисекунд.
     *
     * Что именно изменилось, говорит ядро: смена темы не переписывает список
     * книг, а прокрутка страницы не переписывает настройки. Повторное
     * сохранение слова, которое уже в колоде, не пишет ничего.
     */
    fun run(command: JsonObject): Outcome {
        val outcome = json.decodeFromString<Outcome>(
            core.runCommand(handle, json.encodeToString(JsonObject.serializer(), command)),
        )

        if (outcome.libraryChanged) {
            val state = readLibrary()
            _library.value = state
            store.save(LIBRARY, core.sessionLibrary(handle))
        }
        if (outcome.settingsChanged) {
            _settings.value = readSettings()
            store.save(SETTINGS, core.sessionSettings(handle))
        }
        // Пометка снимается только после записи: снять её раньше значит
        // однажды потерять главу — ядро посчитает, что всё сохранено, и
        // второй раз это состояние уже не предложит.
        if (outcome.changed) {
            core.sessionSaved(handle, outcome.libraryChanged, outcome.settingsChanged)
        }
        return outcome
    }

    /** Ищет слово в скачанном словаре, не перенося правило поиска в Kotlin. */
    fun define(word: String, path: String): Outcome = run(
        command("define") {
            put("word", word)
            put("path", path)
        },
    )

    fun close() {
        core.closeSession(handle)
    }

    private fun readLibrary(): LibraryState = json.decodeFromString(core.sessionLibrary(handle))

    private fun readSettings(): AppSettings = json.decodeFromString(core.sessionSettings(handle))

    private companion object {
        /** Имена записей в хранилище — те же, что были до переезда логики. */
        const val LIBRARY = "library"
        const val SETTINGS = "settings"
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
