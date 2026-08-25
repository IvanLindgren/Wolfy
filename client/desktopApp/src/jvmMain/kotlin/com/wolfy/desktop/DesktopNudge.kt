package com.wolfy.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.wolfy.data.BookNudge
import com.wolfy.data.bookNudge
import com.wolfy.data.library.createLibraryStore
import com.wolfy.data.library.lastReadBook
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.util.Calendar
import java.util.TimeZone

/**
 * Панель «вас ждёт книга» на рабочем столе.
 *
 * Настольный ответ на виджет Android. Маленькое окно без рамки, поверх
 * остальных: книга, место в ней и одна фраза, которая ничего не требует.
 * Нажатие открывает Wolfy.
 *
 * Почему не уведомление. Уведомление появляется само и требует реакции сейчас;
 * панель просто лежит на столе, как лежит на нём бумажная закладка, и её видно
 * только тогда, когда на стол смотрят. Разница между «оклик» и «напоминание»
 * ровно в этом, и для чтения нужно второе.
 *
 * Панель выключена по умолчанию и включается из трея: окно поверх всех — вещь,
 * которую нельзя навязывать.
 */
@Composable
fun DesktopNudgePanel(onOpen: () -> Unit, onClose: () -> Unit) {
    var nudge by remember { mutableStateOf<BookNudge?>(null) }

    // Панель перечитывает состояние сама, раз в несколько минут: чтение идёт в
    // главном окне, а панель в это время закрыта или не видна, и обновляться
    // чаще ей незачем.
    LaunchedEffect(Unit) {
        while (true) {
            nudge = readNudge()
            delay(5 * 60_000L)
        }
    }

    val size = DpSize(320.dp, 132.dp)
    val state = rememberWindowState(size = size, position = deskCorner(size))

    Window(
        onCloseRequest = onClose,
        state = state,
        title = "Wolfy",
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
    ) {
        val shown = nudge
        Column(
            Modifier
                .fillMaxSize()
                .background(PAPER, RoundedCornerShape(12.dp))
                .border(1.dp, RULE, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = shown?.title ?: "Пока нечего читать",
                color = INK,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth().height(1.dp).background(RULE)) {}
            Text(
                text = shown?.place ?: "Добавьте книгу — и она появится здесь",
                color = INK_MUTED,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (shown != null) {
                Text(
                    text = shown.teaser,
                    color = INK_MUTED,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "открыть Wolfy",
                color = ACCENT,
                fontSize = 12.sp,
                modifier = Modifier.clickableText(onOpen),
            )
        }
    }
}

/**
 * Читает приглашение из того же хранилища, что и приложение.
 *
 * Ошибка чтения — это «звать некуда», а не сбой: панель украшает рабочий
 * стол, и падать ей не из-за чего.
 */
fun readNudge(): BookNudge? = runCatching {
    bookNudge(createLibraryStore().lastReadBook(), day = localDay())
}.getOrNull()

/**
 * Правый нижний угол рабочего стола.
 *
 * Там, где на столе обычно и живут такие вещи, и туда же реже всего попадают
 * открытые окна. Считается по рабочей области, а не по экрану: панель задач
 * занимает нижнюю полосу, и окно, поставленное по краю экрана, уехало бы под
 * неё.
 *
 * Точка Compose не равна пикселю: при масштабе интерфейса 150% те же 320
 * точек занимают 480 пикселей, и без перевода панель встала бы левее, чем
 * нужно. Если о среде спросить не удалось — отдаём место системе.
 */
private fun deskCorner(size: DpSize): WindowPosition {
    val environment = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
    }.getOrNull() ?: return WindowPosition.PlatformDefault
    val bounds = runCatching { environment.maximumWindowBounds }.getOrNull()
        ?: return WindowPosition.PlatformDefault
    val scale = runCatching {
        environment.defaultScreenDevice.defaultConfiguration.defaultTransform.scaleY
    }.getOrNull()?.takeIf { it > 0.0 } ?: 1.0

    val margin = 16.dp
    val x = (bounds.width / scale).dp - size.width - margin
    val y = (bounds.height / scale).dp - size.height - margin
    return WindowPosition(x.coerceAtLeast(0.dp), y.coerceAtLeast(0.dp))
}

private fun localDay(): Long {
    val calendar = Calendar.getInstance(TimeZone.getDefault())
    val offset = (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)).toLong()
    return (System.currentTimeMillis() + offset) / 86_400_000L
}

/*
 * Краски панели заданы числами, а не темой приложения.
 *
 * Тема живёт внутри `WolfyTheme`, а панель рисуется отдельным окном до всякой
 * композиции приложения. Тянуть тему сюда значило бы поднимать половину
 * приложения ради четырёх цветов; это те же четыре цвета газетной темы.
 */
private val PAPER = Color(0xFFFBF9F5)
private val INK = Color(0xFF111111)
private val INK_MUTED = Color(0xFF6B6B66)
private val RULE = Color(0xFFD9D8D2)
private val ACCENT = Color(0xFF8C3A2B)

/**
 * Нажатие без подсветки.
 *
 * Волна Material на бумажной панели выглядит чужой, а другого действия у
 * строки нет — нажали, открылось окно.
 */
private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.clickable(
        indication = null,
        interactionSource = MutableInteractionSource(),
        onClick = onClick,
    )
