package com.wolfy.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wolfy.shared.R

/**
 * Напоминания о повторении на Android.
 *
 * Будильник неточный — [AlarmManager.setAndAllowWhileIdle], а не
 * `setExactAndAllowWhileIdle`. Точный требует отдельного разрешения, которое
 * система с Android 12 выдаёт только будильникам и таймерам, и просить его
 * ради «пора повторить слова» — значит и не получить, и выглядеть навязчиво.
 * Разброс в несколько минут здесь ничего не стоит: карточка, созревшая в 9:00,
 * ничем не отличается от карточки, созревшей в 9:07.
 *
 * Момент и число карточек записываются в настройки: после перезагрузки все
 * будильники система стирает, и восстановить их можно только по тому, что
 * приложение запомнило само.
 */
private var appContext: Context? = null

/** Вызывается один раз при старте приложения — как и хранилище. */
fun initializeReminders(context: Context) {
    val application = context.applicationContext
    appContext = application

    // Канал заводится сразу: без него уведомление на Android 8 и старше не
    // покажется вовсе, а заводить его в момент показа — значит зависеть от
    // того, дожил ли процесс до срабатывания будильника.
    val channel = NotificationChannel(
        CHANNEL,
        "Повторения",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "Напоминание, когда накопились карточки к повторению"
    }
    application.getSystemService(NotificationManager::class.java)
        ?.createNotificationChannel(channel)
}

actual fun scheduleReviewReminder(at: Long, count: Int) {
    val context = appContext ?: return
    if (count <= 0) {
        cancelReviewReminder()
        return
    }

    context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
        .putLong(KEY_AT, at)
        .putInt(KEY_COUNT, count)
        .apply()

    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, count))
}

actual fun cancelReviewReminder() {
    val context = appContext ?: return
    context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
    context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, 0))
}

/**
 * Просит разрешение показывать уведомления.
 *
 * Спрашивается не при запуске, а перед первой тренировкой: до неё напоминать
 * не о чем, и вопрос «можно уведомлять?» на пустом месте почти всегда
 * получает «нет» — навсегда.
 *
 * До Android 13 разрешения нет, и функция ничего не делает: там уведомления
 * разрешены по умолчанию.
 */
@Composable
actual fun rememberReminderPermission(): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Отказ — обычный ответ. Тренировка работает и без уведомлений. */ }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { {} }

    return remember(context) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Приёмник будильника и перезагрузки.
 *
 * Оба события ведут в одно место, потому что делают одно и то же: срок
 * наступил — показать, система перезагрузилась — поставить будильник заново.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val at = store.getLong(KEY_AT, 0)
            val count = store.getInt(KEY_COUNT, 0)
            if (at <= 0 || count <= 0) return
            appContext = context.applicationContext
            // Срок, прошедший за время выключенного телефона, не отменяется —
            // он просто наступил. Ставим на ближайшую минуту.
            val moment = maxOf(at, System.currentTimeMillis() + 60_000)
            context.getSystemService(AlarmManager::class.java)
                ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, moment, alarmIntent(context, count))
            return
        }

        val count = intent.getIntExtra(EXTRA_COUNT, store.getInt(KEY_COUNT, 0))
        if (count <= 0) return
        show(context, count)
    }
}

private fun show(context: Context, count: Int) {
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val tap = open?.let {
        PendingIntent.getActivity(
            context,
            0,
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_review)
        .setContentTitle("Пора повторить")
        // Число — единственное, ради чего уведомление вообще стоит показывать:
        // «пора повторить» без него не сообщает читателю ничего нового.
        .setContentText("${cards(count)} ждут — это пять минут")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .apply { tap?.let(::setContentIntent) }
        .build()

    // Разрешение могли отозвать между постановкой будильника и его
    // срабатыванием; без проверки это исключение, а не тихий отказ.
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION, notification) }
}

private fun alarmIntent(context: Context, count: Int): PendingIntent {
    val intent = Intent(context, ReminderReceiver::class.java)
        .setAction(ACTION)
        .putExtra(EXTRA_COUNT, count)
    return PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun cards(count: Int): String {
    val tens = count % 100
    val word = if (tens in 11..14) {
        "карточек"
    } else {
        when (count % 10) {
            1 -> "карточка"
            2, 3, 4 -> "карточки"
            else -> "карточек"
        }
    }
    return "$count $word"
}

private const val CHANNEL = "reviews"
private const val ACTION = "com.wolfy.REVIEW_REMINDER"
private const val EXTRA_COUNT = "count"
private const val NOTIFICATION = 1
private const val STORE = "reminders"
private const val KEY_AT = "at"
private const val KEY_COUNT = "count"
