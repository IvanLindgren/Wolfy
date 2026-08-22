package com.wolfy.data

import com.wolfy.data.library.LibraryStore
import com.wolfy.data.library.currentTimeMillis
import com.wolfy.srs.Intensity
import com.wolfy.srs.Scheduler
import com.wolfy.theme.ReadingTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Настройки приложения.
 *
 * Тема хранится именем, а не номером в перечислении: номер меняется при
 * добавлении новой темы посередине списка, и у читателя, выбравшего сепию,
 * однажды окажется чёрный экран без всякого его участия.
 */
@Serializable
data class AppSettings(
    val theme: String = ReadingTheme.Paper.name,
    /**
     * Множитель размера шрифта читалки.
     *
     * Размер задан в теме и подобран под газетный набор, но зрение у всех
     * разное, а менять кегль в теме значило бы ломать пропорции полосы. Поэтому
     * множитель: он растягивает всё сразу и набор остаётся согласованным.
     */
    val fontScale: Float = 1f,
    /**
     * Клали ли уже демо-книгу.
     *
     * Проверять «библиотека пуста» вместо этого нельзя: читатель, удаливший
     * все свои книги, получил бы демо обратно — и понял бы это как то, что
     * приложение не удалило ничего.
     */
    val demoAdded: Boolean = false,
    /**
     * Интенсивность повторений — именем, по той же причине, что и тема.
     */
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
     * Два числа вместо истории ответов: расписание спрашивает у них только
     * долю верных ([Scheduler.ease]), а история в тысячу записей ездила бы
     * между устройствами каждую синхронизацию ради одного дробного числа.
     */
    val answers: Int = 0,
    val right: Int = 0,
) {
    /** Тема по имени. Незнакомое имя — светлая: она подходит всем. */
    val readingTheme: ReadingTheme
        get() = ReadingTheme.entries.firstOrNull { it.name == theme } ?: ReadingTheme.Paper

    /** Интенсивность по имени. */
    val reviewIntensity: Intensity get() = Intensity.of(intensity)

    /** Поправка сроков под то, как читатель отвечает на самом деле. */
    val ease: Float get() = Scheduler.ease(answers, right)
}

/**
 * Настройки, которые переживают перезапуск.
 *
 * Живут в том же каталоге, что библиотека, но отдельной записью: тема меняется
 * каждый вечер, а библиотека — раз в неделю, и переписывать список книг ради
 * выбора темы незачем.
 */
class Settings(private val store: LibraryStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    fun setTheme(theme: ReadingTheme) {
        update { it.copy(theme = theme.name) }
    }

    /**
     * Заменяет настройки целиком — так они приезжают с другого устройства.
     *
     * Признак «клали ли демо-книгу» при этом сохраняется местный: он про то,
     * что происходило на *этом* устройстве, и приезжать ему неоткуда.
     */
    fun replace(settings: AppSettings) {
        update { settings.copy(demoAdded = it.demoAdded) }
    }

    fun markDemoAdded() {
        update { it.copy(demoAdded = true) }
    }

    fun setFontScale(scale: Float) {
        update { it.copy(fontScale = scale.coerceIn(0.8f, 1.6f)) }
    }

    fun setIntensity(intensity: Intensity) {
        update { it.copy(intensity = intensity.name) }
    }

    /**
     * Учитывает ответ тренировки.
     *
     * Здесь же продлевается серия дней: она про то, что читатель сегодня
     * занимался, а «занимался» — это ответил хотя бы раз. Считать серию по
     * открытию экрана было бы нечестно, а по закрытой колоде — жестоко:
     * человек, у которого сегодня четыре свободных минуты, серию не теряет.
     */
    fun recordAnswer(right: Boolean, now: Long = currentTimeMillis()) {
        val today = localDay(now)
        update { settings ->
            val streak = when (settings.trainedOn) {
                today -> settings.streakDays
                today - 1 -> settings.streakDays + 1
                // Пропуск обрывает серию, и она начинается заново — с
                // сегодняшнего дня, а не с нуля: сегодня-то он занимался.
                else -> 1
            }
            settings.copy(
                trainedOn = today,
                streakDays = streak,
                bestStreak = maxOf(settings.bestStreak, streak),
                answers = settings.answers + 1,
                right = settings.right + if (right) 1 else 0,
            )
        }
    }

    private fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value)
        _state.value = next
        store.save(RECORD, json.encodeToString(next))
    }

    private fun read(): AppSettings {
        val saved = store.load(RECORD) ?: return AppSettings()
        return try {
            json.decodeFromString(saved)
        } catch (e: Exception) {
            // Настройки по умолчанию хуже сохранённых, но лучше падения на
            // старте: приложение откроется, а тему читатель выберет заново.
            AppSettings()
        }
    }

    private companion object {
        const val RECORD = "settings"
    }
}
