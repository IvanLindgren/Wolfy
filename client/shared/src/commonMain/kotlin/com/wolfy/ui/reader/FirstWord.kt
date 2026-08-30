package com.wolfy.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import com.wolfy.theme.WolfyTheme
import com.wolfy.widgets.pressable

/**
 * Первое касание слова.
 *
 * ## Что здесь чинится
 *
 * Wolfy существует ради одного действия: коснуться незнакомого слова и
 * получить разбор. Об этом действии на экране чтения не было сказано ничего.
 * Страница книги выглядит как страница книги — она ничего не обещает и ни на
 * что не намекает, и читатель, открывший первую главу, получал ровно то же,
 * что в любой бесплатной читалке. Главное отличие продукта не было спрятано
 * глубоко: его просто не существовало для того, кто не догадался ткнуть в
 * текст пальцем.
 *
 * ## Почему подсказка, а не обучающий тур
 *
 * Тур приходит до того, как у читателя появился вопрос, и потому не
 * запоминается: человек нажимает «дальше», пока не кончится. Здесь подсказка
 * стоит там, где действие и совершается, и живёт ровно до первого касания.
 *
 * ## Почему с настоящим словом со страницы
 *
 * «Коснитесь любого слова» — инструкция, её нужно понять и применить.
 * «Коснитесь слова, например innumerable» — указание на конкретное место
 * страницы: глаз находит это слово в тексте сам, и палец идёт за ним. Разница
 * между «объяснили» и «показали» здесь стоит всего одного перебора токенов.
 */
@Composable
internal fun FirstWordHint(example: String?, onDismiss: () -> Unit) {
    val colors = WolfyTheme.colors
    val spacing = WolfyTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageMargin, vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.hair),
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Коснитесь незнакомого слова")
                    if (example != null) {
                        append(" — например, ")
                        // Пример набран курсивом и чернилами книги: это слово
                        // со страницы, а не слово из интерфейса, и выглядеть
                        // оно должно как цитата, а не как кнопка.
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.ink)) {
                            append(example)
                        }
                    }
                },
                style = WolfyTheme.typography.body,
                color = colors.ink,
            )
            Text(
                text = "Wolfy разберёт его и предложит забрать в карточки",
                style = WolfyTheme.typography.caption,
                color = colors.inkMuted,
            )
        }
        Text(
            text = "понятно",
            style = WolfyTheme.typography.button,
            color = colors.accent,
            modifier = Modifier.pressable(onClick = onDismiss),
        )
    }
}

/**
 * Слово со страницы, которое не стыдно назвать в примере.
 *
 * Три правила, и каждое отсекает свою неловкость.
 *
 * **Только строчные.** Слово с прописной — это либо имя собственное, разбор
 * которого бесполезен («Dorian» переводить некуда), либо начало предложения,
 * которое по виду от имени не отличить. Строчное слово посреди фразы не
 * бывает ни тем, ни другим.
 *
 * **Только буквы.** Дефисы и апострофы дают «honey-coloured» и «don't»: и то и
 * другое разбирается, но в качестве первого примера выглядит краевым случаем,
 * а не приглашением.
 *
 * **Длина от шести до двенадцати.** Короткие слова читатель и так знает, и
 * предлагать разобрать «was» — значит пообещать бесполезность. Слишком длинные
 * редки, и искать их на странице глазами дольше, чем прочесть подсказку.
 *
 * Возвращает `null`, если подходящего слова не нашлось: подсказка тогда
 * обходится без примера, а не выдумывает его.
 */
internal fun invitingWord(blocks: List<ReaderBlock>): String? {
    for (block in blocks) {
        if (block.kind != "paragraph") continue
        val tokens = block.parsed?.tokens ?: continue
        for (token in tokens) {
            if (!token.tappable) continue
            val text = token.text
            if (text.length !in MIN_LETTERS..MAX_LETTERS) continue
            if (!text.all { it.isLetter() && it.isLowerCase() }) continue
            return text
        }
    }
    return null
}

private const val MIN_LETTERS = 6
private const val MAX_LETTERS = 12
