package com.kakei.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.i18n.stringsFor
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.screens.*
import com.kakei.app.ui.theme.KakeiTheme

class MainActivity : ComponentActivity() {

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
                initialWidgetAction = widgetAction
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_QUICK_ADD = "extra_open_quick_add"
        const val EXTRA_WIDGET_ACTION = "extra_widget_action"
    }
}

@Composable
fun KakeiAppRoot(
    repository: KakeiRepository,
    initialQuickAdd: Boolean = false,
    initialWidgetAction: String? = null
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

    CompositionLocalProvider(LocalAppStrings provides appStrings) {
        KakeiTheme(themeMode = settings.themeMode) {
            if (!settings.isAuthenticated && !settings.isOfflineAutonomousMode) {
                AuthScreen(
                    settings = settings,
                    repository = repository
                )
            } else {
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    contentWindowInsets = WindowInsets.systemBars,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                colors = navItemColors,
                                icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                                label = { Text(appStrings.navHome, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                colors = navItemColors,
                                icon = { Icon(Icons.Default.Insights, contentDescription = null) },
                                label = { Text(appStrings.navInsights, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                colors = navItemColors,
                                icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                                label = { Text(appStrings.navLending, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 3,
                                onClick = { currentTab = 3 },
                                colors = navItemColors,
                                icon = { Icon(Icons.Default.Savings, contentDescription = null) },
                                label = { Text(appStrings.navGoals, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 4,
                                onClick = { currentTab = 4 },
                                colors = navItemColors,
                                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                                label = { Text(appStrings.navProfile, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentTab) {
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
                                onOpenQuickAdd = { showQuickAddModal = true }
                            )
                            1 -> InsightsAndBudgetsScreen(
                                baseCurrency = settings.profile.defaultCurrency,
                                langCode = settings.language.code,
                                accounts = accounts,
                                categories = categories,
                                budgets = budgets,
                                obligations = obligations,
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
                            4 -> ProfileAndHubScreen(
                                settings = settings,
                                accounts = accounts,
                                categories = categories,
                                labels = labels,
                                rules = rules,
                                groupMembers = groupMembers,
                                pendingMutations = pendingMutations,
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