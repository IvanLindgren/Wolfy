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
    val lastSeenVersion: String = "",
    val reduceMotion: Boolean = false,
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

    fun seenVersion(version: String) {
        send(command("seenVersion") { put("version", version) })
    }

    fun setReduceMotion(on: Boolean) {
        send(command("setReduceMotion") { put("on", on) })
    }

    fun setIntensity(intensity: Intensity) {
        send(command("setIntensity") { put("intensity", intensity.name) })
    }

    private fun send(command: kotlinx.serialization.json.JsonObject) {
        session.run(command)
    }
}
