package com.kakei.app.automation

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kakei.app.KakeiApplication
import com.kakei.app.MainActivity
import com.kakei.app.R

/**
 * BroadcastReceiver intercepting incoming transactional SMS messages (`SmsReceiver`).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? KakeiApplication ?: return
        if (!app.repository.settingsState.value.smsCaptureEnabled) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val combinedBody = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        if (combinedBody.isNotBlank()) {
            app.repository.ingestRawBankAlert(combinedBody, sourceChannel = "SMS")
        }
    }
}

/**
 * NotificationListenerService intercepting payment and banking push notifications
 * while strictly ignoring messaging apps (`BankNotificationListenerService`).
 */
class BankNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val pkg = notification.packageName ?: return
        if (pkg == packageName || BankSmsPatterns.ignoredPackages.contains(pkg)) return

        val app = applicationContext as? KakeiApplication ?: return
        if (!app.repository.settingsState.value.pushCaptureEnabled) return

        val extras = notification.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val combined = "$title $text".trim()
        if (combined.isNotBlank()) {
            app.repository.ingestRawBankAlert(combined, sourceChannel = "PUSH")
        }
    }
}

/**
 * Watchdog worker ensuring NotificationListenerService stays bound under OEM battery optimization.
 */
class NotificationListenerWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(applicationContext, BankNotificationListenerService::class.java)
            )
        }
        return Result.success()
    }
}

/**
 * Midnight worker resetting daily widget expenditure counters (`MidnightWidgetResetWorker`).
 */
class MidnightWidgetResetWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        KakeiWidgetUpdater.refreshAllWidgets(applicationContext)
        return Result.success()
    }
}

/**
 * Background synchronization worker (`SyncWorker`) executing Kakei Outbox mutations and server delta pulls.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? KakeiApplication ?: return Result.success()
        app.repository.synchronizeWithServer()
        return Result.success()
    }
}

/**
 * Instant category confirmation overlay service (`InstantPopupService`).
 */
class InstantPopupService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/**
 * Quick Settings Tile (`QuickAddTileService`) for one-tap transaction logging from anywhere on Android.
 */
class QuickAddTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Kakei Quick Add"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated", "NewApi")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_QUICK_ADD, true)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Base provider for all 7 Kakei home screen widgets.
 */
abstract class BaseKakeiWidgetProvider(
    private val widgetTitle: String,
    private val widgetAction: String
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId, widgetTitle, widgetAction)
        }
    }

    companion object {
        fun updateSingleWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            title: String,
            action: String
        ) {
            val app = context.applicationContext as? KakeiApplication
            val summary = app?.repository?.getWidgetMetricSummary(action) ?: "Tap to open Kakei"

            val views = RemoteViews(context.packageName, R.layout.widget_kakei_card).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_value, summary)

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_WIDGET_ACTION, action)
                }
                val pending = PendingIntent.getActivity(
                    context,
                    action.hashCode(),
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
            manager.updateAppWidget(widgetId, views)
        }
    }
}

class BudgetRingWidget : BaseKakeiWidgetProvider("Kakei · Budget Ring", "BUDGET_RING")
class GoalsRingWidget : BaseKakeiWidgetProvider("Kakei · Goals Ring", "GOALS_RING")
class TodaySpendWidget : BaseKakeiWidgetProvider("Kakei · Today's Spend", "TODAY_SPEND")
class DebtBalanceWidget : BaseKakeiWidgetProvider("Kakei · Lend & Borrow", "DEBT_BALANCE")
class QuickAddWidget : BaseKakeiWidgetProvider("Kakei · Quick Add (+)", "QUICK_ADD")
class SplitBillWidget : BaseKakeiWidgetProvider("Kakei · Split Bill", "SPLIT_BILL")
class NetWorthWidget : BaseKakeiWidgetProvider("Kakei · Net Worth", "NET_WORTH")

object KakeiWidgetUpdater {
    fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val providers = listOf(
            BudgetRingWidget::class.java to ("Kakei · Budget Ring" to "BUDGET_RING"),
            GoalsRingWidget::class.java to ("Kakei · Goals Ring" to "GOALS_RING"),
            TodaySpendWidget::class.java to ("Kakei · Today's Spend" to "TODAY_SPEND"),
            DebtBalanceWidget::class.java to ("Kakei · Lend & Borrow" to "DEBT_BALANCE"),
            QuickAddWidget::class.java to ("Kakei · Quick Add (+)" to "QUICK_ADD"),
            SplitBillWidget::class.java to ("Kakei · Split Bill" to "SPLIT_BILL"),
            NetWorthWidget::class.java to ("Kakei · Net Worth" to "NET_WORTH")
        )
        for ((cls, meta) in providers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            for (id in ids) {
                BaseKakeiWidgetProvider.updateSingleWidget(context, manager, id, meta.first, meta.second)
            }
        }
    }
}
