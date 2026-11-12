package com.kakei.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kakei.app.R
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.*
import java.math.RoundingMode
import java.util.Locale

/** Snapshot of everything bank-alert capture needs from the OS. */
private data class CapturePermissionState(
    val postNotifications: Boolean,
    val notificationListener: Boolean,
    val overlay: Boolean
)

private fun readCapturePermissions(context: Context): CapturePermissionState {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return CapturePermissionState(
        postNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS),
        notificationListener = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName),
        overlay = Settings.canDrawOverlays(context)
    )
}

/**
 * Auto-capture onboarding: notification permission, notification-listener
 * and overlay access (system settings screens) and the default account captured alerts are
 * filed into. Status refreshes on resume. Alerts that could not be booked on their own wait
 * in the unprocessed list of the transactions page.
 */
@Composable
fun AutoCaptureCard(
    settings: AppSettingsState,
    accounts: List<AccountDto>,
    repository: KakeiRepository
) {
    val s = LocalResources.current
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(readCapturePermissions(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissions = readCapturePermissions(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions = readCapturePermissions(context) }

    val usableAccounts = accounts.filter { !it.isArchived && it.permissionLevel != "READ_ONLY" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = s.getString(R.string.auto_capture_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(s.getString(R.string.auto_capture_notification_permission), permissions.postNotifications, s.getString(R.string.grant_permission_button)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        PermissionRow(s.getString(R.string.auto_capture_listener_access), permissions.notificationListener, s.getString(R.string.open_settings_button)) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        PermissionRow(s.getString(R.string.auto_capture_overlay_access), permissions.overlay, s.getString(R.string.open_settings_button)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(
            text = s.getString(R.string.capture_default_account_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = settings.captureDefaultAccountId == null,
                onClick = { repository.setDefaultCaptureAccount(null) },
                label = { Text(s.getString(R.string.capture_no_default_account)) }
            )
            usableAccounts.forEach { acc ->
                FilterChip(
                    selected = settings.captureDefaultAccountId == acc.id,
                    onClick = { repository.setDefaultCaptureAccount(acc.id) },
                    label = { Text("${acc.name} (${acc.currency})") }
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, actionLabel: String, onAction: () -> Unit) {
    val s = LocalResources.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = if (granted) s.getString(R.string.permission_granted) else s.getString(R.string.permission_missing),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) KakeiTheme.colors.mint else MaterialTheme.colorScheme.error
            )
        }
        if (!granted) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel, maxLines = 1) }
        }
    }
}

/**
 * Effective exchange rates from the server (market + the user's overrides), with the
 * ability to set a manual rate for a pair and to reset it.
 */
@Composable
fun ExchangeRatesCard(
    settings: AppSettingsState,
    repository: KakeiRepository
) {
    val s = LocalResources.current
    val response by repository.exchangeRates.collectAsState()
    val book by repository.rateBook.collectAsState()
    val base = settings.profile.defaultCurrency.uppercase(Locale.ROOT)

    val currencies = remember(book) {
        (CurrencyFormatter.supportedCurrencies + book.knownCurrencies).map { it.uppercase(Locale.ROOT) }.distinct()
    }
    var fromCurrency by remember { mutableStateOf(currencies.firstOrNull { it != base } ?: "USD") }
    var toCurrency by remember(base) { mutableStateOf(base) }
    var rateInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = s.getString(R.string.exchange_rates_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        val listed = currencies.filter { it != base }
        val anyKnown = listed.any { book.rate(it, base) != null }
        if (!anyKnown) {
            Text(s.getString(R.string.exchange_rates_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listed.forEach { cur ->
            val rate = book.rate(cur, base) ?: return@forEach
            val manual = book.overrides.containsKey(cur to base) || book.overrides.containsKey(base to cur)
            Text(
                text = "1 $cur = ${CurrencyFormatter.formatRate(rate)} $base" +
                    if (manual) " · ${s.getString(R.string.exchange_rate_manual_badge)}" else "",
                color = if (manual) KakeiTheme.colors.mint else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (response.overrides.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            response.overrides.forEach { ov ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1 ${ov.fromCurrency} = ${CurrencyFormatter.formatRate(ov.rate)} ${ov.toCurrency} · ${s.getString(R.string.exchange_rate_manual_badge)}",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { repository.resetExchangeRateOverride(ov.fromCurrency, ov.toCurrency) }) {
                        Text(s.getString(R.string.exchange_rate_reset_button))
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(s.getString(R.string.exchange_rate_from_label), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            currencies.forEach { cur ->
                FilterChip(selected = fromCurrency == cur, onClick = { fromCurrency = cur }, label = { Text(cur) })
            }
        }
        Text(s.getString(R.string.exchange_rate_to_label), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            currencies.forEach { cur ->
                FilterChip(selected = toCurrency == cur, onClick = { toCurrency = cur }, label = { Text(cur) })
            }
        }
        val parsedRate = rateInput.trim().replace(",", ".").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        OutlinedTextField(
            value = rateInput,
            onValueChange = { rateInput = it },
            label = { Text("${s.getString(R.string.transfer_rate_label)}: 1 $fromCurrency = ? $toCurrency") },
            placeholder = {
                book.rate(fromCurrency, toCurrency)?.let {
                    Text(it.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (parsedRate != null) {
                    repository.setExchangeRateOverride(fromCurrency, toCurrency, parsedRate)
                    rateInput = ""
                }
            },
            enabled = parsedRate != null && fromCurrency != toCurrency && settings.isAuthenticated,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(s.getString(R.string.exchange_rate_set_button))
        }
    }
}
