package com.wolfy.data

import com.wolfy.data.library.CoreSession
import com.wolfy.data.library.command
import com.wolfy.data.library.json
import com.wolfy.srs.Intensity
import com.wolfy.theme.ReadingTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Настройки приложения.
 *
 * Тема и интенсивность хранятся именем, а не номером в перечислении: номер
 * меняется при добавлении новой темы посередине списка, и у читателя,
 * выбравшего сепию, однажды окажется чёрный экран без всякого его участия.
 *
 * Форма записи совпадает с той, что держит ядро: это одни и те же байты на
 * диске и в синхронизации.
 */
@Serializable
data class AppSettings(
    val theme: String = ReadingTheme.Paper.name,
    /**
     * Множитель размера шрифта читалки.
     *
     * Размер задан в теме и подобран под газетный набор, но зрение у всех
     * разное, а менять кегль в теме значило бы ломать пропорции полосы.
     * Поэтому множитель: он растягивает всё сразу и набор остаётся
     * согласованным.
     */
    val fontScale: Float = 1f,
    /** Множитель межстрочного интервала читалки. */
    val lineScale: Float = 1f,
    val onboardingSeen: Boolean = false,
    /**
     * Открывал ли читатель разбор слова хоть раз.
     *
     * Касание слова — главное действие продукта, и узнать о нём неоткуда:
     * страница книги выглядит как страница книги и ничего не обещает. Читалка
     * один раз подсказывает это сама и навсегда замолкает, как только
     * подсказкой воспользовались. Синхронизируется: научился человек, а не
     * устройство.
     */
    val wordTapSeen: Boolean = false,
    val lastSeenVersion: String = "",
    val reduceMotion: Boolean = false,
    /** Короткие звуки компаньона; не влияет на радио и произношение. */
    val companionSounds: Boolean = true,
    /**
     * Набирать основу слова полужирным.
     *
     * Приём беглого чтения: взгляд цепляется за начало слова, а окончание
     * достраивает сам. Где проходит граница основы — решает ядро, здесь
     * только «включено или нет».
     */
    val emphasizeStems: Boolean = false,
    /**
     * Окно чтения: что оставлять светлым, а что притушить.
     *
     * Именем, а не флагом: режимов будет больше, а `Boolean` пришлось бы
     * менять на строку ровно тогда, когда настройки уже лежат на устройствах.
     * Известные значения — `off`, `sentence`, `paragraph`.
     */
    val focusMode: String = FOCUS_OFF,
    /** Темп ведущей строки, слов в минуту. Ноль — выключена. */
    val pacerWpm: Int = 0,
    /** Размер отрезка чтения в словах. Ноль — отрезки выключены. */
    val segmentWords: Int = 0,
    /** Разделы газеты, интересные читателю. Пустой список — весь номер. */
    val newspaperTopics: List<String> = emptyList(),
    /**
     * Клали ли уже демо-книгу.
     *
     * Проверять «библиотека пуста» вместо этого нельзя: читатель, удаливший
     * все свои книги, получил бы демо обратно — и понял бы это как то, что
     * приложение не удалило ничего.
     */
    val demoAdded: Boolean = false,
    /** Интенсивность повторений — именем, по той же причине, что и тема. */
    val intensity: String = Intensity.Normal.name,
    /**
     * Местный день последней тренировки.
     *
     * День, а не момент: серия считается по календарю читателя. Позанимался
     * в полночь и в час ночи — это два дня подряд, и спорить с его календарём
     * приложению не с руки.
     */
    val trainedOn: Long = 0,
    val streakDays: Int = 0,
    /**
     * Лучшая серия.
     *
     * Хранится отдельно и никогда не уменьшается: пропущенный день обнуляет
     * текущую серию, но не отменяет того, что три недели подряд действительно
     * были.
     */
    val bestStreak: Int = 0,
    /**
     * Сколько ответов дано всего и сколько из них верных.
     *
     * Два числа вместо истории ответов: расписанию нужна от них только доля
     * верных, а история в тысячу записей ездила бы между устройствами каждую
     * синхронизацию ради одного дробного числа.
     */
    val answers: Int = 0,
    val right: Int = 0,
) {
    /** Тема по имени. Незнакомое имя — светлая: она подходит всем. */
    val readingTheme: ReadingTheme
        get() = ReadingTheme.entries.firstOrNull { it.name == theme } ?: ReadingTheme.Paper

    /** Интенсивность по имени. */
    val reviewIntensity: Intensity get() = Intensity.of(intensity)

    /** Режим окна чтения. Незнакомое имя гасит окно, а не включает что попало. */
    val focus: FocusMode
        get() = when (focusMode) {
            "sentence" -> FocusMode.Sentence
            "paragraph" -> FocusMode.Paragraph
            else -> FocusMode.Off
        }
}

/** Окно чтения выключено. */
const val FOCUS_OFF: String = "off"

/**
 * Что притушить вокруг того места, где читатель сейчас.
 *
 * Предложение — единица смысла, абзац — единица мысли. Что из них подходит,
 * зависит от человека и от книги, поэтому выбор оставлен читателю.
 */
enum class FocusMode(val code: String) {
    Off(FOCUS_OFF),
    Sentence("sentence"),
    Paragraph("paragraph"),
}

/**
 * Настройки, которые переживают перезапуск.
 *
 * Лежат в том же хранилище, что библиотека, но отдельной записью: тема
 * меняется каждый вечер, а библиотека — раз в неделю, и переписывать список
 * книг ради выбора темы незачем.
 *
 * Правила — серия дней, счёт ответов, пределы множителей набора, слияние с
 * настройками другого устройства — живут в ядре на Rust. Здесь только поток
 * изменений для экранов.
 */
class Settings(private val session: CoreSession) {
    val state: StateFlow<AppSettings> get() = session.settings

    val current: AppSettings get() = state.value

    fun setTheme(theme: ReadingTheme) {
        send(command("setTheme") { put("theme", theme.name) })
    }

    /**
     * Заменяет настройки целиком — так они приезжают с другого устройства.
     *
     * Признак «клали ли демо-книгу» при этом сохраняется местный: он про то,
     * что происходило на *этом* устройстве, и приезжать ему неоткуда. Следит
     * за этим ядро.
     */
    fun replace(settings: AppSettings) {
        send(
            command("replaceSettings") {
                put("settings", json.encodeToJsonElement(settings))
            },
        )
    }

    fun markDemoAdded() {
        send(command("markDemoAdded"))
    }

    fun setFontScale(scale: Float) {
        send(command("setFontScale") { put("scale", scale) })
    }

    fun setLineScale(scale: Float) {
        send(command("setLineScale") { put("scale", scale) })
    }

    fun seenOnboarding() {
        send(command("seenOnboarding"))
    }

    /** Читатель открыл разбор слова. Идемпотентна: ядро отсеет повтор. */
    fun seenWordTap() {
        send(command("seenWordTap"))
    }

    fun seenVersion(version: String) {
        send(command("seenVersion") { put("version", version) })
    }

    fun setReduceMotion(on: Boolean) {
        send(command("setReduceMotion") { put("on", on) })
    }

    fun setCompanionSounds(on: Boolean) {
        send(command("setCompanionSounds") { put("on", on) })
    }

    fun setIntensity(intensity: Intensity) {
        send(command("setIntensity") { put("intensity", intensity.name) })
    }

    fun setEmphasizeStems(on: Boolean) {
        send(command("setEmphasizeStems") { put("on", on) })
    }

    fun setFocusMode(mode: FocusMode) {
        send(command("setFocusMode") { put("mode", mode.code) })
    }

    /** Ноль выключает ведущую строку; пределы ставит ядро. */
    fun setPacer(wpm: Int) {
        send(command("setPacer") { put("wpm", wpm) })
    }

    /** Ноль выключает отрезки чтения. */
    fun setSegmentWords(words: Int) {
        send(command("setSegmentWords") { put("words", words) })
    }

    fun setNewspaperTopics(topics: List<String>) {
        send(
            command("setNewspaperTopics") {
                put("topics", json.encodeToJsonElement(topics))
            },
        )
    }

    private fun send(command: kotlinx.serialization.json.JsonObject) {
        session.run(command)
    }
}
