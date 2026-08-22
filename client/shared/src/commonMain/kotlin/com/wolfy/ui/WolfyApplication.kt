package com.wolfy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wolfy.data.WolfyApi
import com.wolfy.data.writeDemoBook
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.createWolfyCore
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.ui.reader.ReaderScreen
import com.wolfy.ui.reader.ReaderViewModel

/**
 * Корень приложения.
 *
 * Пока экран один — читалка с демо-главой. Навигация, библиотека и колоды
 * появятся вокруг него, но именно этот экран отвечает на главный вопрос
 * продукта: открывается ли карточка слова мгновенно и не мешает ли она читать.
 *
 * @param serverUrl адрес сервиса. По умолчанию локальный: контекстный перевод
 *   идёт через свой сервер, потому что ключи провайдеров нельзя класть в
 *   приложение, которое можно распаковать.
 * @param sessionToken токен Читавука. Пока его нет, перевод честно скажет, что
 *   нужен вход, а чтение и разбор слов работают без него.
 */
@Composable
fun WolfyApplication(
    serverUrl: String = "http://localhost:8080",
    sessionToken: String? = null,
    theme: ReadingTheme = ReadingTheme.Paper,
) {
    WolfyTheme(theme = theme) {
        var failure by remember { mutableStateOf<String?>(null) }

        val viewModel = remember {
            try {
                ReaderViewModel(
                    core = createWolfyCore(),
                    api = WolfyApi(baseUrl = serverUrl, tokenProvider = { sessionToken }),
                )
            } catch (e: CoreException) {
                failure = e.message
                null
            }
        }

        val message = failure
        if (viewModel == null || message != null) {
            CoreUnavailable(message ?: "ядро недоступно")
            return@WolfyTheme
        }

        LaunchedEffect(viewModel) {
            viewModel.open(writeDemoBook())
        }

        val state by viewModel.state.collectAsState()

        ReaderScreen(
            state = state,
            onWordTap = viewModel::onWordTap,
            onDismissCard = viewModel::dismissCard,
            onSaveWord = viewModel::saveWord,
            onPreviousChapter = viewModel::previousChapter,
            onNextChapter = viewModel::nextChapter,
        )
    }
}

/**
 * Ядро не загрузилось.
 *
 * Отдельный экран, а не молчаливая пустота: без ядра приложение не умеет
 * ничего, и разработчику важно сразу увидеть, что библиотека не собрана.
 */
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
