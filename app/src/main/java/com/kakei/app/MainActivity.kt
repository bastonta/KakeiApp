package com.kakei.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.security.BiometricHelper
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.BottomNavItem
import com.kakei.app.ui.components.KakeiBottomBar
import com.kakei.app.ui.screens.*
import com.kakei.app.ui.theme.KakeiTheme
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    /** True while a BiometricPrompt is on screen (it may pause/stop this activity). */
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as KakeiApplication
        syncAppLocale(app.repository)
        val openQuickAddInitial = intent?.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false) == true ||
            intent?.getStringExtra(EXTRA_WIDGET_ACTION) == "QUICK_ADD"
        val widgetAction = intent?.getStringExtra(EXTRA_WIDGET_ACTION)

        setContent {
            KakeiAppRoot(
                repository = app.repository,
                initialQuickAdd = openQuickAddInitial,
                initialWidgetAction = widgetAction,
                lockState = AppLockState(
                    isUnlocked = unlocked,
                    autoPromptPending = autoPromptPending,
                    lastError = lockError
                ),
                onUnlockRequest = ::requestUnlock,
                onLockDisabled = { unlocked = true }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val since = backgroundedAt
        backgroundedAt = 0L
        // Background time after which the biometric lock engages again (user setting).
        val relockAfterMs = (application as KakeiApplication).repository
            .settingsState.value.lockTimeoutSeconds * 1000L
        if (since != 0L && SystemClock.elapsedRealtime() - since >= relockAfterMs) {
            unlocked = false
            autoPromptPending = true
            lockError = null
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !authInProgress) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Keeps the per-app locale and the repository language in step. A locale picked in the
     * system per-app language settings wins on launch; afterwards every language change made in
     * the app (or pulled from the server profile) is applied as the per-app locale, which
     * recreates the activity with matching resources. The first launch after an update applies
     * the stored language, so existing installs keep theirs.
     */
    private fun syncAppLocale(repository: KakeiRepository) {
        AppCompatDelegate.getApplicationLocales()[0]?.let {
            repository.setLanguage(AppLanguage.fromCode(it.language))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsState.map { it.language }.distinctUntilChanged().collect { language ->
                    if (AppCompatDelegate.getApplicationLocales()[0]?.language != language.code) {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.code))
                    }
                }
            }
        }
    }

    private fun requestUnlock(title: String, subtitle: String) {
        autoPromptPending = false
        if (authInProgress) return
        authInProgress = true
        lockError = null
        BiometricHelper.authenticate(
            activity = this,
            title = title,
            subtitle = subtitle,
            onSuccess = {
                authInProgress = false
                unlocked = true
            },
            onError = { message ->
                authInProgress = false
                lockError = message
            }
        )
    }

    companion object {
        const val EXTRA_OPEN_QUICK_ADD = "extra_open_quick_add"
        const val EXTRA_WIDGET_ACTION = "extra_widget_action"

        // Process-scoped lock state: survives configuration changes and activity
        // recreation, and resets to locked when the process dies.
        private var unlocked by mutableStateOf(false)
        private var autoPromptPending by mutableStateOf(true)
        private var lockError by mutableStateOf<String?>(null)
        private var backgroundedAt = 0L
    }
}

/** Snapshot of the biometric app lock passed from [MainActivity] into the UI. */
data class AppLockState(
    val isUnlocked: Boolean = true,
    /** The system prompt should open on its own once (cold start or relock). */
    val autoPromptPending: Boolean = false,
    val lastError: String? = null
)

@Composable
fun KakeiAppRoot(
    repository: KakeiRepository,
    initialQuickAdd: Boolean = false,
    initialWidgetAction: String? = null,
    lockState: AppLockState = AppLockState(),
    onUnlockRequest: (title: String, subtitle: String) -> Unit = { _, _ -> },
    onLockDisabled: () -> Unit = {}
) {
    val settings by repository.settingsState.collectAsState()
    val accounts by repository.accounts.collectAsState()
    val categories by repository.categories.collectAsState()
    val labels by repository.labels.collectAsState()
    val rules by repository.rules.collectAsState()
    val transactions by repository.transactions.collectAsState()
    val budgets by repository.budgets.collectAsState()
    val obligations by repository.obligations.collectAsState()
    val forecast by repository.forecast.collectAsState()
    val lendingResponse by repository.lendingResponse.collectAsState()
    val savingsGoals by repository.savingsGoals.collectAsState()
    val groupMembers by repository.groupMembers.collectAsState()
    val pendingMutations by repository.pendingMutations.collectAsState()
    val healthScore by repository.healthScore.collectAsState()
    val streaks by repository.streaks.collectAsState()
    val instantCapturedRecord by repository.instantCapturedRecord.collectAsState()

    val res = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(settings.statusMessage) {
        val msg = settings.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            repository.clearStatusMessage()
        }
    }

    // Saveable: switching the language recreates the activity, which must keep the open screen.
    var currentTab by rememberSaveable {
        mutableIntStateOf(
            when (initialWidgetAction) {
                "BUDGET_RING" -> 1
                "DEBT_BALANCE", "SPLIT_BILL" -> 2
                "GOALS_RING" -> 3
                else -> 0
            }
        )
    }
    var showQuickAddModal by remember { mutableStateOf(initialQuickAdd) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    // Full-screen transactions page opened from Home: (account filter, focus search).
    var transactionsPage by remember { mutableStateOf<Pair<String?, Boolean>?>(null) }
    // Record opened in the editor; a transfer is always edited through its debit leg.
    var editingRecord by remember { mutableStateOf<TransactionDto?>(null) }
    val openRecordEditor: (TransactionDto) -> Unit = { tx ->
        editingRecord = if (tx.isTransferCredit && tx.transferPairId != null) {
            transactions.firstOrNull { it.transferPairId == tx.transferPairId && !it.isTransferCredit } ?: tx
        } else tx
    }

    // Biometric app lock. Re-checked on resume: the user may have removed the device
    // screen lock meanwhile, and a lock that can never be passed must not engage.
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val canUseBiometrics = remember(settings.biometricLockEnabled, isResumed) {
        settings.biometricLockEnabled && BiometricHelper.canAuthenticate(context)
    }
    val hasSession = settings.isAuthenticated || settings.isOfflineAutonomousMode
    val lockActive = hasSession && canUseBiometrics && !lockState.isUnlocked
    val promptTitle = res.getString(R.string.unlock_kakei)
    val promptSubtitle = res.getString(R.string.biometrics_or_device_credential)

    if (!canUseBiometrics && !lockState.isUnlocked) {
        // Lock turned off (or unusable): keep the session unlocked, so turning it on later
        // does not lock the user out mid-session.
        SideEffect { onLockDisabled() }
    }
    LaunchedEffect(lockActive, lockState.autoPromptPending, isResumed) {
        if (lockActive && lockState.autoPromptPending && isResumed) {
            onUnlockRequest(promptTitle, promptSubtitle)
        }
    }

    KakeiTheme(themeMode = settings.themeMode) {
        if (!settings.isAuthenticated && !settings.isOfflineAutonomousMode) {
            AuthScreen(
                settings = settings,
                repository = repository
            )
        } else if (lockActive) {
            AppLockScreen(
                errorMessage = lockState.lastError,
                onUnlock = { onUnlockRequest(promptTitle, promptSubtitle) }
            )
        } else {
            // New record, profile and the transactions page are full-screen pages, not tabs.
            BackHandler(enabled = showProfile && !showQuickAddModal) { showProfile = false }
            BackHandler(enabled = transactionsPage != null && !showProfile && !showQuickAddModal) { transactionsPage = null }
            val fullScreenPage = showQuickAddModal || showProfile || transactionsPage != null || editingRecord != null

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = WindowInsets.statusBars,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!fullScreenPage) {
                        KakeiBottomBar(
                            items = listOf(
                                BottomNavItem(res.getString(R.string.nav_home), Icons.Outlined.Home),
                                BottomNavItem(res.getString(R.string.nav_insights), Icons.Outlined.BarChart),
                                BottomNavItem(res.getString(R.string.nav_lending), Icons.Outlined.People),
                                BottomNavItem(res.getString(R.string.nav_goals), Icons.Outlined.TrackChanges)
                            ),
                            selectedIndex = currentTab,
                            onSelect = { currentTab = it },
                            onAdd = { showQuickAddModal = true },
                            addContentDescription = res.getString(R.string.quick_add_transaction)
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .imePadding()
                ) {
                    val recordToEdit = editingRecord
                    if (recordToEdit != null) {
                        key(recordToEdit.id) {
                            RecordEditorScreen(
                                accounts = accounts,
                                categories = categories,
                                labels = labels,
                                transactions = transactions,
                                langCode = settings.language.code,
                                onCreateLabel = { name, colorHex -> repository.createLabel(name, colorHex) },
                                rateFor = { from, to -> repository.rateBook.value.rate(from, to) },
                                initialRecord = recordToEdit,
                                onCreateRule = { payee, catId ->
                                    repository.createSmartRule(pattern = payee, matchType = "CONTAINS", categoryId = catId)
                                },
                                onDismiss = { editingRecord = null },
                                onSave = { _, type, amt, catId, payee, note, status, _, splits, labelIds, targetAmount, bookingDateIso ->
                                    repository.updateRecord(
                                        record = recordToEdit,
                                        amount = amt,
                                        categoryId = catId,
                                        payee = payee,
                                        note = note,
                                        status = status,
                                        splits = splits,
                                        labelIds = labelIds,
                                        targetAmount = targetAmount,
                                        bookingDateIso = bookingDateIso,
                                        type = type
                                    )
                                    editingRecord = null
                                }
                            )
                        }
                    } else if (showQuickAddModal) {
                        RecordEditorScreen(
                            accounts = accounts,
                            categories = categories,
                            labels = labels,
                            transactions = transactions,
                            langCode = settings.language.code,
                            onCreateLabel = { name, colorHex -> repository.createLabel(name, colorHex) },
                            rateFor = { from, to -> repository.rateBook.value.rate(from, to) },
                            onDismiss = { showQuickAddModal = false },
                            onSave = { accId, type, amt, catId, payee, note, status, targetAccId, splits, labelIds, targetAmount, bookingDateIso ->
                                repository.createRecord(
                                    accountId = accId,
                                    type = type,
                                    amount = amt,
                                    categoryId = catId,
                                    payee = payee,
                                    note = note,
                                    status = status,
                                    transferTargetAccountId = targetAccId,
                                    splits = splits,
                                    labelIds = labelIds,
                                    targetAmount = targetAmount,
                                    bookingDateIso = bookingDateIso ?: Instant.now().toString()
                                )
                                showQuickAddModal = false
                            }
                        )
                    } else if (showProfile) {
                        ProfileAndHubScreen(
                            settings = settings,
                            accounts = accounts,
                            categories = categories,
                            rules = rules,
                            groupMembers = groupMembers,
                            pendingMutations = pendingMutations,
                            repository = repository,
                            onBack = { showProfile = false }
                        )
                    } else if (transactionsPage != null) {
                        val (accountId, focusSearch) = transactionsPage!!
                        TransactionsScreen(
                            baseCurrency = settings.profile.defaultCurrency,
                            langCode = settings.language.code,
                            accounts = accounts,
                            categories = categories,
                            labels = labels,
                            transactions = transactions,
                            repository = repository,
                            initialAccountId = accountId,
                            focusSearch = focusSearch,
                            onEditRecord = openRecordEditor,
                            onBack = { transactionsPage = null }
                        )
                    } else when (currentTab) {
                        0 -> HomeScreen(
                            settings = settings,
                            accounts = accounts,
                            categories = categories,
                            labels = labels,
                            transactions = transactions,
                            instantCapturedRecord = instantCapturedRecord,
                            repository = repository,
                            onOpenProfile = { showProfile = true },
                            onOpenTransactions = { accountId, focusSearch -> transactionsPage = accountId to focusSearch },
                            onEditRecord = openRecordEditor
                        )
                        1 -> InsightsAndBudgetsScreen(
                            baseCurrency = settings.profile.defaultCurrency,
                            langCode = settings.language.code,
                            accounts = accounts,
                            categories = categories,
                            budgets = budgets,
                            obligations = obligations,
                            transactions = transactions,
                            forecast = forecast,
                            healthScore = healthScore,
                            streaks = streaks,
                            repository = repository
                        )
                        2 -> LendingScreen(
                            baseCurrency = settings.profile.defaultCurrency,
                            lendingResponse = lendingResponse,
                            repository = repository,
                            openSplitBillInitially = initialWidgetAction == "SPLIT_BILL"
                        )
                        3 -> SavingsScreen(
                            baseCurrency = settings.profile.defaultCurrency,
                            goals = savingsGoals,
                            repository = repository
                        )
                    }
                }
            }
        }
    }
}