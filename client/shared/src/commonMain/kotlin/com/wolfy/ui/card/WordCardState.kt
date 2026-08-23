package com.wolfy.ui.card

import androidx.compose.runtime.Immutable
import com.wolfy.ffi.Finding
import com.wolfy.ffi.DictionaryEntry
import com.wolfy.ffi.Token
import com.wolfy.ffi.WordAnalysis
import com.wolfy.widgets.GraphLink
import com.wolfy.widgets.GraphWord

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
    /** Слова и связи фразы, уже подготовленные вне композиции. */
    val graphWords: List<GraphWord> = emptyList(),
    val graphLinks: List<GraphLink> = emptyList(),
    val translation: TranslationState = TranslationState.Idle,
    val definition: DefinitionState = DefinitionState.Idle,
    /** Лежит ли слово уже в колоде книги. */
    val saved: Boolean = false,
    /**
     * Лежит ли в колоде фраз само предложение.
     *
     * Отдельно от [saved]: слово и фраза сохраняются по отдельности и живут
     * в разных колодах. Читатель, сохранивший слово, часто хочет сохранить и
     * оборот, в котором оно стоит, — и наоборот почти никогда.
     */
    val phraseSaved: Boolean = false,
)

/** Толкование приезжает независимо от перевода: локально либо с сервера. */
@Immutable
sealed interface DefinitionState {
    data object Idle : DefinitionState
    data object Loading : DefinitionState
    data class Ready(val entry: DictionaryEntry) : DefinitionState
    /** Слова нет в базе либо без локального файла сейчас нет сети. */
    data object Missing : DefinitionState
}

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
     * Два перевода, а не один, и это не роскошь. Слово, переведённое отдельно,
     * — это словарная статья: «library — библиотека». Предложение, переведённое
     * целиком, отвечает на другой вопрос: что здесь вообще сказано. Читателю
     * посреди книги нужны оба, и подменять первое вторым нельзя — перевод
     * фразы в строке «что значит слово» выглядит так, будто слово значит всё
     * предложение.
     *
     * @param word перевод самого слова — словарная строка карточки.
     * @param sentence перевод предложения целиком, на язык читателя. Пусто,
     *   если по слову тапнули вне предложения.
     */
    data class Ready(val word: String, val sentence: String = "") : TranslationState

    /**
     * Перевода не будет.
     *
     * Сообщение пишется для читателя, а не для разработчика: «нет сети» вместо
     * кода ошибки. Чтение при этом продолжается — карточка уже показала разбор.
     */
    data class Failed(val message: String) : TranslationState
}
