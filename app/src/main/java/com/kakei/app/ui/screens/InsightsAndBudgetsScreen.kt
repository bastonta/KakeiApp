package com.kakei.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.AppStrings
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tab 2: Insights, Envelope Budgets & 30-Day Cash Flow Forecast Screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsAndBudgetsScreen(
    baseCurrency: String,
    langCode: String,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    budgets: List<BudgetEnvelopeDto>,
    obligations: List<PlannedObligationDto>,
    forecast: CashFlowForecastDto,
    healthScore: FinancialHealthScoreDto,
    streaks: NoSpendStreakDto,
    heatmaps: SpendingHeatmapsDto,
    insights: InsightsSummaryDto,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    var subTab by remember { mutableIntStateOf(0) }
    var showCreateBudgetDialog by remember { mutableStateOf(false) }
    var showCreateObligationDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SecondaryTabRow(
            selectedTabIndex = subTab,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                text = { Text(s.tabAnalytics) }
            )
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                text = { Text("${s.tabBudgets} (${budgets.size})") }
            )
            Tab(
                selected = subTab == 2,
                onClick = { subTab = 2 },
                text = { Text(s.tabPlanning) }
            )
        }

        when (subTab) {
            0 -> AnalyticsInsightsSubScreen(
                healthScore = healthScore,
                streaks = streaks,
                heatmaps = heatmaps,
                insights = insights
            )
            1 -> BudgetsSubScreen(
                budgets = budgets,
                categories = categories,
                langCode = langCode,
                onOpenCreate = { showCreateBudgetDialog = true },
                onDelete = { repository.deleteBudget(it) }
            )
            2 -> PlanningForecastSubScreen(
                forecast = forecast,
                obligations = obligations,
                onOpenCreate = { showCreateObligationDialog = true },
                onExecute = { repository.executeObligationNow(it) },
                onDelete = { repository.deleteObligation(it) }
            )
        }
    }

    if (showCreateBudgetDialog) {
        var bName by remember { mutableStateOf("") }
        var bLimit by remember { mutableStateOf("100000") }
        var bPeriod by remember { mutableStateOf("MONTHLY") }
        var selectedCats by remember { mutableStateOf(setOf<String>()) }

        AlertDialog(
            onDismissRequest = { showCreateBudgetDialog = false },
            title = { Text(s.createBudgetButton) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bName,
                        onValueChange = { bName = it },
                        label = { Text(s.pick("Название конверта", "Envelope Name")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = bLimit,
                        onValueChange = { bLimit = it },
                        label = { Text(s.pick("Лимит ($baseCurrency)", "Budget Limit ($baseCurrency)")) },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("WEEKLY", "MONTHLY", "YEARLY").forEach { p ->
                            FilterChip(
                                selected = bPeriod == p,
                                onClick = { bPeriod = p },
                                label = { Text(s.period(p)) }
                            )
                        }
                    }
                    Text(s.categoryLabel, style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            val active = selectedCats.contains(cat.id)
                            FilterChip(
                                selected = active,
                                onClick = {
                                    selectedCats = if (active) selectedCats - cat.id else selectedCats + cat.id
                                },
                                label = { Text(cat.localizedName(langCode)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lim = bLimit.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (bName.isNotBlank() && lim > BigDecimal.ZERO) {
                            repository.createBudget(
                                name = bName,
                                limitAmount = lim,
                                currency = baseCurrency,
                                periodType = bPeriod,
                                categoryIds = selectedCats.toList()
                            )
                            showCreateBudgetDialog = false
                        }
                    }
                ) {
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateBudgetDialog = false }) {
                    Text(s.cancelButton)
                }
            }
        )
    }

    if (showCreateObligationDialog) {
        var title by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("25000") }
        var freq by remember { mutableStateOf("MONTHLY") }
        var opType by remember { mutableStateOf("EXPENSE") }
        var dueDays by remember { mutableStateOf("7") }
        val defaultAccId = accounts.firstOrNull()?.id.orEmpty()

        AlertDialog(
            onDismissRequest = { showCreateObligationDialog = false },
            title = { Text(s.createObligationButton) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(s.pick("Название / получатель", "Title / Payee")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("${s.amountLabel} ($baseCurrency)") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EXPENSE", "INCOME").forEach { t ->
                            FilterChip(
                                selected = opType == t,
                                onClick = { opType = t },
                                label = { Text(s.operationType(t)) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("WEEKLY", "MONTHLY", "YEARLY", "ONE_OFF").forEach { f ->
                            FilterChip(
                                selected = freq == f,
                                onClick = { freq = f },
                                label = { Text(s.period(f)) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = dueDays,
                        onValueChange = { dueDays = it },
                        label = { Text(s.pick("Через сколько дней", "Due in days from today")) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val daysOffset = dueDays.toLongOrNull() ?: 7L
                        if (title.isNotBlank() && amt > BigDecimal.ZERO && defaultAccId.isNotBlank()) {
                            repository.createObligation(
                                title = title,
                                accountId = defaultAccId,
                                categoryId = categories.firstOrNull()?.id,
                                type = opType,
                                amount = amt,
                                currency = baseCurrency,
                                frequency = freq,
                                nextDueDate = LocalDate.now().plusDays(daysOffset).toString(),
                                payee = title
                            )
                            showCreateObligationDialog = false
                        }
                    }
                ) {
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateObligationDialog = false }) {
                    Text(s.cancelButton)
                }
            }
        )
    }
}

@Composable
private fun AnalyticsInsightsSubScreen(
    healthScore: FinancialHealthScoreDto,
    streaks: NoSpendStreakDto,
    heatmaps: SpendingHeatmapsDto,
    insights: InsightsSummaryDto
) {
    val s = LocalAppStrings.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Financial Health Score Card (0..100 + Grade + 3 Subscores)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = EmeraldBrand)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        Text(s.healthScoreTitle, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = s.pick("Сбережения", "Savings") + ": ${healthScore.savingsRatePercentage}% · " +
                                s.pick("Бюджеты", "Budgets") + ": ${healthScore.budgetCompliancePercentage}% · " +
                                s.pick("Долг/доход", "DTI") + ": ${healthScore.debtToIncomePercentage}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = EmeraldBrand.copy(alpha = 0.20f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "${healthScore.overallScore}/100 (${healthScore.grade})",
                            style = MaterialTheme.typography.titleLarge,
                            color = emeraldText(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { (healthScore.overallScore / 100f).coerceIn(0f, 1f) },
                    color = EmeraldBrand,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                healthScore.recommendations.forEach { rec ->
                    Text(
                        text = "• $rec",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. No-Spend Streak Card
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = IndigoAccent)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(s.noSpendStreakTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = s.pick("Дней без трат за 30 дн.", "Last 30d zero-spend days") + ": ${streaks.totalNoSpendDaysLast30}/30",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.days(streaks.currentStreakDays), style = MaterialTheme.typography.headlineMedium, color = emeraldText())
                        Text(s.currentStreakLabel, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.days(streaks.longestStreakDays), style = MaterialTheme.typography.headlineMedium, color = IndigoAccent)
                        Text(s.longestStreakLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 3. GitHub-Style Spending Heatmap (Monthly Grid + 12-Month Matrix)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = EmeraldBrand)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "${s.spendingHeatmapTitle} (${heatmaps.year}-${heatmaps.month.toString().padStart(2, '0')})",
                    style = MaterialTheme.typography.titleLarge
                )

                // Calendar cells in rows of 7
                val isDark = LocalIsDarkTheme.current
                val chunks = heatmaps.calendarDays.chunked(7)
                chunks.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        week.forEach { cell ->
                            val cellColor = when (cell.intensityLevel) {
                                1 -> HeatmapLevel1Dark
                                2 -> HeatmapLevel2Dark
                                3 -> HeatmapLevel3Dark
                                4 -> HeatmapLevel4Dark
                                else -> if (isDark) HeatmapLevel0Dark else MaterialTheme.colorScheme.surfaceVariant
                            }
                            val cellTextColor = if (cell.intensityLevel > 0 || isDark) Color.White else MaterialTheme.colorScheme.onSurface
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(cellColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cell.date.takeLast(2),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cellTextColor
                                )
                            }
                        }
                        repeat(7 - week.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 12-Month Matrix Summary
                Text(s.pick("Расходы по месяцам за год", "365-Day Monthly Matrix"), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    heatmaps.monthlySummaries.filter { it.totalSpent > BigDecimal.ZERO }.forEach { m ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(monthLabel(m.month, s.language), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = CurrencyFormatter.format(m.totalSpent, heatmaps.currency),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Month-over-Month, Month-End Projection, Small Spends, Weekday vs Weekend
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = CyanTransfer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(s.monthComparisonTitle, style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(s.pick("Текущий месяц", "Current Month"), style = MaterialTheme.typography.labelSmall)
                        Text(
                            CurrencyFormatter.format(insights.currentMonthSpent, insights.currency),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(s.monthEndProjectionTitle, style = MaterialTheme.typography.labelSmall)
                        Text(
                            CurrencyFormatter.format(insights.monthEndProjectedSpend, insights.currency),
                            style = MaterialTheme.typography.titleLarge,
                            color = AmberWarning
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${s.smallSpendsTitle}: ${insights.smallSpendsCount} ${s.pick("опер.", "tx")} (${CurrencyFormatter.format(insights.smallSpendsTotal, insights.currency)})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "${s.weekdayVsWeekendTitle}: ${s.pick("будни", "weekday")} ${CurrencyFormatter.format(insights.weekdaySpendTotal, insights.currency)} · ${s.pick("выходные", "weekend")} ${CurrencyFormatter.format(insights.weekendSpendTotal, insights.currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 5. Top Merchants Leaderboard
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = PurpleGoal)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(s.topPayeesTitle, style = MaterialTheme.typography.titleLarge)
                insights.topPayees.forEachIndexed { idx, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${idx + 1}. ${payeeLabel(entry.payee, s)} (${entry.transactionCount}×)")
                        Text(
                            CurrencyFormatter.format(entry.totalSpent, insights.currency),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetsSubScreen(
    budgets: List<BudgetEnvelopeDto>,
    categories: List<CategoryDto>,
    langCode: String,
    onOpenCreate: () -> Unit,
    onDelete: (String) -> Unit
) {
    val s = LocalAppStrings.current
    val catMap = remember(categories) { categories.associateBy { it.id } }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(
                onClick = onOpenCreate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(s.createBudgetButton)
            }
        }

        if (budgets.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.AccountBalanceWallet,
                    title = s.pick("Бюджетов пока нет", "No budgets yet"),
                    hint = s.pick(
                        "Задайте лимит на категории — приложение покажет остаток и безопасную сумму на день.",
                        "Set a limit for some categories to track what's left and a safe daily amount."
                    )
                )
            }
        }

        items(budgets, key = { it.id }) { b ->
            val barColor = when (b.alertLevel) {
                "EXCEEDED_100" -> CoralExpense
                "WARNING_90", "WARNING_75" -> AmberWarning
                else -> EmeraldBrand
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = barColor)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(b.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${s.period(b.periodType)} · ${s.budgetAlert(b.alertLevel)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor
                        )
                    }
                    IconButton(onClick = { onDelete(b.id) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = s.deleteButton)
                    }
                }

                LinearProgressIndicator(
                    progress = { (b.usagePercentage.toFloat() / 100f).coerceIn(0f, 1f) },
                    color = barColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${CurrencyFormatter.format(b.spentAmount, b.currency)} / ${CurrencyFormatter.format(b.limitAmount, b.currency)} (${b.usagePercentage}%)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${s.remainingLabel}: ${CurrencyFormatter.format(b.remainingAmount, b.currency)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "${s.dailyBurnLimitLabel}: ${CurrencyFormatter.format(b.dailyBurnRateLimit, b.currency)} ${s.pick("/день", "/ day")} (${s.pick("осталось", "left")} ${s.days(b.remainingDaysInPeriod)})",
                    style = MaterialTheme.typography.labelLarge,
                    color = EmeraldLight
                )

                if (b.categoryIds.isNotEmpty()) {
                    Text(
                        text = b.categoryIds.mapNotNull { catMap[it]?.localizedName(langCode) }.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanningForecastSubScreen(
    forecast: CashFlowForecastDto,
    obligations: List<PlannedObligationDto>,
    onOpenCreate: () -> Unit,
    onExecute: (PlannedObligationDto) -> Unit,
    onDelete: (String) -> Unit
) {
    val s = LocalAppStrings.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 30-Day Cash Flow Runway Forecast Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = if (forecast.liquidityDeficitAlert) CoralExpense else IndigoAccent)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(s.cashFlowForecastTitle, style = MaterialTheme.typography.titleLarge)
                if (forecast.liquidityDeficitAlert) {
                    Text(
                        text = s.liquidityAlertText,
                        color = CoralExpense,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(s.pick("Сейчас доступно", "Current Liquid"), style = MaterialTheme.typography.labelSmall)
                        Text(
                            CurrencyFormatter.format(forecast.currentLiquidBalance, forecast.baseCurrency),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(s.pick("Через 30 дней", "Projected in 30d"), style = MaterialTheme.typography.labelSmall)
                        Text(
                            CurrencyFormatter.format(forecast.forecastedEndBalance, forecast.baseCurrency),
                            style = MaterialTheme.typography.titleLarge,
                            color = EmeraldLight
                        )
                    }
                }
                Text(
                    text = "${s.pick("Запланировано", "Planned")} +${CurrencyFormatter.format(forecast.plannedIncomeTotal, forecast.baseCurrency)} / -${CurrencyFormatter.format(forecast.plannedExpenseTotal, forecast.baseCurrency)} (${s.pick("доля обязательств", "obligation ratio")}: ${forecast.obligationToLiquidityRatio}%)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Button(
                onClick = onOpenCreate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(s.createObligationButton)
            }
        }

        if (obligations.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.EventRepeat,
                    title = s.pick("Регулярных платежей нет", "No planned payments"),
                    hint = s.pick(
                        "Добавьте аренду, подписки или зарплату, чтобы прогноз учитывал их.",
                        "Add rent, subscriptions or salary so the forecast accounts for them."
                    )
                )
            }
        }

        items(obligations, key = { it.id }) { ob ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = IndigoAccent)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ob.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${s.period(ob.frequency)} · ${s.pick("Срок", "Due")}: ${ob.nextDueDate}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = CurrencyFormatter.format(ob.amount, ob.currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (ob.type == "INCOME") EmeraldLight else CoralExpense
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onExecute(ob) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(s.executeNowButton)
                    }
                    IconButton(onClick = { onDelete(ob.id) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = s.deleteButton)
                    }
                }
            }
        }
    }
}

/** Short month name for the yearly matrix, e.g. "окт." / "Oct". */
private fun monthLabel(month: Int, language: AppLanguage): String {
    val locale = if (language == AppLanguage.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH
    return Month.of(month.coerceIn(1, 12)).getDisplayName(TextStyle.SHORT_STANDALONE, locale)
}

/** The server reports records without a payee as "Unknown Merchant"; show that in the UI language. */
private fun payeeLabel(payee: String, s: AppStrings): String =
    if (payee.isBlank() || payee == "Unknown Merchant") s.pick("Без получателя", "Unknown Merchant") else payee