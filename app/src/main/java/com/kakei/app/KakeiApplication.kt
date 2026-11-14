package com.kakei.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kakei.app.automation.ExpenseReminderScheduler
import com.kakei.app.automation.MidnightWidgetResetWorker
import com.kakei.app.automation.NotificationListenerWatchdogWorker
import com.kakei.app.automation.SyncWorker
import com.kakei.app.data.local.KakeiDatabase
import com.kakei.app.data.repository.KakeiRepository
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class KakeiApplication : Application() {

    lateinit var database: KakeiDatabase
        private set

    lateinit var repository: KakeiRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = KakeiDatabase(this)
        repository = KakeiRepository(this, database)
        scheduleBackgroundWorkers()
    }

    private fun scheduleBackgroundWorkers() {
        runCatching {
            val wm = WorkManager.getInstance(this)

            val watchdogRequest = PeriodicWorkRequestBuilder<NotificationListenerWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()
            wm.enqueueUniquePeriodicWork(
                "kakei_notification_watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                watchdogRequest
            )

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                30, TimeUnit.MINUTES
            ).build()
            wm.enqueueUniquePeriodicWork(
                "kakei_background_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            // Daily, first run just after the next local midnight, so "today" figures on the
            // widgets roll over with the calendar day. UPDATE re-anchors installs that still
            // carry the old 12-hour schedule counted from first launch.
            val now = ZonedDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1)
            val widgetResetRequest = PeriodicWorkRequestBuilder<MidnightWidgetResetWorker>(
                24, TimeUnit.HOURS
            ).setInitialDelay(Duration.between(now, nextMidnight).toMillis(), TimeUnit.MILLISECONDS).build()
            wm.enqueueUniquePeriodicWork(
                "kakei_midnight_widget_reset",
                ExistingPeriodicWorkPolicy.UPDATE,
                widgetResetRequest
            )
        }

        // Restores the daily reminder if its queued run was lost (e.g. app data restored).
        val settings = repository.settingsState.value
        ExpenseReminderScheduler.apply(
            this,
            settings.expenseReminderEnabled,
            settings.expenseReminderMinuteOfDay,
            ExistingWorkPolicy.KEEP
        )
    }
}
