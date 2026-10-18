package com.kakei.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kakei.app.automation.MidnightWidgetResetWorker
import com.kakei.app.automation.NotificationListenerWatchdogWorker
import com.kakei.app.automation.SyncWorker
import com.kakei.app.data.local.KakeiDatabase
import com.kakei.app.data.repository.KakeiRepository
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

            val widgetResetRequest = PeriodicWorkRequestBuilder<MidnightWidgetResetWorker>(
                12, TimeUnit.HOURS
            ).build()
            wm.enqueueUniquePeriodicWork(
                "kakei_midnight_widget_reset",
                ExistingPeriodicWorkPolicy.KEEP,
                widgetResetRequest
            )
        }
    }
}
