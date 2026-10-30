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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.local.PendingCaptureEntity
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.*
import java.math.RoundingMode
import java.util.Locale

/** Snapshot of everything bank-alert capture needs from the OS. */
private data class CapturePermissionState(
    val receiveSms: Boolean,
    val postNotifications: Boolean,
    val notificationListener: Boolean,
    val overlay: Boolean
)

private fun readCapturePermissions(context: Context): CapturePermissionState {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return CapturePermissionState(
        receiveSms = granted(Manifest.permission.RECEIVE_SMS),
        postNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS),
        notificationListener = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName),
        overlay = Settings.canDrawOverlays(context)
    )
}

/**
 * Auto-capture onboarding: runtime SMS / notification permissions, notification-listener
 * and overlay access (system settings screens), the default account captured alerts are
 * filed into, and the alerts still waiting for an account. Status refreshes on resume.
 */
@Composable
fun AutoCaptureCard(
    settings: AppSettingsState,
    accounts: List<AccountDto>,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(readCapturePermissions(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissions = readCapturePermissions(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions = readCapturePermissions(context) }

    val usableAccounts = accounts.filter { !it.isArchived && it.permissionLevel != "READ_ONLY" }
    val pendingCaptures by repository.pendingCaptures.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = s.autoCaptureTitle,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        PermissionRow(s.autoCaptureSmsPermission, permissions.receiveSms, s.grantPermissionButton) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(s.autoCaptureNotificationPermission, permissions.postNotifications, s.grantPermissionButton) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        PermissionRow(s.autoCaptureListenerAccess, permissions.notificationListener, s.openSettingsButton) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        PermissionRow(s.autoCaptureOverlayAccess, permissions.overlay, s.openSettingsButton) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(
            text = s.captureDefaultAccountLabel,
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
                label = { Text(s.captureNoDefaultAccount) }
            )
            usableAccounts.forEach { acc ->
                FilterChip(
                    selected = settings.captureDefaultAccountId == acc.id,
                    onClick = { repository.setDefaultCaptureAccount(acc.id) },
                    label = { Text("${acc.name} (${acc.currency})") }
                )
            }
        }

        if (pendingCaptures.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Text(
                text = "${s.pendingCapturesTitle} (${pendingCaptures.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            pendingCaptures.forEach { capture ->
                PendingCaptureRow(capture, usableAccounts, repository)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, actionLabel: String, onAction: () -> Unit) {
    val s = LocalAppStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = if (granted) s.permissionGranted else s.permissionMissing,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) KakeiTheme.colors.mint else MaterialTheme.colorScheme.error
            )
        }
        if (!granted) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel, maxLines = 1) }
        }
    }
}

@Composable
private fun PendingCaptureRow(
    capture: PendingCaptureEntity,
    accounts: List<AccountDto>,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    var rememberSender by remember(capture.id) { mutableStateOf(!capture.sender.isNullOrBlank()) }
    val matching = accounts.filter { it.currency.equals(capture.currency, ignoreCase = true) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${CurrencyFormatter.format(capture.amount, capture.currency)} · ${capture.merchant ?: capture.operationType}",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = listOfNotNull(
                capture.sender,
                CurrencyFormatter.formatShortDate(capture.bookingDate),
                s.pendingCaptureCurrencyMismatch.takeIf { capture.reason == "CURRENCY_MISMATCH" }
            ).joinToString(" · ") + "\n" + capture.rawText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            matching.forEach { acc ->
                AssistChip(
                    onClick = { repository.filePendingCapture(capture, acc.id, rememberSender) },
                    label = { Text(acc.name) }
                )
            }
            TextButton(onClick = { repository.dismissPendingCapture(capture.id) }) { Text(s.dismissButton) }
        }
        if (!capture.sender.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberSender, onCheckedChange = { rememberSender = it })
                Text(s.pendingCaptureRemember, style = MaterialTheme.typography.bodySmall)
            }
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
    val s = LocalAppStrings.current
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
            text = s.exchangeRatesTitle,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        val listed = currencies.filter { it != base }
        val anyKnown = listed.any { book.rate(it, base) != null }
        if (!anyKnown) {
            Text(s.exchangeRatesEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listed.forEach { cur ->
            val rate = book.rate(cur, base) ?: return@forEach
            val manual = book.overrides.containsKey(cur to base) || book.overrides.containsKey(base to cur)
            Text(
                text = "1 $cur = ${CurrencyFormatter.formatRate(rate)} $base" +
                    if (manual) " · ${s.exchangeRateManualBadge}" else "",
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
                        text = "1 ${ov.fromCurrency} = ${CurrencyFormatter.formatRate(ov.rate)} ${ov.toCurrency} · ${s.exchangeRateManualBadge}",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { repository.resetExchangeRateOverride(ov.fromCurrency, ov.toCurrency) }) {
                        Text(s.exchangeRateResetButton)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(s.exchangeRateFromLabel, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            currencies.forEach { cur ->
                FilterChip(selected = fromCurrency == cur, onClick = { fromCurrency = cur }, label = { Text(cur) })
            }
        }
        Text(s.exchangeRateToLabel, style = MaterialTheme.typography.labelLarge)
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
            label = { Text("${s.transferRateLabel}: 1 $fromCurrency = ? $toCurrency") },
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
            Text(s.exchangeRateSetButton)
        }
    }
}
