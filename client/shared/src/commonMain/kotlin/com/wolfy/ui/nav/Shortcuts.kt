package com.wolfy.ui.nav

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Есть ли у устройства клавиатура.
 *
 * От неё зависит не только сама раскладка, но и то, показывать ли подсказки:
 * номер рядом с вариантом ответа помогает тому, кто может его нажать, и
 * засоряет экран тому, кто не может.
 *
 * Значение приходит сверху, из точки запуска: «Android или Windows» здесь не
 * ответ — к планшету клавиатуру подключают, а к настольной машине нет.
 */
val LocalKeyboard = staticCompositionLocalOf { false }

/**
 * Сочетание клавиш — и для дела, и для подсказки.
 *
 * Один список на оба: раскладка, описанная в одном месте, а показанная из
 * другого, расходится с первой же правкой, и подсказка начинает врать.
 */
@Immutable
data class Shortcut(val keys: String, val title: String)

/** Что умеет читалка. */
val readerShortcuts = listOf(
    Shortcut("Пробел · PgDn · ↓", "Страница вперёд"),
    Shortcut("Shift+Пробел · PgUp · ↑", "Страница назад"),
    Shortcut("← · →", "Предыдущая и следующая глава"),
    Shortcut("Home · End", "В начало и в конец главы"),
    Shortcut("Esc", "Закрыть карточку, потом книгу"),
)

/** Что умеет открытая карточка слова. */
val cardShortcuts = listOf(
    Shortcut("Enter", "Сохранить слово в колоду"),
    Shortcut("S", "Произнести вслух"),
    Shortcut("Esc", "Закрыть карточку"),
)

/** Что работает всегда, на любом экране. */
val globalShortcutList = listOf(
    Shortcut("Ctrl+1 … Ctrl+4", "Перейти в раздел"),
    Shortcut("?", "Показать этот список"),
)

/** Что умеет тренировка. */
val trainingShortcuts = listOf(
    Shortcut("1 … 9", "Выбрать вариант ответа"),
    Shortcut("Enter", "Проверить и перейти к следующему"),
    Shortcut("Esc", "Выйти к колодам"),
)

/**
 * Перехват клавиш до того, как их увидят вложенные элементы.
 *
 * Годится там, где полей ввода нет: в читалке некому спорить за пробел и
 * стрелки, а список внутри забрал бы их себе и прокрутил на одну строку
 * вместо страницы.
 *
 * Обработчик возвращает `true`, если клавишу разобрал: неразобранная уходит
 * дальше, и системные сочетания продолжают работать.
 */
@Composable
fun Modifier.shortcuts(
    enabled: Boolean = true,
    onKey: (KeyEvent) -> Boolean,
): Modifier = withFocus(enabled).onPreviewKeyEvent { event ->
    enabled && event.type == KeyEventType.KeyDown && onKey(event)
}

/**
 * Перехват клавиш после вложенных элементов.
 *
 * Для экранов с полями ввода. В тренировке есть задание «набери слово»: цифры
 * и Enter там принадлежат полю, и отнимать их у него ради сочетаний значит
 * сделать задание невыполнимым. Поле, забравшее клавишу, до этого обработчика
 * её не пропустит.
 */
@Composable
fun Modifier.shortcutsUnlessTyping(
    enabled: Boolean = true,
    onKey: (KeyEvent) -> Boolean,
): Modifier = withFocus(enabled).onKeyEvent { event ->
    enabled && event.type == KeyEventType.KeyDown && onKey(event)
}

/**
 * Делает элемент способным принимать клавиши и просит фокус.
 *
 * Просит при каждом включении, а не один раз: карточка слова забирает фокус
 * себе, и без повторной просьбы первое же нажатие после её закрытия уходило
 * бы в никуда.
 *
 * `requestFocus` на ещё не размещённом элементе бросает исключение — это
 * гонка между композицией и раскладкой, а не ошибка вызывающего, поэтому она
 * гасится: следующее включение попросит снова.
 */
@Composable
private fun Modifier.withFocus(enabled: Boolean): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (enabled) runCatching { requester.requestFocus() }
    }
    return focusRequester(requester).focusable(enabled)
}

/**
 * Клавиши всего приложения — те, что работают на любом экране.
 *
 * Фокуса не просит, и это главное отличие от соседей. Событие клавиши
 * поднимается от того, кто в фокусе, вверх по дереву, так что корню оно
 * достанется и без фокуса — а попроси он фокус, он отобрал бы его у читалки, и
 * пробел перестал бы листать страницу.
 *
 * Отсюда же следует порядок: сначала своё разбирает экран, и только
 * неразобранное доходит сюда. Ctrl+1 из тренировки уводит в раздел, а Esc —
 * нет: его тренировка забрала себе.
 */
@Composable
fun Modifier.globalShortcuts(onKey: (KeyEvent) -> Boolean): Modifier =
    onKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event) }

/**
 * Цифра, нажатая на клавиатуре, — из основного ряда или из блока справа.
 *
 * Оба ряда, потому что на настольной клавиатуре цифру удобнее давать правой
 * рукой, не уходя с блока, а на ноутбуке этого блока просто нет.
 */
fun digitOf(key: Key): Int? = when (key) {
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}
