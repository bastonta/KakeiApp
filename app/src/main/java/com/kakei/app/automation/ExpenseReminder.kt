package com.kakei.app.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kakei.app.KakeiApplication
import com.kakei.app.MainActivity
import com.kakei.app.R
import com.kakei.app.core.i18n.withAppLanguage
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Daily "record your expenses" reminder. Each run posts the notification and enqueues the next
 * one-time run for the configured local time, so the schedule follows time zone and DST changes.
 * WorkManager persists the pending run across reboots; Doze may delay it by a few minutes.
 */
object ExpenseReminderScheduler {
    private const val WORK_NAME = "kakei_expense_reminder"
    const val CHANNEL_ID = "kakei_expense_reminder"
    private const val NOTIFICATION_ID = 2001

    /**
     * Schedules (or cancels) the reminder from the current settings. The default REPLACE re-plans
     * an already queued run, needed after the time changes; app start uses KEEP to leave a queued
     * run intact, and the worker itself uses APPEND_OR_REPLACE so it does not cancel its own run.
     */
    fun apply(
        context: Context,
        enabled: Boolean,
        minuteOfDay: Int,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
    ) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val delay = delayUntilNext(ZonedDateTime.now(), minuteOfDay)
            val request = OneTimeWorkRequestBuilder<ExpenseReminderWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }

    /** Time from [now] to the next occurrence of [minuteOfDay] (local time), always > 0. */
    fun delayUntilNext(now: ZonedDateTime, minuteOfDay: Int): Duration {
        val time = LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60)
        var next = now.with(time).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    fun showNotification(context: Context) {
        // POST_NOTIFICATIONS is a runtime permission only from Android 13.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.expense_reminder_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_QUICK_ADD, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(context.getString(R.string.expense_reminder_notification_title))
            .setContentText(context.getString(R.string.expense_reminder_notification_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }
}

/** Posts the expense reminder and plans the next day's run (`ExpenseReminderWorker`). */
class ExpenseReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? KakeiApplication ?: return Result.success()
        val settings = app.repository.settingsState.value
        if (!settings.expenseReminderEnabled) return Result.success()
        // Resolve strings in the language picked in the app; the worker runs without an activity.
        val localized = applicationContext.withAppLanguage(settings.language)
        ExpenseReminderScheduler.showNotification(localized)
        ExpenseReminderScheduler.apply(
            applicationContext,
            enabled = true,
            minuteOfDay = settings.expenseReminderMinuteOfDay,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE
        )
        return Result.success()
    }
}
