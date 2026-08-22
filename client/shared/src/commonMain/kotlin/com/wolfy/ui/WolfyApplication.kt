package com.wolfy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wolfy.ffi.CoreException
import com.wolfy.ffi.WordAnalysis
import com.wolfy.ffi.createWolfyCore
import com.wolfy.theme.ReadingTheme
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.Caption
import com.wolfy.widgets.Masthead
import com.wolfy.widgets.Rule
import com.wolfy.widgets.SectionLabel
import com.wolfy.widgets.ThemePicker
import com.wolfy.widgets.WordCardPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Корень приложения.
 *
 * Пока это проверочный экран: он показывает, что ядро загрузилось, разбирает
 * настоящее слово и рисует газетную тему. Навигация и настоящие экраны
 * появятся поверх него — но именно этот экран отвечает на вопрос, ради
 * которого собран весь нижний слой: доходит ли разбор слова из Rust до
 * интерфейса на Compose.
 */
@Composable
fun WolfyApplication() {
    var theme by remember { mutableStateOf(ReadingTheme.Paper) }

    WolfyTheme(theme = theme) {
        val state by produceState<CoreState>(CoreState.Loading) {
            // Ядро грузится и разбирает словарь — это работа для фонового
            // потока, в главном она съела бы несколько кадров.
            value = withContext(Dispatchers.Default) {
                try {
                    val core = createWolfyCore()
                    CoreState.Ready(
                        version = core.version(),
                        analysis = core.analyzeWord("reading"),
                    )
                } catch (e: CoreException) {
                    CoreState.Failed(e.message ?: "ядро недоступно")
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WolfyTheme.spacing.pageMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WolfyTheme.spacing.large),
        ) {
            Masthead()
            Rule(thick = true)

            when (val current = state) {
                CoreState.Loading -> SectionLabel("Ядро загружается")

                is CoreState.Failed -> {
                    SectionLabel("Ядро недоступно")
                    Caption(current.message)
                }

                is CoreState.Ready -> {
                    SectionLabel("Ядро ${current.version}")
                    WordCardPreview(current.analysis)
                }
            }

            Spacer(Modifier.height(WolfyTheme.spacing.small))
            Rule()
            SectionLabel("Тема оформления")
            ThemePicker(selected = theme, onSelect = { theme = it })
        }
    }
}

/** Что сейчас с ядром. */
private sealed interface CoreState {
    data object Loading : CoreState
    data class Ready(val version: String, val analysis: WordAnalysis) : CoreState
    data class Failed(val message: String) : CoreState
}
