package com.kakei.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.automation.SmartMerchantMatcher
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.security.HealthCheckHelper
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.local.SyncMutationEntity
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Tab 5: Profile, Synchronization, Security (2FA/Passkeys/Biometrics), Categories & Smart Merchant Rule Simulator,
 * Family Group Sharing, CSV/OFX Import & Export, SMS/Push Automation & System Diagnostics.
 */
@Composable
fun ProfileAndHubScreen(
    settings: AppSettingsState,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    labels: List<LabelDto>,
    rules: List<CounterpartyRuleDto>,
    groupMembers: List<GroupMemberDto>,
    pendingMutations: List<SyncMutationEntity>,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    val isRu = s.language == AppLanguage.RU
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCategoriesDialog by remember { mutableStateOf(false) }
    var showSharingDialog by remember { mutableStateOf(false) }
    var showExchangeDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember(settings.serverBaseUrl) { mutableStateOf(settings.serverBaseUrl) }
    var sampleSmsIdx by remember { mutableIntStateOf(0) }

    val sampleBankAlerts = listOf(
        "Kaspi Gold: Покупка 4 850 ₸ в Magnum Cash & Carry. Доступно: 607 550 ₸",
        "Halyk Bank: Оплата 1 950 KZT в Starbucks Coffee. Карта *4421",
        "Freedom Bank: Пополнение 250 USD зарплатный перевод Tech Corp",
        "Kaspi Gold: Оплата 3 200 ₸ в Yandex Go. Доступно: 604 350 ₸"
    )

    val health = remember(repository.database.isSqlCipherActive) {
        HealthCheckHelper.inspect(context, repository.database.isSqlCipherActive)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. User Profile, Language, Theme & Base Currency Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = EmeraldBrand)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    ) {
                        Text(
                            text = settings.profile.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${settings.profile.email} · ${settings.profile.defaultCurrency}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (settings.isAuthenticated) "Kakei Cloud" else s.offlineModeBadge,
                                color = emeraldText(),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                // Language selector (2 equal columns)
                Text(
                    text = s.languageLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = settings.language == lang,
                            onClick = { repository.setLanguage(lang) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = lang.displayName,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }

                // Theme selector (3 equal columns: Dark / Light / System)
                Text(
                    text = s.themeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val modeTitle = when (mode) {
                            ThemeMode.DARK -> s.themeDark
                            ThemeMode.LIGHT -> s.themeLight
                            ThemeMode.SYSTEM -> s.themeSystem
                        }
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { repository.setThemeMode(mode) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = modeTitle,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                // Base Currency selector (2 rows x 3 equal columns so all 6 currencies are visible)
                Text(
                    text = s.baseCurrencyLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurrencyFormatter.supportedCurrencies.chunked(3).forEach { rowCurrencies ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowCurrencies.forEach { curr ->
                                FilterChip(
                                    selected = settings.profile.defaultCurrency == curr,
                                    onClick = {
                                        repository.updateProfile(settings.profile.name, curr, settings.language)
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = {
                                        Text(
                                            text = "$curr ${CurrencyFormatter.symbolFor(curr)}",
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Kakei API Synchronization & Offline Outbox Queue
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = IndigoAccent)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = s.syncStatusTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    label = { Text(s.authServerUrlLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val syncErrors by repository.syncErrors.collectAsState()
                Text(
                    text = "${s.pendingMutationsLabel}: ${pendingMutations.size} · Server: ${settings.serverVersion ?: if (isRu) "Не подключен" else "Not connected"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (syncErrors.isNotEmpty()) {
                    Text(
                        text = (if (isRu) "Отклонено сервером: " else "Rejected by server: ") +
                            "${syncErrors.size} · " + syncErrors.first().let { "${it.entityType} ${it.operation} → ${it.status} ${it.error}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { repository.dismissSyncErrors() }) {
                        Text(if (isRu) "Скрыть ошибки синхронизации" else "Dismiss sync errors")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            repository.setServerBaseUrl(serverUrlInput)
                            scope.launch { repository.synchronizeWithServer() }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldBrand,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(s.syncNowButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { repository.logout() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(s.logoutButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // 3. Native SMS / Push Capture & Simulator
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = AmberWarning)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = s.automationAndSmsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRu) "Парсер банковских SMS (SmsReceiver)" else "Bank SMS Parser (SmsReceiver)",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = settings.smsCaptureEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(smsEnabled = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRu) "Перехват банковских Push (дедупликация 5 мин)" else "Payment Push Listener (5-min dedup)",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = settings.pushCaptureEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(pushEnabled = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRu) "Мгновенное окно выбора категории (Overlay)" else "Instant Category Popup Overlay",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = settings.instantPopupEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(instantPopup = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRu) "Биометрическая авто-блокировка экрана" else "Biometric Screen-Off Auto-Lock",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = settings.biometricLockEnabled,
                        onCheckedChange = { repository.updateAutomationToggles(biometricEnabled = it) }
                    )
                }

                FilledTonalButton(
                    onClick = {
                        val raw = sampleBankAlerts[sampleSmsIdx % sampleBankAlerts.size]
                        sampleSmsIdx++
                        repository.ingestRawBankAlert(raw, sourceChannel = "SMS")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s.simulateSmsCaptureButton)
                }
            }
        }

        // 4. Feature Hub Action Cards (Categories & Smart Rules, Group Sharing, Import/Export, Encrypted Backups)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = CyanTransfer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { showCategoriesDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldBrand,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Category, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${s.categoriesAndRulesTitle} (${categories.size} / ${rules.size})")
                }

                FilledTonalButton(
                    onClick = { showSharingDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Group, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${s.groupSharingTitle} (${groupMembers.size})")
                }

                FilledTonalButton(
                    onClick = { showExchangeDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ImportExport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s.importExportTitle)
                }

                OutlinedButton(
                    onClick = { showBackupDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isRu) "Зашифрованный бэкап AES-256-GCM" else "AES-256-GCM Encrypted Backup & Restore")
                }
            }
        }

        // 5. System & SQLCipher Diagnostics Panel
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = PurpleGoal)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = s.diagnosticsTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "• SQLCipher AES-256 Hardware Keystore: ${if (health.sqlCipherActive) "ACTIVE" else "FALLBACK SQLITE"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• NotificationListenerService Bound: ${health.notificationListenerGranted}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• System Overlay Permission: ${health.overlayPermissionGranted}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Battery Optimization Ignored: ${health.batteryOptimizationIgnored}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Biometric Hardware Ready: ${health.biometricAvailable}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Home Screen Widgets Registered: 7 AppWidgetProviders + QuickAddTileService",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Categories, Labels & Smart Rule Simulator Dialog
    if (showCategoriesDialog) {
        var newCatName by remember { mutableStateOf("") }
        var newRulePattern by remember { mutableStateOf("") }
        var newRuleMatchType by remember { mutableStateOf("CONTAINS") }
        var newRuleCatId by remember { mutableStateOf(categories.firstOrNull()?.id.orEmpty()) }
        var simulatorInput by remember { mutableStateOf("Magnum Cash & Carry Almaty") }

        val simResult = remember(simulatorInput, rules, categories) {
            SmartMerchantMatcher.classify(simulatorInput, null, rules, categories)
        }
        val simCategoryName = categories.firstOrNull { it.id == simResult.categoryId }
            ?.localizedName(settings.language.code) ?: "None"

        AlertDialog(
            onDismissRequest = { showCategoriesDialog = false },
            title = { Text(s.categoriesAndRulesTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Smart Rule Simulator:", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = simulatorInput,
                        onValueChange = { simulatorInput = it },
                        label = { Text("Test Merchant Text") },
                        singleLine = true
                    )
                    Text(
                        text = "➔ Category: $simCategoryName (${simResult.confidenceLabel})",
                        color = emeraldText(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider()
                    OutlinedTextField(
                        value = newRulePattern,
                        onValueChange = { newRulePattern = it },
                        label = { Text("New Rule Pattern (e.g. Wolt)") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("EXACT", "PREFIX", "CONTAINS").forEach { mt ->
                            FilterChip(
                                selected = newRuleMatchType == mt,
                                onClick = { newRuleMatchType = mt },
                                label = { Text(mt) }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (newRulePattern.isNotBlank() && newRuleCatId.isNotBlank()) {
                                repository.createSmartRule(newRulePattern, newRuleMatchType, newRuleCatId)
                                newRulePattern = ""
                            }
                        }
                    ) {
                        Text("+ Add Smart Rule")
                    }
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text("New Category Name") },
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
                        Text("+ Add Category")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoriesDialog = false }) { Text("Done") }
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
            title = { Text(s.groupSharingTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = memberEmail,
                        onValueChange = { memberEmail = it },
                        label = { Text("Family Member Email") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ADMIN", "TRACK_READ", "READ_ONLY").forEach { p ->
                            FilterChip(
                                selected = permLevel == p,
                                onClick = { permLevel = p },
                                label = { Text(p) }
                            )
                        }
                    }
                    groupMembers.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${m.memberEmail} (${m.permissionLevel})",
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { repository.removeGroupMember(m) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
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
                    Text("Invite Member")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSharingDialog = false }) { Text("Close") }
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
            title = { Text(s.importExportTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("csv", "ofx").forEach { f ->
                            FilterChip(
                                selected = format == f,
                                onClick = { format = f },
                                label = { Text(f.uppercase()) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = csvInput,
                        onValueChange = { csvInput = it },
                        label = { Text("Statement Content (CSV / OFX 2.2)") },
                        maxLines = 5
                    )
                    OutlinedButton(
                        onClick = {
                            val exported = repository.exportCsvString()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_TEXT, exported)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Export CSV Report"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Ledger to CSV")
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
                    Text("Import & Deduplicate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExchangeDialog = false }) { Text(s.cancelButton) }
            }
        )
    }

    // Encrypted Backup Dialog (AES-256-GCM + PBKDF2)
    if (showBackupDialog) {
        var passphrase by remember { mutableStateOf("kakei-vault-2026") }
        var cipherText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Encrypted Backup (AES-256-GCM)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Backup Passphrase") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            cipherText = repository.createEncryptedBackupString(passphrase)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Generate Encrypted Snapshot")
                    }
                    if (cipherText.isNotBlank()) {
                        OutlinedTextField(
                            value = cipherText,
                            onValueChange = { cipherText = it },
                            label = { Text("Encrypted Base64 Payload") },
                            maxLines = 4
                        )
                        FilledTonalButton(
                            onClick = {
                                repository.restoreEncryptedBackupString(cipherText, passphrase)
                                showBackupDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Restore from Payload")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupDialog = false }) { Text("Close") }
            }
        )
    }
}
