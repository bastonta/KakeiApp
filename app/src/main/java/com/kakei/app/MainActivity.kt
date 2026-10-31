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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.i18n.stringsFor
import com.kakei.app.core.security.BiometricHelper
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.BottomNavItem
import com.kakei.app.ui.components.KakeiBottomBar
import com.kakei.app.ui.screens.*
import com.kakei.app.ui.theme.KakeiTheme

class MainActivity : FragmentActivity() {

    /** True while a BiometricPrompt is on screen (it may pause/stop this activity). */
    private var authInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as KakeiApplication
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
    val heatmaps by repository.heatmaps.collectAsState()
    val insights by repository.insights.collectAsState()
    val instantCapturedRecord by repository.instantCapturedRecord.collectAsState()

    val appStrings = remember(settings.language) { stringsFor(settings.language) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(settings.statusMessage) {
        val msg = settings.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            repository.clearStatusMessage()
        }
    }

    var currentTab by remember {
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
    var showProfile by remember { mutableStateOf(false) }
    // Full-screen transactions page opened from Home: (account filter, focus search).
    var transactionsPage by remember { mutableStateOf<Pair<String?, Boolean>?>(null) }

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
    val promptTitle = appStrings.pick("Вход в Kakei", "Unlock Kakei")
    val promptSubtitle = appStrings.pick("Биометрия или код устройства", "Biometrics or device credential")

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

    CompositionLocalProvider(LocalAppStrings provides appStrings) {
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
                // Profile and the transactions page are full-screen pages opened from Home, not tabs.
                BackHandler(enabled = showProfile) { showProfile = false }
                BackHandler(enabled = transactionsPage != null && !showProfile) { transactionsPage = null }
                val fullScreenPage = showProfile || transactionsPage != null

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
                                    BottomNavItem(appStrings.navHome, Icons.Outlined.Home),
                                    BottomNavItem(appStrings.navInsights, Icons.Outlined.BarChart),
                                    BottomNavItem(appStrings.navLending, Icons.Outlined.People),
                                    BottomNavItem(appStrings.navGoals, Icons.Outlined.TrackChanges)
                                ),
                                selectedIndex = currentTab,
                                onSelect = { currentTab = it },
                                onAdd = { showQuickAddModal = true },
                                addContentDescription = appStrings.quickAddTransaction
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
                        if (showProfile) {
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
                                onBack = { transactionsPage = null }
                            )
                        } else when (currentTab) {
                            0 -> HomeScreen(
                                settings = settings,
                                accounts = accounts,
                                categories = categories,
                                labels = labels,
                                transactions = transactions,
                                budgets = budgets,
                                forecast = forecast,
                                instantCapturedRecord = instantCapturedRecord,
                                repository = repository,
                                onOpenProfile = { showProfile = true },
                                onOpenTransactions = { accountId, focusSearch -> transactionsPage = accountId to focusSearch }
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
                                heatmaps = heatmaps,
                                insights = insights,
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

                    if (showQuickAddModal) {
                        RecordEditorDialog(
                            accounts = accounts,
                            categories = categories,
                            labels = labels,
                            langCode = settings.language.code,
                            rateFor = { from, to -> repository.rateBook.value.rate(from, to) },
                            onDismiss = { showQuickAddModal = false },
                            onSave = { accId, type, amt, catId, payee, note, status, targetAccId, splits, labelIds, targetAmount ->
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
                                    targetAmount = targetAmount
                                )
                                showQuickAddModal = false
                            }
                        )
                    }
                }
            }
        }
    }
}