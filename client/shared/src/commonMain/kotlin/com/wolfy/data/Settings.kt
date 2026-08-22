package com.wolfy.data

import com.wolfy.data.library.LibraryStore
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
) {
    /** Тема по имени. Незнакомое имя — светлая: она подходит всем. */
    val readingTheme: ReadingTheme
        get() = ReadingTheme.entries.firstOrNull { it.name == theme } ?: ReadingTheme.Paper
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
