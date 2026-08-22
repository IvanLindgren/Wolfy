package com.wolfy.ui.card

import androidx.compose.runtime.Immutable
import com.wolfy.ffi.Finding
import com.wolfy.ffi.Token
import com.wolfy.ffi.WordAnalysis

/**
 * Состояние открытой карточки слова.
 *
 * Разбор здесь уже есть — он посчитан локально в момент тапа. Перевод хранится
 * отдельным полем со своим состоянием именно потому, что приезжает позже и
 * может не приехать вовсе: без сети карточка остаётся полезной.
 */
@Immutable
data class WordCardState(
    /** Токен, по которому нажали: нужен, чтобы подсветить слово на странице. */
    val token: Token,
    val analysis: WordAnalysis,
    /** Предложение вокруг слова — контекст перевода. */
    val context: String,
    /**
     * Грамматика этого предложения: время, залог, модальность, условие.
     *
     * Считается ядром на устройстве вместе с разбором слова и потому есть
     * сразу — в отличие от перевода, которого приходится ждать. Пустой список
     * значит, что разбирать во фразе нечего, и это нормальный ответ: не в
     * каждом предложении есть чему учиться.
     */
    val grammar: List<Finding> = emptyList(),
    val translation: TranslationState = TranslationState.Idle,
    /** Лежит ли слово уже в колоде книги. */
    val saved: Boolean = false,
)

/** Что сейчас с переводом. */
@Immutable
sealed interface TranslationState {
    /** Перевод ещё не запрашивали. */
    data object Idle : TranslationState

    /** Запрос ушёл на сервер. */
    data object Loading : TranslationState

    /**
     * Перевод пришёл.
     *
     * @param text перевод самого слова или фразы.
     * @param context перевод предложения целиком — то, ради чего слово вообще
     *   переводится в контексте, а не по словарю.
     */
    data class Ready(val text: String, val context: String = "") : TranslationState

    /**
     * Перевода не будет.
     *
     * Сообщение пишется для читателя, а не для разработчика: «нет сети» вместо
     * кода ошибки. Чтение при этом продолжается — карточка уже показала разбор.
     */
    data class Failed(val message: String) : TranslationState
}
