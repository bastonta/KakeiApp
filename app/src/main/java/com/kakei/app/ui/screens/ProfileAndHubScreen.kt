package com.kakei.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.kakei.app.BuildConfig
import com.kakei.app.R
import com.kakei.app.automation.SmartMerchantMatcher
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.permissionLevel
import com.kakei.app.core.security.HealthCheckHelper
import com.kakei.app.core.security.SystemHealthStatus
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.local.SyncMutationEntity
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.ThemeMode
import com.kakei.app.ui.theme.kakeiCard
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profile page (opened from the Home avatar): Synchronization, SMS/Push Automation & Biometrics,
 * Appearance (language, theme, base currency), Categories & Smart Merchant Rule Simulator,
 * Family Group Sharing, Exchange Rates, CSV/OFX Import & Export, Encrypted Backups & System Diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileAndHubScreen(
    settings: AppSettingsState,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    rules: List<CounterpartyRuleDto>,
    groupMembers: List<GroupMemberDto>,
    pendingMutations: List<SyncMutationEntity>,
    repository: KakeiRepository,
    onBack: () -> Unit
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCategoriesDialog by remember { mutableStateOf(false) }
    var showSharingDialog by remember { mutableStateOf(false) }
    var showExchangeDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRatesSheet by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showCurrencyDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showLockTimeoutDialog by remember { mutableStateOf(false) }
    var showReminderTimeDialog by remember { mutableStateOf(false) }
    var showAddPasskeyDialog by remember { mutableStateOf(false) }
    var passkeyToDelete by remember { mutableStateOf<UserPasskeyItem?>(null) }
    var sampleSmsIdx by remember { mutableIntStateOf(0) }

    val sampleBankAlerts = listOf(
        "Kaspi Gold: Покупка 4 850 ₸ в Magnum Cash & Carry. Доступно: 607 550 ₸",
        "Halyk Bank: Оплата 1 950 KZT в Starbucks Coffee. Карта *4421",
        "Freedom Bank: Пополнение 250 USD зарплатный перевод Tech Corp",
        "Kaspi Gold: Оплата 3 200 ₸ в Yandex Go. Доступно: 604 350 ₸"
    )

    // The probe hits Settings / system services: run it off the main thread, and again
    // each time the screen resumes (the user may have just granted a permission).
    val sqlCipherActive = repository.database.isSqlCipherActive
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val health by produceState<SystemHealthStatus?>(null, sqlCipherActive, isResumed) {
        if (isResumed) {
            value = withContext(Dispatchers.IO) { HealthCheckHelper.inspect(context, sqlCipherActive) }
        }
    }
    val syncErrors by repository.syncErrors.collectAsState()
    val passkeys by repository.passkeys.collectAsState()
    val rateBook by repository.rateBook.collectAsState()
    val baseCurrency = settings.profile.defaultCurrency
    val themeTitle = when (settings.themeMode) {
        ThemeMode.DARK -> s.getString(R.string.theme_dark)
        ThemeMode.LIGHT -> s.getString(R.string.theme_light)
        ThemeMode.SYSTEM -> s.getString(R.string.theme_system)
    }
    val lockTimeoutLabel: (Int) -> String = { seconds ->
        when {
            seconds == 0 -> s.getString(R.string.immediately)
            seconds < 60 -> s.getString(R.string.lock_after_seconds, seconds)
            else -> s.getString(R.string.lock_after_minutes, seconds / 60)
        }
    }
    val reminderTimeLabel = "%02d:%02d".format(
        settings.expenseReminderMinuteOfDay / 60,
        settings.expenseReminderMinuteOfDay % 60
    )
    // The reminder is only switched on once notifications may be posted.
    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) repository.setExpenseReminder(enabled = true)
    }
    val enableExpenseReminder: () -> Unit = {
        // POST_NOTIFICATIONS is a runtime permission only from Android 13.
        val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            repository.setExpenseReminder(enabled = true)
        } else {
            reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val usdRate = if (baseCurrency.equals("USD", ignoreCase = true)) null else rateBook.rate("USD", baseCurrency)

    Column(modifier = Modifier.fillMaxSize().background(c.bg)) {
        val listState = rememberLazyListState()
        PageTopBar(
            title = s.getString(R.string.nav_profile),
            backContentDescription = s.getString(R.string.back),
            onBack = onBack,
            scrolled = listState.canScrollBackward
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Profile & synchronization (Kakei API + offline Outbox queue)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kakeiCard(cornerRadius = 24.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = settings.profile.name, color = c.mint, contentColor = c.mintInk, size = 68.dp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                settings.profile.name,
                                style = MaterialTheme.typography.headlineMedium,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${s.getString(R.string.auth_default_currency_label)} · $baseCurrency ${CurrencyFormatter.symbolFor(baseCurrency)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background((if (settings.isAuthenticated) c.mint else c.muted).copy(alpha = 0.16f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (settings.isAuthenticated) Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                                    contentDescription = null,
                                    tint = if (settings.isAuthenticated) c.mint else c.muted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (settings.isAuthenticated) "Kakei Cloud" else s.getString(R.string.offline_mode_badge),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (settings.isAuthenticated) c.mint else c.muted
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surf2)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Sync, contentDescription = null, tint = c.mint, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        settings.syncInProgress -> s.getString(R.string.syncing)
                                        pendingMutations.isEmpty() -> s.getString(R.string.up_to_date)
                                        else -> "${s.getString(R.string.pending_mutations_label)}: ${pendingMutations.size}"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = c.text
                                )
                                Text(
                                    text = listOfNotNull(
                                        settings.lastSyncTimestamp?.let { CurrencyFormatter.formatShortDate(it) },
                                        if (pendingMutations.isEmpty()) s.getString(R.string.queue_empty) else null,
                                        settings.serverVersion?.let { "${s.getString(R.string.server)} $it" }
                                    ).joinToString(" · ").ifBlank { s.getString(R.string.server_not_connected) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            KakeiButton(
                                text = s.getString(R.string.sync),
                                kind = KakeiButtonKind.TONAL,
                                height = 40.dp,
                                enabled = !settings.syncInProgress,
                                onClick = { scope.launch { repository.synchronizeWithServer() } }
                            )
                        }
                        if (syncErrors.isNotEmpty()) {
                            Text(
                                text = s.getString(R.string.rejected_by_server) +
                                    "${syncErrors.size} · " + syncErrors.first().let { "${it.entityType} ${it.operation} → ${it.status} ${it.error}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.coral
                            )
                            Text(
                                text = s.getString(R.string.dismiss_sync_errors),
                                style = MaterialTheme.typography.labelLarge,
                                color = c.muted,
                                modifier = Modifier.clickable { repository.dismissSyncErrors() }
                            )
                        }
                    }
                }
            }

            // 2. Native SMS / Push capture & security toggles
            item { GroupCaption(s.getString(R.string.auto_capture)) }
            item {
                GroupCard {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Outlined.Chat,
                        iconColor = c.cyan,
                        title = s.getString(R.string.bank_sms),
                        subtitle = s.getString(R.string.kaspi_halyk_freedom_and_more),
                        checked = settings.smsCaptureEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(smsEnabled = it) }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.Notifications,
                        iconColor = c.amber,
                        title = s.getString(R.string.push_notifications),
                        subtitle = s.getString(R.string.n_5_min_deduplication),
                        checked = settings.pushCaptureEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(pushEnabled = it) }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.Bolt,
                        iconColor = c.mint,
                        title = s.getString(R.string.quick_category),
                        subtitle = s.getString(R.string.home_card_right_after_a),
                        checked = settings.instantPopupEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(instantPopup = it) }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.Fingerprint,
                        iconColor = c.violet,
                        title = s.getString(R.string.biometric_unlock),
                        checked = settings.biometricLockEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(biometricEnabled = it) }
                    )
                    if (settings.biometricLockEnabled) {
                        RowDivider(startIndent = 66.dp)
                        SettingsRow(
                            icon = Icons.Outlined.Timer,
                            iconColor = c.violet,
                            title = s.getString(R.string.lock_after),
                            subtitle = s.getString(R.string.time_in_background_before_relock),
                            value = lockTimeoutLabel(settings.lockTimeoutSeconds),
                            onClick = { showLockTimeoutDialog = true }
                        )
                    }
                }
            }

            // 2b. Auto-capture permissions, default capture account & alerts awaiting an account
            item {
                AutoCaptureCard(settings = settings, accounts = accounts, repository = repository)
            }

            // 2c. Passkeys registered on the Kakei account (refreshed on every sync)
            if (settings.isAuthenticated) {
                item { GroupCaption("Passkeys") }
                item {
                    GroupCard {
                        passkeys.forEachIndexed { index, passkey ->
                            if (index > 0) RowDivider(startIndent = 66.dp)
                            SettingsRow(
                                icon = Icons.Outlined.Key,
                                iconColor = c.violet,
                                title = passkey.name,
                                subtitle = listOfNotNull(
                                    "${s.getString(R.string.created)} ${CurrencyFormatter.formatShortDate(passkey.createdAt)}",
                                    passkey.lastUsedAt?.let {
                                        "${s.getString(R.string.last_used)} ${CurrencyFormatter.formatShortDate(it)}"
                                    }
                                ).joinToString(" · "),
                                showChevron = false,
                                trailing = {
                                    IconButton(onClick = { passkeyToDelete = passkey }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = s.getString(R.string.remove_passkey),
                                            tint = c.muted
                                        )
                                    }
                                }
                            )
                        }
                        if (passkeys.isNotEmpty()) RowDivider(startIndent = 66.dp)
                        SettingsRow(
                            icon = Icons.Outlined.Add,
                            iconColor = c.mint,
                            title = s.getString(R.string.add_passkey),
                            subtitle = s.getString(R.string.sign_in_with_fingerprint_or),
                            onClick = { showAddPasskeyDialog = true }
                        )
                    }
                }
            }

            // 2d. Daily reminder to record expenses
            item { GroupCaption(s.getString(R.string.reminders)) }
            item {
                GroupCard {
                    SettingsRow(
                        icon = Icons.Outlined.NotificationsActive,
                        iconColor = c.amber,
                        title = s.getString(R.string.expense_reminder),
                        subtitle = s.getString(R.string.expense_reminder_subtitle),
                        checked = settings.expenseReminderEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) enableExpenseReminder() else repository.setExpenseReminder(enabled = false)
                        }
                    )
                    if (settings.expenseReminderEnabled) {
                        RowDivider(startIndent = 66.dp)
                        SettingsRow(
                            icon = Icons.Outlined.Schedule,
                            iconColor = c.amber,
                            title = s.getString(R.string.expense_reminder_time),
                            value = reminderTimeLabel,
                            onClick = { showReminderTimeDialog = true }
                        )
                    }
                }
            }

            // 3. Appearance
            item { GroupCaption(s.getString(R.string.appearance)) }
            item {
                GroupCard {
                    SettingsRow(
                        icon = Icons.Outlined.Language,
                        iconColor = c.blue,
                        title = s.getString(R.string.language_label),
                        value = settings.language.displayName,
                        onClick = { showLanguageDialog = true }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.DarkMode,
                        iconColor = c.violet,
                        title = s.getString(R.string.theme_label),
                        value = themeTitle,
                        onClick = { showThemeDialog = true }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        iconColor = c.mint,
                        title = s.getString(R.string.auth_default_currency_label),
                        value = "$baseCurrency ${CurrencyFormatter.symbolFor(baseCurrency)}",
                        onClick = { showCurrencyDialog = true }
                    )
                }
            }

            // 4. Data hub: categories & smart rules, sharing, rates, import/export, backups, server
            item { GroupCaption(s.getString(R.string.data)) }
            item {
                GroupCard {
                    SettingsRow(
                        icon = Icons.Outlined.Sell,
                        iconColor = c.amber,
                        title = s.getString(R.string.categories_and_rules_title),
                        value = "${categories.size} · ${rules.size}",
                        onClick = { showCategoriesDialog = true }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.People,
                        iconColor = c.cyan,
                        title = s.getString(R.string.group_sharing_title),
                        value = "${groupMembers.size}",
                        onClick = { showSharingDialog = true }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.SwapHoriz,
                        iconColor = c.blue,
                        title = s.getString(R.string.exchange_rates_title),
                        value = usdRate?.let { "USD ${CurrencyFormatter.formatRate(it)}" },
                        onClick = { showRatesSheet = true }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.Download,
                        iconColor = c.mint,
                        title = s.getString(R.string.import_export_title),
                        value = "CSV, OFX",
                        onClick = { showExchangeDialog = true }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.Lock,
                        iconColor = c.pink,
                        title = s.getString(R.string.encrypted_backup),
                        subtitle = "AES-256-GCM",
                        onClick = { showBackupDialog = true }
                    )
                    RowDivider(startIndent = 66.dp)
                    SettingsRow(
                        icon = Icons.Outlined.Dns,
                        iconColor = c.muted,
                        title = s.getString(R.string.auth_server_url_label),
                        value = runCatching { URI(settings.serverBaseUrl).host }.getOrNull() ?: settings.serverBaseUrl,
                        onClick = { showServerDialog = true }
                    )
                }
            }

            // 5. Developer tools: synthetic alerts would book fake money into real accounts.
            if (BuildConfig.DEBUG) {
                item { GroupCaption(s.getString(R.string.developer)) }
                item {
                    GroupCard {
                        SettingsRow(
                            icon = Icons.Outlined.Sms,
                            iconColor = c.amber,
                            title = s.getString(R.string.simulate_sms_capture_button),
                            showChevron = false,
                            onClick = {
                                val raw = sampleBankAlerts[sampleSmsIdx % sampleBankAlerts.size]
                                sampleSmsIdx++
                                repository.ingestRawBankAlertAsync(raw, sourceChannel = "SMS", sender = "DEBUG-SIMULATOR")
                            }
                        )
                        if (repository.canLoadDemoData && accounts.isEmpty()) {
                            RowDivider(startIndent = 66.dp)
                            SettingsRow(
                                icon = Icons.Outlined.Inventory2,
                                iconColor = c.violet,
                                title = s.getString(R.string.load_demo_data_button),
                                showChevron = false,
                                onClick = { repository.loadDemoDataForDevelopment() }
                            )
                        }
                    }
                }
            }

            // 6. System & SQLCipher Diagnostics Panel
            item { GroupCaption(s.getString(R.string.diagnostics_title)) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kakeiCard()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val status = health
                    if (status == null) {
                        Text("…", color = c.muted)
                    } else {
                        DiagnosticLine("SQLCipher AES-256", if (status.sqlCipherActive) "ACTIVE" else "FALLBACK SQLITE", status.sqlCipherActive)
                        DiagnosticLine("NotificationListenerService", status.notificationListenerGranted.toString(), status.notificationListenerGranted)
                        DiagnosticLine("System overlay", status.overlayPermissionGranted.toString(), status.overlayPermissionGranted)
                        DiagnosticLine("Battery optimization ignored", status.batteryOptimizationIgnored.toString(), status.batteryOptimizationIgnored)
                        DiagnosticLine("Biometric hardware", status.biometricAvailable.toString(), status.biometricAvailable)
                    }
                    DiagnosticLine("Home screen widgets", "7 + QS Tile", true)
                }
            }

            // 7. Sign out
            item {
                Spacer(modifier = Modifier.height(6.dp))
                KakeiButton(
                    text = s.getString(R.string.logout_button),
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    kind = KakeiButtonKind.TONAL,
                    color = c.coral,
                    onClick = { repository.logout() },
                    modifier = Modifier.fillMaxWidth(),
                    height = 56.dp
                )
            }
        }
    }

    // Language
    if (showLanguageDialog) {
        ChoiceDialog(
            title = s.getString(R.string.language_label),
            options = AppLanguage.entries.map { it to it.displayName },
            selected = settings.language,
            onSelect = {
                repository.setLanguage(it)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    // Theme
    if (showThemeDialog) {
        ChoiceDialog(
            title = s.getString(R.string.theme_label),
            options = ThemeMode.entries.map { mode ->
                mode to when (mode) {
                    ThemeMode.DARK -> s.getString(R.string.theme_dark)
                    ThemeMode.LIGHT -> s.getString(R.string.theme_light)
                    ThemeMode.SYSTEM -> s.getString(R.string.theme_system)
                }
            },
            selected = settings.themeMode,
            onSelect = {
                repository.setThemeMode(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    // Biometric relock delay
    if (showLockTimeoutDialog) {
        ChoiceDialog(
            title = s.getString(R.string.lock_after),
            options = AppSettingsState.LOCK_TIMEOUT_OPTIONS.map { it to lockTimeoutLabel(it) },
            selected = settings.lockTimeoutSeconds,
            onSelect = {
                repository.setLockTimeout(it)
                showLockTimeoutDialog = false
            },
            onDismiss = { showLockTimeoutDialog = false }
        )
    }

    // Expense reminder time
    if (showReminderTimeDialog) {
        val timeState = rememberTimePickerState(
            initialHour = settings.expenseReminderMinuteOfDay / 60,
            initialMinute = settings.expenseReminderMinuteOfDay % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showReminderTimeDialog = false },
            containerColor = c.surf,
            title = { Text(s.getString(R.string.expense_reminder_time)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.setExpenseReminder(minuteOfDay = timeState.hour * 60 + timeState.minute)
                    showReminderTimeDialog = false
                }) { Text(s.getString(R.string.done)) }
            },
            dismissButton = {
                TextButton(onClick = { showReminderTimeDialog = false }) { Text(s.getString(R.string.cancel_button)) }
            },
            text = { TimePicker(state = timeState) }
        )
    }

    // Base currency
    if (showCurrencyDialog) {
        ChoiceDialog(
            title = s.getString(R.string.auth_default_currency_label),
            options = CurrencyFormatter.supportedCurrencies.map { it to "$it ${CurrencyFormatter.symbolFor(it)}" },
            selected = baseCurrency,
            onSelect = {
                repository.updateProfile(settings.profile.name, it, settings.language)
                showCurrencyDialog = false
            },
            onDismiss = { showCurrencyDialog = false }
        )
    }

    // New passkey name, then the system Credential Manager sheet
    if (showAddPasskeyDialog) {
        var passkeyName by remember { mutableStateOf(android.os.Build.MODEL) }
        AlertDialog(
            onDismissRequest = { showAddPasskeyDialog = false },
            title = { Text(s.getString(R.string.new_passkey)) },
            text = {
                OutlinedTextField(
                    value = passkeyName,
                    onValueChange = { passkeyName = it.take(100) },
                    label = { Text(s.getString(R.string.category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.registerPasskey(context, passkeyName)
                        showAddPasskeyDialog = false
                    },
                    enabled = !settings.syncInProgress
                ) {
                    Text(s.getString(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPasskeyDialog = false }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    passkeyToDelete?.let { passkey ->
        AlertDialog(
            onDismissRequest = { passkeyToDelete = null },
            title = { Text(s.getString(R.string.remove_passkey_confirm)) },
            text = {
                Text(
                    s.getString(R.string.will_no_longer_sign_you, passkey.name)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.deletePasskey(passkey.id)
                        passkeyToDelete = null
                    }
                ) {
                    Text(s.getString(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { passkeyToDelete = null }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    // Server URL
    if (showServerDialog) {
        var serverUrlInput by remember(settings.serverBaseUrl) { mutableStateOf(settings.serverBaseUrl) }
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text(s.getString(R.string.auth_server_url_label)) },
            text = {
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.setServerBaseUrl(serverUrlInput)
                        scope.launch { repository.synchronizeWithServer() }
                        showServerDialog = false
                    }
                ) {
                    Text(s.getString(R.string.sync_now_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    // Exchange rates (server rates + manual overrides)
    if (showRatesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRatesSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.bg
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                ExchangeRatesCard(settings = settings, repository = repository)
            }
        }
    }

    // Categories, Labels & Smart Rule Simulator Dialog
    if (showCategoriesDialog) {
        var newCatName by remember { mutableStateOf("") }
        var newRulePattern by remember { mutableStateOf("") }
        var newRuleMatchType by remember { mutableStateOf("CONTAINS") }
        var newRuleCatId by remember { mutableStateOf("") }
        var simulatorInput by remember { mutableStateOf("Magnum Cash & Carry Almaty") }

        val simResult = remember(simulatorInput, rules, categories) {
            SmartMerchantMatcher.classify(simulatorInput, null, rules, categories)
        }
        val simCategoryName = categories.firstOrNull { it.id == simResult.categoryId }
            ?.localizedName(settings.language.code) ?: s.getString(R.string.none)

        AlertDialog(
            onDismissRequest = { showCategoriesDialog = false },
            title = { Text(s.getString(R.string.categories_and_rules_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(s.getString(R.string.smart_rule_simulator), fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = simulatorInput,
                        onValueChange = { simulatorInput = it },
                        label = { Text(s.getString(R.string.test_merchant_text)) },
                        singleLine = true
                    )
                    Text(
                        text = "➔ ${s.getString(R.string.category_label)}: $simCategoryName (${simResult.confidenceLabel})",
                        color = c.mint,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(color = c.line)
                    OutlinedTextField(
                        value = newRulePattern,
                        onValueChange = { newRulePattern = it },
                        label = { Text(s.getString(R.string.new_rule_pattern_e_g)) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("EXACT", "PREFIX", "CONTAINS").forEach { mt ->
                            KakeiChip(
                                text = when (mt) {
                                    "EXACT" -> s.getString(R.string.exact)
                                    "PREFIX" -> s.getString(R.string.prefix)
                                    else -> s.getString(R.string.contains)
                                },
                                selected = newRuleMatchType == mt,
                                onClick = { newRuleMatchType = mt }
                            )
                        }
                    }
                    CategoryPickerRow(
                        categories = categories,
                        selectedId = newRuleCatId,
                        langCode = settings.language.code,
                        onSelect = { newRuleCatId = it.id }
                    )
                    Button(
                        onClick = {
                            if (newRulePattern.isNotBlank() && newRuleCatId.isNotBlank()) {
                                repository.createSmartRule(newRulePattern, newRuleMatchType, newRuleCatId)
                                newRulePattern = ""
                            }
                        }
                    ) {
                        Text(s.getString(R.string.add_smart_rule))
                    }
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text(s.getString(R.string.new_category_name)) },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = {
                            if (newCatName.isNotBlank()) {
                                repository.createCategory(newCatName, null, "tag", "#30A46C")
                                newCatName = ""
                            }
                        }
                    ) {
                        Text(s.getString(R.string.add_category))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoriesDialog = false }) { Text(s.getString(R.string.done)) }
            }
        )
    }

    // Group Sharing Dialog (/api/groups/*)
    if (showSharingDialog) {
        var memberEmail by remember { mutableStateOf("") }
        var permLevel by remember { mutableStateOf("TRACK_READ") }
        val targetAccId = accounts.firstOrNull()?.id.orEmpty()

        AlertDialog(
            onDismissRequest = { showSharingDialog = false },
            title = { Text(s.getString(R.string.group_sharing_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = memberEmail,
                        onValueChange = { memberEmail = it },
                        label = { Text(s.getString(R.string.family_member_email)) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ADMIN", "TRACK_READ", "READ_ONLY").forEach { p ->
                            KakeiChip(text = s.permissionLevel(p), selected = permLevel == p, onClick = { permLevel = p })
                        }
                    }
                    groupMembers.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(name = m.memberName.ifBlank { m.memberEmail }, color = accentForName(m.memberEmail), size = 32.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "${m.memberEmail} (${s.permissionLevel(m.permissionLevel)})",
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { repository.removeGroupMember(m) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = s.getString(R.string.delete_button), tint = c.muted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (memberEmail.isNotBlank() && targetAccId.isNotBlank()) {
                            repository.shareAccountWithMember(targetAccId, memberEmail, permLevel)
                            memberEmail = ""
                        }
                    }
                ) {
                    Text(s.getString(R.string.invite_member))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSharingDialog = false }) { Text(s.getString(R.string.close)) }
            }
        )
    }

    // Exchange CSV / OFX 2.2 Import & Export Dialog (/api/exchange/*)
    if (showExchangeDialog) {
        var format by remember { mutableStateOf("csv") }
        var csvInput by remember {
            mutableStateOf(
                "Date,Amount,Payee,Note\n2026-10-17,-5400,Magnum Supermarket,Imported CSV row\n2026-10-18,-1800,Starbucks Coffee,Morning espresso"
            )
        }
        val targetAccId = accounts.firstOrNull()?.id.orEmpty()

        AlertDialog(
            onDismissRequest = { showExchangeDialog = false },
            title = { Text(s.getString(R.string.import_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("csv", "ofx").forEach { f ->
                            KakeiChip(text = f.uppercase(), selected = format == f, onClick = { format = f })
                        }
                    }
                    OutlinedTextField(
                        value = csvInput,
                        onValueChange = { csvInput = it },
                        label = { Text(s.getString(R.string.statement_content)) },
                        maxLines = 5
                    )
                    OutlinedButton(
                        onClick = {
                            val exported = repository.exportCsvString()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_TEXT, exported)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, s.getString(R.string.export_csv_report)))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s.getString(R.string.export_ledger_to_csv))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (targetAccId.isNotBlank() && csvInput.isNotBlank()) {
                            repository.importStatementContent(
                                accountId = targetAccId,
                                format = format,
                                delimiter = ",",
                                content = csvInput
                            )
                            showExchangeDialog = false
                        }
                    }
                ) {
                    Text(s.getString(R.string.import_deduplicate))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExchangeDialog = false }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    // Encrypted Backup Dialog (AES-256-GCM + PBKDF2)
    if (showBackupDialog) {
        var passphrase by remember { mutableStateOf("kakei-vault-2026") }
        var cipherText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(s.getString(R.string.encrypted_backup_aes_256_gcm)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(s.getString(R.string.backup_passphrase)) },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            cipherText = repository.createEncryptedBackupString(passphrase)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s.getString(R.string.generate_encrypted_snapshot))
                    }
                    if (cipherText.isNotBlank()) {
                        OutlinedTextField(
                            value = cipherText,
                            onValueChange = { cipherText = it },
                            label = { Text(s.getString(R.string.encrypted_base64_payload)) },
                            maxLines = 4
                        )
                        FilledTonalButton(
                            onClick = {
                                repository.restoreEncryptedBackupString(cipherText, passphrase)
                                showBackupDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(s.getString(R.string.restore_from_payload))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupDialog = false }) { Text(s.getString(R.string.close)) }
            }
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String, ok: Boolean) {
    val c = KakeiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (ok) c.mint else c.amber)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

/** Single-choice list dialog used for language, theme and base currency. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.getString(R.string.cancel_button)) }
        }
    )
}
