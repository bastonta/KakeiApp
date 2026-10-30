package com.kakei.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.AppStrings
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Category slices shown in the donut before the rest are grouped into "Other". */
private const val DONUT_TOP_CATEGORIES = 4

/**
 * Tab 2: Analytics — Overview (health score, 30-day cash flow forecast, category split,
 * spending heatmap, insights), Envelope Budgets and the Planned Obligations list.
 */
@Composable
fun InsightsAndBudgetsScreen(
    baseCurrency: String,
    langCode: String,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    budgets: List<BudgetEnvelopeDto>,
    obligations: List<PlannedObligationDto>,
    transactions: List<TransactionDto>,
    forecast: CashFlowForecastDto,
    healthScore: FinancialHealthScoreDto,
    streaks: NoSpendStreakDto,
    heatmaps: SpendingHeatmapsDto,
    insights: InsightsSummaryDto,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var showCreateBudgetDialog by remember { mutableStateOf(false) }
    var showCreateObligationDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ScreenHeader(title = s.navInsights)
            Spacer(modifier = Modifier.height(10.dp))
            KakeiSegmented(
                options = listOf(s.tabAnalytics, s.tabBudgets, s.tabPlanning),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        when (subTab) {
            0 -> AnalyticsInsightsSubScreen(
                baseCurrency = baseCurrency,
                langCode = langCode,
                categories = categories,
                transactions = transactions,
                forecast = forecast,
                healthScore = healthScore,
                streaks = streaks,
                heatmaps = heatmaps,
                insights = insights,
                repository = repository
            )
            1 -> BudgetsSubScreen(
                baseCurrency = baseCurrency,
                budgets = budgets,
                categories = categories,
                onOpenCreate = { showCreateBudgetDialog = true },
                onDelete = { repository.deleteBudget(it) }
            )
            2 -> PlanningSubScreen(
                forecast = forecast,
                obligations = obligations,
                categories = categories,
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            KakeiChip(text = s.period(p), selected = bPeriod == p, onClick = { bPeriod = p })
                        }
                    }
                    Text(s.categoryLabel, style = MaterialTheme.typography.labelLarge, color = c.muted)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            val active = selectedCats.contains(cat.id)
                            val visual = categoryVisual(cat)
                            KakeiChip(
                                text = cat.localizedName(langCode),
                                icon = visual.icon,
                                iconTint = visual.color,
                                color = visual.color,
                                selected = active,
                                onClick = {
                                    selectedCats = if (active) selectedCats - cat.id else selectedCats + cat.id
                                }
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            KakeiChip(
                                text = s.operationType(t),
                                selected = opType == t,
                                color = if (t == "INCOME") c.mint else c.coral,
                                onClick = { opType = t }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("WEEKLY", "MONTHLY", "YEARLY", "ONE_OFF").forEach { f ->
                            KakeiChip(text = s.period(f), selected = freq == f, onClick = { freq = f })
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

// ============================================================================
// Overview
// ============================================================================

@Composable
private fun AnalyticsInsightsSubScreen(
    baseCurrency: String,
    langCode: String,
    categories: List<CategoryDto>,
    transactions: List<TransactionDto>,
    forecast: CashFlowForecastDto,
    healthScore: FinancialHealthScoreDto,
    streaks: NoSpendStreakDto,
    heatmaps: SpendingHeatmapsDto,
    insights: InsightsSummaryDto,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val rateBook by repository.rateBook.collectAsState()
    val spendCurrency = insights.currency.ifBlank { baseCurrency }

    // Current-month expenses per category in the insights currency; split records are
    // attributed to their split categories. Records without a known rate are left out.
    val categorySpend = remember(transactions, rateBook, spendCurrency) {
        val month = YearMonth.now()
        val totals = linkedMapOf<String?, BigDecimal>()
        transactions.forEach { tx ->
            if (tx.type != "EXPENSE") return@forEach
            val date = bookingDateTime(tx.bookingDate)?.toLocalDate() ?: return@forEach
            if (YearMonth.from(date) != month) return@forEach
            val parts = if (tx.splits.isNotEmpty()) tx.splits.map { it.categoryId to it.amount } else listOf(tx.categoryId to tx.amount)
            parts.forEach { (catId, amt) ->
                val converted = CurrencyFormatter.convert(amt.abs(), tx.currency, spendCurrency, rateBook) ?: return@forEach
                totals[catId] = (totals[catId] ?: BigDecimal.ZERO) + converted
            }
        }
        totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HealthScoreCard(healthScore) }
        item { ForecastCard(forecast) }
        item {
            CategoryDonutCard(
                spend = categorySpend,
                categories = categories,
                langCode = langCode,
                currency = spendCurrency,
                monthOverMonth = insights.monthOverMonthDeltaPercentage.takeIf { insights.previousMonthSpent.signum() > 0 }
            )
        }
        item { SpendingHeatmapCard(heatmaps, streaks) }

        // Month projection, small spends, weekday vs weekend, 12-month matrix
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .kakeiCard(cornerRadius = 24.dp)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(s.monthComparisonTitle, style = MaterialTheme.typography.titleLarge, color = c.text)
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatColumn(
                        caption = s.pick("Текущий месяц", "Current month"),
                        value = formatMoney(insights.currentMonthSpent, insights.currency),
                        valueColor = c.text,
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        caption = s.monthEndProjectionTitle,
                        value = formatMoney(insights.monthEndProjectedSpend, insights.currency),
                        valueColor = c.amber,
                        modifier = Modifier.weight(1f)
                    )
                }
                RowDivider()
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatColumn(
                        caption = "${s.smallSpendsTitle} · ${insights.smallSpendsCount}",
                        value = formatMoney(insights.smallSpendsTotal, insights.currency),
                        valueColor = c.text,
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        caption = s.pick("Будни / выходные", "Weekday / weekend"),
                        value = "${CurrencyFormatter.format(insights.weekdaySpendTotal, insights.currency)} / " +
                            CurrencyFormatter.format(insights.weekendSpendTotal, insights.currency),
                        valueColor = c.text,
                        modifier = Modifier.weight(1f)
                    )
                }
                val months = heatmaps.monthlySummaries.filter { it.totalSpent > BigDecimal.ZERO }
                if (months.isNotEmpty()) {
                    RowDivider()
                    Text(s.pick("Расходы по месяцам за год", "Spending by month"), style = MaterialTheme.typography.labelLarge, color = c.muted)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        months.forEach { m ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(c.surf2)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(monthLabel(m.month, s.language), style = MaterialTheme.typography.labelSmall, color = c.muted)
                                Text(
                                    text = CurrencyFormatter.format(m.totalSpent, heatmaps.currency),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = c.text
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top Merchants Leaderboard
        if (insights.topPayees.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kakeiCard(cornerRadius = 24.dp)
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        s.topPayeesTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = c.text,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                    insights.topPayees.forEachIndexed { idx, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${idx + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = c.faint,
                                modifier = Modifier.width(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(payeeLabel(entry.payee, s), style = MaterialTheme.typography.titleMedium, color = c.text)
                                Text(
                                    s.pick("${entry.transactionCount} опер.", "${entry.transactionCount} records"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.muted
                                )
                            }
                            Text(
                                formatMoney(entry.totalSpent, insights.currency),
                                style = MaterialTheme.typography.titleMedium,
                                color = c.text
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(caption: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    val c = KakeiTheme.colors
    Column(modifier = modifier) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Financial Health Score card: 0..100 ring, grade and the three sub-scores as bars. */
@Composable
private fun HealthScoreCard(healthScore: FinancialHealthScoreDto) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val scoreColor = when {
        healthScore.overallScore >= 70 -> c.mint
        healthScore.overallScore >= 40 -> c.amber
        else -> c.coral
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KakeiRing(
                progress = healthScore.overallScore / 100f,
                color = scoreColor,
                size = 100.dp,
                strokeWidth = 9.dp
            ) {
                Text(
                    "${healthScore.overallScore}",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text
                )
            }
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.healthScoreTitle, style = MaterialTheme.typography.titleLarge, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(healthScore.grade, style = MaterialTheme.typography.titleSmall, color = scoreColor)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(gradeLabel(healthScore.overallScore, s), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
                ScoreBar(s.pick("Сбережения", "Savings"), healthScore.savingsRatePercentage, c.mint)
                ScoreBar(s.pick("Бюджеты", "Budgets"), healthScore.budgetCompliancePercentage, c.cyan)
                ScoreBar(s.pick("Долг / доход", "Debt / income"), healthScore.debtToIncomePercentage, c.amber)
            }
        }
        if (healthScore.recommendations.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.mint.copy(alpha = 0.10f))
                    .padding(12.dp)
            ) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = c.mint, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    healthScore.recommendations.forEach { rec ->
                        Text(rec, style = MaterialTheme.typography.bodyMedium, color = c.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreBar(label: String, percentage: BigDecimal, color: Color) {
    val c = KakeiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
            Text(
                "${percentage.setScale(0, RoundingMode.HALF_UP).toPlainString()}%",
                style = MaterialTheme.typography.labelMedium,
                color = c.text
            )
        }
        KakeiProgressBar(progress = percentage.toFloat() / 100f, color = color, height = 4.dp)
    }
}

private fun gradeLabel(score: Int, s: AppStrings): String = when {
    score >= 85 -> s.pick("Отлично", "Excellent")
    score >= 70 -> s.pick("Хорошо", "Good")
    score >= 50 -> s.pick("Средне", "Fair")
    else -> s.pick("Нужно внимание", "Needs attention")
}

/** 30-day projected balance chart with planned income / payments and the obligation load. */
@Composable
private fun ForecastCard(forecast: CashFlowForecastDto) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val currency = forecast.baseCurrency
    val change = if (forecast.currentLiquidBalance.signum() > 0) {
        (forecast.forecastedEndBalance - forecast.currentLiquidBalance)
            .multiply(BigDecimal(100))
            .divide(forecast.currentLiquidBalance, 1, RoundingMode.HALF_UP)
    } else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.pick("Прогноз на ${forecast.horizonDays} дней", "${forecast.horizonDays}-day forecast"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted
                )
                Text(
                    formatMoney(forecast.forecastedEndBalance, currency),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (forecast.liquidityDeficitAlert) c.coral else c.text
                )
            }
            if (change != null && change.signum() != 0) {
                val positive = change.signum() > 0
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background((if (positive) c.mint else c.coral).copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (positive) Icons.Outlined.NorthEast else Icons.Outlined.SouthEast,
                        contentDescription = null,
                        tint = if (positive) c.mint else c.coral,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        (if (positive) "+" else "−") + change.abs().stripTrailingZeros().toPlainString().replace('.', ',') + "%",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (positive) c.mint else c.coral
                    )
                }
            }
        }

        if (forecast.liquidityDeficitAlert) {
            Text(s.liquidityAlertText, style = MaterialTheme.typography.bodyMedium, color = c.coral)
        }

        if (forecast.dailyTimeline.size >= 2) {
            ForecastChart(forecast)
        }

        RowDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn(
                caption = s.pick("Доходы", "Income"),
                value = "+" + CurrencyFormatter.format(forecast.plannedIncomeTotal, currency),
                valueColor = c.mint,
                modifier = Modifier.weight(1f)
            )
            StatColumn(
                caption = s.pick("Платежи", "Payments"),
                value = "−" + CurrencyFormatter.format(forecast.plannedExpenseTotal, currency),
                valueColor = c.coral,
                modifier = Modifier.weight(1f)
            )
            StatColumn(
                caption = s.pick("Нагрузка", "Load"),
                value = "${forecast.obligationToLiquidityRatio.setScale(0, RoundingMode.HALF_UP).toPlainString()}%",
                valueColor = c.text,
                modifier = Modifier.weight(0.7f)
            )
        }
    }
}

@Composable
private fun ForecastChart(forecast: CashFlowForecastDto) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val points = forecast.dailyTimeline
    val values = points.map { it.projectedBalance }
    val min = values.minOrNull() ?: BigDecimal.ZERO
    val max = values.maxOrNull() ?: BigDecimal.ZERO
    val range = max - min
    // Normalized 0..1 heights (UI geometry only; amounts stay BigDecimal).
    val ratios = values.map { v ->
        if (range.signum() == 0) 0.5f else (v - min).divide(range, 6, RoundingMode.HALF_UP).toFloat()
    }
    // Highlight the day with the largest planned income (usually payday).
    val peakIndex = points.indices.maxByOrNull { points[it].plannedIncome }
        ?.takeIf { points[it].plannedIncome.signum() > 0 }
    val peakTitle = peakIndex?.let { idx ->
        forecast.projections.firstOrNull { it.type == "INCOME" && it.projectedDate.take(10) == points[idx].date.take(10) }?.title
    }
    val chartHeight = 130.dp
    val density = LocalDensity.current
    val dateFormat = DateTimeFormatter.ofPattern(if (s.language == AppLanguage.RU) "d MMM" else "MMM d", localeFor(s))
    fun labelFor(iso: String): String =
        runCatching { dateFormat.format(LocalDate.parse(iso.take(10))).replace(".", "") }.getOrDefault(iso.take(10))

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { chartHeight.toPx() }
        val padTop = heightPx * 0.12f
        val padBottom = heightPx * 0.08f
        fun xAt(i: Int) = widthPx * i / (points.size - 1)
        fun yAt(i: Int) = padTop + (heightPx - padTop - padBottom) * (1f - ratios[i])

        Canvas(modifier = Modifier.fillMaxSize()) {
            val grid = c.line
            listOf(0.33f, 0.66f).forEach { f ->
                drawLine(
                    color = grid,
                    start = Offset(0f, size.height * f),
                    end = Offset(size.width, size.height * f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                )
            }
            val line = Path()
            points.indices.forEach { i ->
                if (i == 0) line.moveTo(xAt(i), yAt(i)) else line.lineTo(xAt(i), yAt(i))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(c.mint.copy(alpha = 0.28f), c.mint.copy(alpha = 0f))))
            drawPath(line, c.mint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (peakIndex != null) {
                drawCircle(c.bg, radius = 5.dp.toPx(), center = Offset(xAt(peakIndex), yAt(peakIndex)))
                drawCircle(c.mint, radius = 5.dp.toPx(), center = Offset(xAt(peakIndex), yAt(peakIndex)), style = Stroke(2.dp.toPx()))
            }
        }

        if (peakIndex != null) {
            val tooltipWidth = 150.dp
            val xDp = with(density) { xAt(peakIndex).toDp() }
            val yDp = with(density) { yAt(peakIndex).toDp() }
            val left = (xDp + 8.dp).coerceAtMost(maxWidth - tooltipWidth).coerceAtLeast(0.dp)
            Column(
                modifier = Modifier
                    .offset(x = left, y = (yDp + 10.dp).coerceAtMost(chartHeight - 44.dp))
                    .width(tooltipWidth)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surf2)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    listOfNotNull(labelFor(points[peakIndex].date), peakTitle).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "+" + CurrencyFormatter.format(points[peakIndex].plannedIncome, forecast.baseCurrency),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.mint
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val last = points.size - 1
        listOf(0, last / 3, last * 2 / 3, last).distinct().forEach { i ->
            Text(labelFor(points[i].date), style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

/** Donut of this month's spending by category with a legend; the center shows the total and the MoM change. */
@Composable
private fun CategoryDonutCard(
    spend: List<Pair<String?, BigDecimal>>,
    categories: List<CategoryDto>,
    langCode: String,
    currency: String,
    monthOverMonth: BigDecimal?
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val total = spend.fold(BigDecimal.ZERO) { acc, p -> acc + p.second }

    data class Slice(val label: String, val amount: BigDecimal, val color: Color)

    val slices = buildList {
        spend.take(DONUT_TOP_CATEGORIES).forEach { (catId, amt) ->
            val cat = catId?.let { catMap[it] }
            add(
                Slice(
                    label = cat?.localizedName(langCode) ?: s.pick("Без категории", "Uncategorized"),
                    amount = amt,
                    color = cat?.let { categoryVisual(it).color } ?: c.faint
                )
            )
        }
        val rest = spend.drop(DONUT_TOP_CATEGORIES).fold(BigDecimal.ZERO) { acc, p -> acc + p.second }
        if (rest.signum() > 0) add(Slice(s.pick("Прочее", "Other"), rest, c.faint))
    }
    fun share(amount: BigDecimal): BigDecimal =
        if (total.signum() == 0) BigDecimal.ZERO else amount.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.pick("Расходы по категориям", "Spending by category"),
                style = MaterialTheme.typography.titleLarge,
                color = c.text,
                modifier = Modifier.weight(1f)
            )
            Text(monthName(LocalDate.now().monthValue, s), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        if (slices.isEmpty()) {
            Text(
                s.pick("В этом месяце расходов пока нет.", "No spending this month yet."),
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 22.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val gap = if (slices.size > 1) 2.5f else 0f
                    var start = -90f
                    slices.forEach { slice ->
                        val sweep = if (total.signum() == 0) 0f else
                            slice.amount.divide(total, 6, RoundingMode.HALF_UP).toFloat() * 360f
                        drawArc(
                            color = slice.color,
                            startAngle = start + gap / 2f,
                            sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(stroke)
                        )
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatCompactMoney(total, currency, s),
                        style = MaterialTheme.typography.titleMedium,
                        color = c.text,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 26.dp),
                        textAlign = TextAlign.Center
                    )
                    if (monthOverMonth != null && monthOverMonth.signum() != 0) {
                        val pct = monthOverMonth.setScale(0, RoundingMode.HALF_UP)
                        val prev = monthName(LocalDate.now().minusMonths(1).monthValue, s).take(4).lowercase(localeFor(s))
                        Text(
                            (if (pct.signum() > 0) "+" else "−") + pct.abs().toPlainString() + "% " + s.pick("к $prev.", "vs $prev"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pct.signum() > 0) c.coral else c.mint
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                slices.forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(slice.color)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            slice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${share(slice.amount)}%", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    }
                }
            }
        }
    }
}

/** Month calendar of daily spending intensity (0..4) with the current no-spend streak. */
@Composable
private fun SpendingHeatmapCard(heatmaps: SpendingHeatmapsDto, streaks: NoSpendStreakDto) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val cells = heatmaps.calendarDays
    val firstDate = cells.firstOrNull()?.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    val monthValue = heatmaps.month ?: firstDate?.monthValue ?: LocalDate.now().monthValue
    // Monday-first grid: pad the first week up to the weekday of the 1st.
    val leading = firstDate?.dayOfWeek?.let { it.value - DayOfWeek.MONDAY.value } ?: 0
    val today = LocalDate.now().toString()
    fun levelColor(level: Int): Color = when (level) {
        1 -> c.mint.copy(alpha = 0.22f)
        2 -> c.mint.copy(alpha = 0.42f)
        3 -> c.mint.copy(alpha = 0.68f)
        4 -> c.mint
        else -> c.surf2
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.pick("Траты по дням", "Daily spending"),
                style = MaterialTheme.typography.titleLarge,
                color = c.text,
                modifier = Modifier.weight(1f)
            )
            Text(monthName(monthValue, s), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.SHORT_STANDALONE, localeFor(s)).replaceFirstChar { it.titlecase(localeFor(s)) },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        val slots: List<HeatmapDayCellDto?> = List(leading) { null } + cells
        slots.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { cell ->
                    if (cell == null) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        val isToday = cell.date.take(10) == today
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.15f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(levelColor(cell.intensityLevel))
                                .then(
                                    if (isToday) {
                                        Modifier.border(1.5.dp, c.text.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                cell.date.takeLast(2).trimStart('0'),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    cell.intensityLevel >= 3 -> c.mintInk
                                    cell.intensityLevel > 0 -> c.text
                                    else -> c.faint
                                }
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            val n = streaks.currentStreakDays
            Text(
                s.pick(
                    "$n ${ruPlural(n, "день", "дня", "дней")} без трат",
                    "$n ${if (n == 1L) "day" else "days"} without spending"
                ),
                style = MaterialTheme.typography.titleSmall,
                color = c.text
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(s.pick("меньше", "less"), style = MaterialTheme.typography.labelSmall, color = c.faint)
            Spacer(modifier = Modifier.width(4.dp))
            (0..4).forEach { level ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 1.5.dp)
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(levelColor(level))
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(s.pick("больше", "more"), style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

// ============================================================================
// Budgets
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BudgetsSubScreen(
    baseCurrency: String,
    budgets: List<BudgetEnvelopeDto>,
    categories: List<CategoryDto>,
    onOpenCreate: () -> Unit,
    onDelete: (String) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val catMap = remember(categories) { categories.associateBy { it.id } }
    var menuBudgetId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Month summary across all budgets in the base currency
        val summed = budgets.filter { it.currency.equals(baseCurrency, ignoreCase = true) }
        if (summed.isNotEmpty()) {
            item {
                BudgetSummaryCard(summed, baseCurrency, catMap)
            }
        }

        if (budgets.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = s.pick("Бюджетов пока нет", "No budgets yet"),
                    hint = s.pick(
                        "Задайте лимит на категории — приложение покажет остаток и безопасную сумму на день.",
                        "Set a limit for some categories to track what's left and a safe daily amount."
                    )
                )
            }
        }

        items(budgets, key = { it.id }) { b ->
            val exceeded = b.alertLevel == "EXCEEDED_100" || b.alertLevel == "CRITICAL_100"
            val warning = b.alertLevel == "WARNING_75" || b.alertLevel == "WARNING_90"
            val barColor = when {
                exceeded -> c.coral
                warning -> c.amber
                else -> c.mint
            }
            val cat = b.categoryIds.firstNotNullOfOrNull { catMap[it] }
            val visual = cat?.let { categoryVisual(it) } ?: CategoryVisual(Icons.Outlined.AccountBalanceWallet, c.mint)

            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kakeiCard(cornerRadius = 24.dp)
                        .combinedClickable(onClick = { menuBudgetId = b.id }, onLongClick = { menuBudgetId = b.id })
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(icon = visual.icon, color = visual.color, size = 48.dp, iconSize = 24.dp, cornerRadius = 14.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                b.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${s.period(b.periodType)} · ${s.pick("осталось", "left")} ${s.days(b.remainingDaysInPeriod)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.muted
                            )
                        }
                        KakeiBadge(
                            text = if (exceeded) s.pick("Превышен", "Over") else "${b.usagePercentage.setScale(0, RoundingMode.HALF_UP).toPlainString()}%",
                            color = barColor,
                            large = true
                        )
                    }
                    KakeiProgressBar(progress = b.usagePercentage.toFloat() / 100f, color = barColor, height = 8.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatMoney(b.spentAmount, b.currency)} ${s.pick("из", "of")} ${formatMoney(b.limitAmount, b.currency)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (exceeded) {
                                "+" + CurrencyFormatter.format((b.spentAmount - b.limitAmount).max(BigDecimal.ZERO), b.currency)
                            } else {
                                "${CurrencyFormatter.format(b.dailyBurnRateLimit, b.currency)}${s.pick("/день", "/day")}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = when {
                                exceeded -> c.coral
                                warning -> c.amber
                                else -> c.muted
                            }
                        )
                    }
                }
                DropdownMenu(expanded = menuBudgetId == b.id, onDismissRequest = { menuBudgetId = null }) {
                    if (b.categoryIds.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    b.categoryIds.mapNotNull { catMap[it]?.name }.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.muted
                                )
                            },
                            enabled = false,
                            onClick = {}
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(s.deleteButton, color = c.coral) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.coral) },
                        onClick = {
                            menuBudgetId = null
                            onDelete(b.id)
                        }
                    )
                }
            }
        }

        item {
            DashedAddButton(text = s.pick("Новый бюджет", "New budget"), onClick = onOpenCreate)
        }
    }
}

@Composable
private fun BudgetSummaryCard(
    budgets: List<BudgetEnvelopeDto>,
    currency: String,
    catMap: Map<String, CategoryDto>
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val spent = budgets.fold(BigDecimal.ZERO) { acc, b -> acc + b.spentAmount }
    val limit = budgets.fold(BigDecimal.ZERO) { acc, b -> acc + b.limitAmount }
    val remaining = budgets.fold(BigDecimal.ZERO) { acc, b -> acc + b.remainingAmount.max(BigDecimal.ZERO) }
    val today = LocalDate.now()
    val daysLeft = (today.lengthOfMonth() - today.dayOfMonth + 1).toLong()
    val perDay = remaining.divide(BigDecimal(daysLeft), 0, RoundingMode.DOWN)
    val usage = if (limit.signum() == 0) BigDecimal.ZERO else spent.multiply(BigDecimal(100)).divide(limit, 0, RoundingMode.HALF_UP)
    val ringColor = when {
        usage >= BigDecimal(100) -> c.coral
        usage >= BigDecimal(75) -> c.amber
        else -> c.mint
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(c.mint.copy(alpha = 0.10f), c.surf)))
            .kakeiCard(cornerRadius = 24.dp, color = Color.Transparent, borderColor = c.mint.copy(alpha = 0.45f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(spentInMonthLabel(today.month, s), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatMoney(spent, currency), style = MaterialTheme.typography.headlineLarge, color = c.text, maxLines = 1)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "/ ${CurrencyFormatter.format(limit, currency)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.faint,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            KakeiRing(progress = usage.toFloat() / 100f, color = ringColor, size = 76.dp, strokeWidth = 8.dp) {
                Text("${usage.toPlainString()}%", style = MaterialTheme.typography.titleSmall, color = c.text)
            }
        }
        // Stacked bar: each budget's share of the combined limit, in its category color.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(c.surf2),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (limit.signum() > 0) {
                budgets.forEach { b ->
                    val w = b.spentAmount.min(b.limitAmount).divide(limit, 4, RoundingMode.HALF_UP).toFloat()
                    if (w > 0f) {
                        val color = b.categoryIds.firstNotNullOfOrNull { catMap[it] }?.let { categoryVisual(it).color } ?: c.mint
                        Box(
                            modifier = Modifier
                                .weight(w)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                        )
                    }
                }
                val unused = BigDecimal.ONE - budgets.fold(BigDecimal.ZERO) { acc, b ->
                    acc + b.spentAmount.min(b.limitAmount).divide(limit, 4, RoundingMode.HALF_UP)
                }
                if (unused.signum() > 0) Spacer(modifier = Modifier.weight(unused.toFloat()))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = c.muted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                s.pick(
                    "$daysLeft ${ruPlural(daysLeft, "день", "дня", "дней")} до конца месяца · можно ${CurrencyFormatter.format(perDay, currency)} в день",
                    "$daysLeft ${if (daysLeft == 1L) "day" else "days"} left this month · ${CurrencyFormatter.format(perDay, currency)} a day"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted
            )
        }
    }
}

private val RU_MONTHS_PREPOSITIONAL = listOf(
    "январе", "феврале", "марте", "апреле", "мае", "июне",
    "июле", "августе", "сентябре", "октябре", "ноябре", "декабре"
)

/** "Потрачено в октябре" / "Spent in October". */
private fun spentInMonthLabel(month: Month, s: AppStrings): String =
    if (s.language == AppLanguage.RU) {
        "Потрачено в ${RU_MONTHS_PREPOSITIONAL[month.value - 1]}"
    } else {
        "Spent in ${month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.ENGLISH)}"
    }

// ============================================================================
// Plan
// ============================================================================

@Composable
private fun PlanningSubScreen(
    forecast: CashFlowForecastDto,
    obligations: List<PlannedObligationDto>,
    categories: List<CategoryDto>,
    onOpenCreate: () -> Unit,
    onExecute: (PlannedObligationDto) -> Unit,
    onDelete: (String) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val catMap = remember(categories) { categories.associateBy { it.id } }
    var menuId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 30-Day Cash Flow Runway summary
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .kakeiCard(
                        cornerRadius = 24.dp,
                        borderColor = if (forecast.liquidityDeficitAlert) c.coral.copy(alpha = 0.5f) else c.line
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(s.cashFlowForecastTitle, style = MaterialTheme.typography.titleLarge, color = c.text)
                if (forecast.liquidityDeficitAlert) {
                    Text(s.liquidityAlertText, style = MaterialTheme.typography.bodyMedium, color = c.coral)
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatColumn(
                        caption = s.pick("Сейчас доступно", "Available now"),
                        value = formatMoney(forecast.currentLiquidBalance, forecast.baseCurrency),
                        valueColor = c.text,
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        caption = s.pick("Через ${forecast.horizonDays} дней", "In ${forecast.horizonDays} days"),
                        value = formatMoney(forecast.forecastedEndBalance, forecast.baseCurrency),
                        valueColor = if (forecast.liquidityDeficitAlert) c.coral else c.mint,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            SectionHeader(title = s.pick("Регулярные платежи", "Planned payments"))
        }

        if (obligations.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.EventRepeat,
                    title = s.pick("Регулярных платежей нет", "No planned payments"),
                    hint = s.pick(
                        "Добавьте аренду, подписки или зарплату, чтобы прогноз учитывал их.",
                        "Add rent, subscriptions or salary so the forecast accounts for them."
                    )
                )
            }
        } else {
            item {
                GroupCard {
                    obligations.forEachIndexed { index, ob ->
                        if (index > 0) RowDivider(startIndent = 74.dp)
                        val cat = ob.categoryId?.let { catMap[it] }
                        val visual = when {
                            ob.type == "INCOME" -> CategoryVisual(Icons.Outlined.Work, c.mint)
                            cat != null -> categoryVisual(cat)
                            else -> CategoryVisual(Icons.Outlined.EventRepeat, c.violet)
                        }
                        Box {
                            TransactionRow(
                                icon = visual.icon,
                                iconColor = visual.color,
                                title = ob.title,
                                subtitle = s.period(ob.frequency),
                                amount = (if (ob.type == "INCOME") "+" else "−") + CurrencyFormatter.format(ob.amount, ob.currency),
                                amountColor = if (ob.type == "INCOME") c.mint else c.text,
                                trailingCaption = shortDate(ob.nextDueDate, s) ?: ob.nextDueDate,
                                modifier = Modifier.combinedClickableCompat { menuId = ob.id }
                            )
                            DropdownMenu(expanded = menuId == ob.id, onDismissRequest = { menuId = null }) {
                                DropdownMenuItem(
                                    text = { Text(s.executeNowButton) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                                    onClick = {
                                        menuId = null
                                        onExecute(ob)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(s.deleteButton, color = c.coral) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.coral) },
                                    onClick = {
                                        menuId = null
                                        onDelete(ob.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            DashedAddButton(text = s.createObligationButton, onClick = onOpenCreate)
        }
    }
}

/** Tap and long-press both open the row's action menu. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onOpen: () -> Unit): Modifier =
    this.combinedClickable(onClick = onOpen, onLongClick = onOpen)

/** Short month name for the yearly matrix, e.g. "окт." / "Oct". */
private fun monthLabel(month: Int, language: AppLanguage): String {
    val locale = if (language == AppLanguage.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH
    return Month.of(month.coerceIn(1, 12)).getDisplayName(TextStyle.SHORT_STANDALONE, locale)
}

/** The server reports records without a payee as "Unknown Merchant"; show that in the UI language. */
private fun payeeLabel(payee: String, s: AppStrings): String =
    if (payee.isBlank() || payee == "Unknown Merchant") s.pick("Без получателя", "Unknown Merchant") else payee
