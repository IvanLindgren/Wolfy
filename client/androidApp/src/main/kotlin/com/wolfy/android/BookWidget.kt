package com.wolfy.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wolfy.data.bookNudge
import com.wolfy.data.library.createLibraryStore
import com.wolfy.data.library.lastReadBook
import java.util.Calendar
import java.util.TimeZone

/**
 * Виджет «вас ждёт книга».
 *
 * Показывает книгу, открытую последней, место в ней и одну фразу, которая
 * ничего не требует. Нажатие открывает приложение — других действий у виджета
 * нет и быть не должно: он висит среди чужих значков, и всё, чего от него
 * хотят, — вернуть в книгу одним касанием.
 *
 * Почему `RemoteViews`, а не Glance. Виджет — это три строки и одно нажатие.
 * Glance принёс бы за собой Compose-runtime в процесс лаунчера ради разметки,
 * которую `RemoteViews` описывает пятнадцатью строками XML.
 *
 * Состояние читается прямо из записи на диске, минуя ядро. Так и надо:
 * система будит виджет в своём процессе, где ни ядра, ни открытой сессии нет,
 * а поднимать ядро ради строки текста значит тратить полсекунды и десяток
 * мегабайт на каждое обновление. Ничего при этом не пишется — только чтение.
 */
class BookWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        val views = render(context)
        widgetIds.forEach { id -> manager.updateAppWidget(id, views) }
    }

    private fun render(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_book)

        // Ошибка чтения — это «звать некуда», а не падение: виджет, который
        // роняет лаунчер, снимут с рабочего стола вместе с приложением.
        // Хранилище берётся штатной фабрикой: виджет система будит в процессе
        // самого приложения, а значит `WolfyApp.onCreate` уже отработал и
        // каталог известен. Собирать второй путь к тем же файлам значило бы
        // однажды разойтись с первым.
        val book = runCatching { createLibraryStore().lastReadBook() }.getOrNull()

        val nudge = bookNudge(book, day = localDay())
        if (nudge == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_place, "")
            views.setTextViewText(R.id.widget_teaser, context.getString(R.string.widget_empty_hint))
        } else {
            views.setTextViewText(R.id.widget_title, nudge.title)
            views.setTextViewText(R.id.widget_place, nudge.place)
            views.setTextViewText(R.id.widget_teaser, nudge.teaser)
        }

        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        return views
    }

    /**
     * Намерение «открыть приложение».
     *
     * `FLAG_IMMUTABLE` обязателен с Android 12 и правилен по существу: менять
     * это намерение снаружи незачем, а изменяемое — это дыра, через которую
     * чужое приложение отправит запрос от нашего имени.
     */
    private fun openApp(context: Context): PendingIntent {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Номер местного дня: по нему выбирается фраза, и в течение дня она одна. */
    private fun localDay(): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        val offset = (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)).toLong()
        return (System.currentTimeMillis() + offset) / 86_400_000L
    }

    companion object {
        /**
         * Просит систему перерисовать все виджеты.
         *
         * Зовётся приложением, когда место в книге изменилось: система сама
         * обновляет виджет не чаще получаса, и без этого толчка он показывал
         * бы вчерашнюю страницу сразу после чтения.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, BookWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return

            val intent = Intent(context, BookWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
