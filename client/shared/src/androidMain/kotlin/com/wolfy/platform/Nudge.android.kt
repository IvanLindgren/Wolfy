package com.wolfy.platform

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

private var appContext: Context? = null

/** Вызывается один раз при старте приложения, рядом с остальной подготовкой. */
fun initializeNudge(context: Context) {
    appContext = context.applicationContext
}

/**
 * Будит виджет широковещательным намерением.
 *
 * Класс виджета живёт в модуле приложения, а этот код — в общем, и ссылаться
 * на него отсюда нельзя: зависимость идёт в другую сторону. Поэтому получатель
 * назван строкой — тем же способом, каким его назвал бы лаунчер.
 *
 * Ошибки проглатываются намеренно: обновление украшения на рабочем столе не
 * повод ронять чтение.
 */
actual fun refreshBookNudge() {
    val context = appContext ?: return
    runCatching {
        val component = ComponentName(context.packageName, WIDGET_CLASS)
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return

        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                this.component = component
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            },
        )
    }
}

private const val WIDGET_CLASS = "com.wolfy.android.BookWidget"
