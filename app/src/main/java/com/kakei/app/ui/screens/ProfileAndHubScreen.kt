package com.kakei.app.ui.screens

import android.content.Intent
import com.kakei.app.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.automation.SmartMerchantMatcher
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.LocalAppStrings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

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
    labels: List<LabelDto>,
    rules: List<CounterpartyRuleDto>,
    groupMembers: List<GroupMemberDto>,
    pendingMutations: List<SyncMutationEntity>,
    repository: KakeiRepository,
    onBack: () -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val isRu = s.language == AppLanguage.RU
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
    val rateBook by repository.rateBook.collectAsState()
    val baseCurrency = settings.profile.defaultCurrency
    val themeTitle = when (settings.themeMode) {
        ThemeMode.DARK -> s.themeDark
        ThemeMode.LIGHT -> s.themeLight
        ThemeMode.SYSTEM -> s.themeSystem
    }
    val usdRate = if (baseCurrency.equals("USD", ignoreCase = true)) null else rateBook.rate("USD", baseCurrency)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top bar
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = s.pick("Назад", "Back"),
                    onClick = onBack,
                    size = 44.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(s.navProfile, style = MaterialTheme.typography.headlineMedium, color = c.text)
            }
        }

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
                            "${s.baseCurrencyLabel} · $baseCurrency ${CurrencyFormatter.symbolFor(baseCurrency)}",
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
                                if (settings.isAuthenticated) "Kakei Cloud" else s.offlineModeBadge,
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
                                    settings.syncInProgress -> s.pick("Синхронизация…", "Syncing…")
                                    pendingMutations.isEmpty() -> s.pick("Синхронизировано", "Up to date")
                                    else -> "${s.pendingMutationsLabel}: ${pendingMutations.size}"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = c.text
                            )
                            Text(
                                text = listOfNotNull(
                                    settings.lastSyncTimestamp?.let { CurrencyFormatter.formatShortDate(it) },
                                    if (pendingMutations.isEmpty()) s.pick("очередь пуста", "queue empty") else null,
                                    settings.serverVersion?.let { "${s.pick("сервер", "server")} $it" }
                                ).joinToString(" · ").ifBlank { s.pick("Сервер не подключён", "Server not connected") },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        KakeiButton(
                            text = s.pick("Обновить", "Sync"),
                            kind = KakeiButtonKind.TONAL,
                            height = 40.dp,
                            enabled = !settings.syncInProgress,
                            onClick = { scope.launch { repository.synchronizeWithServer() } }
                        )
                    }
                    if (syncErrors.isNotEmpty()) {
                        Text(
                            text = (if (isRu) "Отклонено сервером: " else "Rejected by server: ") +
                                "${syncErrors.size} · " + syncErrors.first().let { "${it.entityType} ${it.operation} → ${it.status} ${it.error}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.coral
                        )
                        Text(
                            text = if (isRu) "Скрыть ошибки синхронизации" else "Dismiss sync errors",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.muted,
                            modifier = Modifier.clickable { repository.dismissSyncErrors() }
                        )
                    }
                }
            }
        }

        // 2. Native SMS / Push capture & security toggles
        item { GroupCaption(s.pick("Автозахват", "Auto-capture")) }
        item {
            GroupCard {
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    iconColor = c.cyan,
                    title = s.pick("SMS банков", "Bank SMS"),
                    subtitle = s.pick("Kaspi, Halyk, Freedom и другие", "Kaspi, Halyk, Freedom and more"),
                    checked = settings.smsCaptureEnabled,
                    onCheckedChange = { repository.updateAutomationToggles(smsEnabled = it) }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.Notifications,
                    iconColor = c.amber,
                    title = s.pick("Push-уведомления", "Push notifications"),
                    subtitle = s.pick("Дедупликация 5 мин", "5-min deduplication"),
                    checked = settings.pushCaptureEnabled,
                    onCheckedChange = { repository.updateAutomationToggles(pushEnabled = it) }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.Bolt,
                    iconColor = c.mint,
                    title = s.pick("Быстрая категория", "Quick category"),
                    subtitle = s.pick("Карточка на главной сразу после покупки", "Home card right after a purchase"),
                    checked = settings.instantPopupEnabled,
                    onCheckedChange = { repository.updateAutomationToggles(instantPopup = it) }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.Fingerprint,
                    iconColor = c.violet,
                    title = s.pick("Вход по биометрии", "Biometric unlock"),
                    checked = settings.biometricLockEnabled,
                    onCheckedChange = { repository.updateAutomationToggles(biometricEnabled = it) }
                )
            }
        }

        // 2b. Auto-capture permissions, default capture account & alerts awaiting an account
        item {
            AutoCaptureCard(settings = settings, accounts = accounts, repository = repository)
        }

        // 3. Appearance
        item { GroupCaption(s.pick("Оформление", "Appearance")) }
        item {
            GroupCard {
                SettingsRow(
                    icon = Icons.Outlined.Language,
                    iconColor = c.blue,
                    title = s.languageLabel,
                    value = settings.language.displayName,
                    onClick = { showLanguageDialog = true }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.DarkMode,
                    iconColor = c.violet,
                    title = s.themeLabel,
                    value = themeTitle,
                    onClick = { showThemeDialog = true }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    iconColor = c.mint,
                    title = s.baseCurrencyLabel,
                    value = "$baseCurrency ${CurrencyFormatter.symbolFor(baseCurrency)}",
                    onClick = { showCurrencyDialog = true }
                )
            }
        }

        // 4. Data hub: categories & smart rules, sharing, rates, import/export, backups, server
        item { GroupCaption(s.pick("Данные", "Data")) }
        item {
            GroupCard {
                SettingsRow(
                    icon = Icons.Outlined.Sell,
                    iconColor = c.amber,
                    title = s.categoriesAndRulesTitle,
                    value = "${categories.size} · ${rules.size}",
                    onClick = { showCategoriesDialog = true }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.People,
                    iconColor = c.cyan,
                    title = s.groupSharingTitle,
                    value = "${groupMembers.size}",
                    onClick = { showSharingDialog = true }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.SwapHoriz,
                    iconColor = c.blue,
                    title = s.exchangeRatesTitle,
                    value = usdRate?.let { "USD ${CurrencyFormatter.formatRate(it)}" },
                    onClick = { showRatesSheet = true }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.Download,
                    iconColor = c.mint,
                    title = s.importExportTitle,
                    value = "CSV, OFX",
                    onClick = { showExchangeDialog = true }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    iconColor = c.pink,
                    title = s.pick("Зашифрованная копия", "Encrypted backup"),
                    subtitle = "AES-256-GCM",
                    onClick = { showBackupDialog = true }
                )
                RowDivider(startIndent = 66.dp)
                SettingsRow(
                    icon = Icons.Outlined.Dns,
                    iconColor = c.muted,
                    title = s.authServerUrlLabel,
                    value = runCatching { URI(settings.serverBaseUrl).host }.getOrNull() ?: settings.serverBaseUrl,
                    onClick = { showServerDialog = true }
                )
            }
        }

        // 5. Developer tools: synthetic alerts would book fake money into real accounts.
        if (BuildConfig.DEBUG) {
            item { GroupCaption(s.pick("Разработка", "Developer")) }
            item {
                GroupCard {
                    SettingsRow(
                        icon = Icons.Outlined.Sms,
                        iconColor = c.amber,
                        title = s.simulateSmsCaptureButton,
                        showChevron = false,
                        onClick = {
                            val raw = sampleBankAlerts[sampleSmsIdx % sampleBankAlerts.size]
                            sampleSmsIdx++
                            repository.ingestRawBankAlert(raw, sourceChannel = "SMS", sender = "DEBUG-SIMULATOR")
                        }
                    )
                    if (repository.canLoadDemoData && accounts.isEmpty()) {
                        RowDivider(startIndent = 66.dp)
                        SettingsRow(
                            icon = Icons.Outlined.Inventory2,
                            iconColor = c.violet,
                            title = s.loadDemoDataButton,
                            showChevron = false,
                            onClick = { repository.loadDemoDataForDevelopment() }
                        )
                    }
                }
            }
        }

        // 6. System & SQLCipher Diagnostics Panel
        item { GroupCaption(s.diagnosticsTitle) }
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
                text = s.logoutButton,
                icon = Icons.AutoMirrored.Outlined.Logout,
                kind = KakeiButtonKind.TONAL,
                color = c.coral,
                onClick = { repository.logout() },
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp
            )
        }
    }

    // Language
    if (showLanguageDialog) {
        ChoiceDialog(
            title = s.languageLabel,
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
            title = s.themeLabel,
            options = ThemeMode.entries.map { mode ->
                mode to when (mode) {
                    ThemeMode.DARK -> s.themeDark
                    ThemeMode.LIGHT -> s.themeLight
                    ThemeMode.SYSTEM -> s.themeSystem
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

    // Base currency
    if (showCurrencyDialog) {
        ChoiceDialog(
            title = s.baseCurrencyLabel,
            options = CurrencyFormatter.supportedCurrencies.map { it to "$it ${CurrencyFormatter.symbolFor(it)}" },
            selected = baseCurrency,
            onSelect = {
                repository.updateProfile(settings.profile.name, it, settings.language)
                showCurrencyDialog = false
            },
            onDismiss = { showCurrencyDialog = false }
        )
    }

    // Server URL
    if (showServerDialog) {
        var serverUrlInput by remember(settings.serverBaseUrl) { mutableStateOf(settings.serverBaseUrl) }
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text(s.authServerUrlLabel) },
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
                    Text(s.syncNowButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) { Text(s.cancelButton) }
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
        var newRuleCatId by remember { mutableStateOf(categories.firstOrNull()?.id.orEmpty()) }
        var simulatorInput by remember { mutableStateOf("Magnum Cash & Carry Almaty") }

        val simResult = remember(simulatorInput, rules, categories) {
            SmartMerchantMatcher.classify(simulatorInput, null, rules, categories)
        }
        val simCategoryName = categories.firstOrNull { it.id == simResult.categoryId }
            ?.localizedName(settings.language.code) ?: s.pick("нет", "None")

        AlertDialog(
            onDismissRequest = { showCategoriesDialog = false },
            title = { Text(s.categoriesAndRulesTitle) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(s.pick("Проверка правил:", "Smart Rule Simulator:"), fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = simulatorInput,
                        onValueChange = { simulatorInput = it },
                        label = { Text(s.pick("Текст для проверки", "Test Merchant Text")) },
                        singleLine = true
                    )
                    Text(
                        text = "➔ ${s.categoryLabel}: $simCategoryName (${simResult.confidenceLabel})",
                        color = c.mint,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(color = c.line)
                    OutlinedTextField(
                        value = newRulePattern,
                        onValueChange = { newRulePattern = it },
                        label = { Text(s.pick("Шаблон правила (например, Wolt)", "New Rule Pattern (e.g. Wolt)")) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("EXACT", "PREFIX", "CONTAINS").forEach { mt ->
                            KakeiChip(
                                text = when (mt) {
                                    "EXACT" -> s.pick("Совпадает", "Exact")
                                    "PREFIX" -> s.pick("Начинается с", "Prefix")
                                    else -> s.pick("Содержит", "Contains")
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
                        Text(s.pick("+ Добавить правило", "+ Add Smart Rule"))
                    }
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text(s.pick("Название категории", "New Category Name")) },
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
                        Text(s.pick("+ Добавить категорию", "+ Add Category"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoriesDialog = false }) { Text(s.pick("Готово", "Done")) }
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = memberEmail,
                        onValueChange = { memberEmail = it },
                        label = { Text(s.pick("Email участника", "Family Member Email")) },
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
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = s.deleteButton, tint = c.muted)
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
                    Text(s.pick("Пригласить", "Invite Member"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSharingDialog = false }) { Text(s.pick("Закрыть", "Close")) }
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("csv", "ofx").forEach { f ->
                            KakeiChip(text = f.uppercase(), selected = format == f, onClick = { format = f })
                        }
                    }
                    OutlinedTextField(
                        value = csvInput,
                        onValueChange = { csvInput = it },
                        label = { Text(s.pick("Содержимое выписки (CSV / OFX 2.2)", "Statement Content (CSV / OFX 2.2)")) },
                        maxLines = 5
                    )
                    OutlinedButton(
                        onClick = {
                            val exported = repository.exportCsvString()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_TEXT, exported)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, s.pick("Экспорт CSV", "Export CSV Report")))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s.pick("Экспорт операций в CSV", "Export Ledger to CSV"))
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
                    Text(s.pick("Импортировать без дублей", "Import & Deduplicate"))
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
            title = { Text(s.pick("Зашифрованный бэкап (AES-256-GCM)", "Encrypted Backup (AES-256-GCM)")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(s.pick("Пароль бэкапа", "Backup Passphrase")) },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            cipherText = repository.createEncryptedBackupString(passphrase)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s.pick("Создать зашифрованный снимок", "Generate Encrypted Snapshot"))
                    }
                    if (cipherText.isNotBlank()) {
                        OutlinedTextField(
                            value = cipherText,
                            onValueChange = { cipherText = it },
                            label = { Text(s.pick("Зашифрованные данные (Base64)", "Encrypted Base64 Payload")) },
                            maxLines = 4
                        )
                        FilledTonalButton(
                            onClick = {
                                repository.restoreEncryptedBackupString(cipherText, passphrase)
                                showBackupDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(s.pick("Восстановить из данных", "Restore from Payload"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupDialog = false }) { Text(s.pick("Закрыть", "Close")) }
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
    val s = LocalAppStrings.current
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
            TextButton(onClick = onDismiss) { Text(s.cancelButton) }
        }
    )
}
