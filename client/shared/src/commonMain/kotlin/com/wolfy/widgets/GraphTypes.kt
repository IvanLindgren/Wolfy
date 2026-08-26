package com.wolfy.widgets

import androidx.compose.runtime.Immutable

/** Данные синтаксического разбора, которые читает карточка фразы.
 * Визуальный граф больше не рисуется: на маленьком экране он неразборчив. */
@Immutable
data class GraphWord(val text: String, val tag: String? = null)

@Immutable
data class GraphLink(val from: Int, val to: Int, val label: String)
