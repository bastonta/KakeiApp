package com.kakei.app.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakei.app.R
import com.kakei.app.core.i18n.days
import com.kakei.app.core.i18n.obligationPeriod
import com.kakei.app.core.i18n.operationType
import com.kakei.app.core.i18n.period
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Category slices shown in the donut before the rest are grouped into "Other". */
private const val DONUT_TOP_CATEGORIES = 5

/**
 * Tab 2: Analytics — Overview (period statistics and the health score, see [AnalyticsOverview]),
 * Envelope Budgets and the Planned Obligations list with the 30-day cash flow forecast.
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
    transactions: List<TransactionDto>,
    forecast: CashFlowForecastDto,
    healthScore: FinancialHealthScoreDto,
    streaks: NoSpendStreakDto,
    repository: KakeiRepository
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var showCreateBudgetDialog by remember { mutableStateOf(false) }
    var showCreateObligationDialog by remember { mutableStateOf(false) }
    /** Planned payment opened in the dialog for editing; null while creating a new one. */
    var editingObligation by remember { mutableStateOf<PlannedObligationDto?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ScreenHeader(title = s.getString(R.string.nav_insights))
            Spacer(modifier = Modifier.height(10.dp))
            KakeiSegmented(
                options = listOf(s.getString(R.string.tab_analytics), s.getString(R.string.tab_budgets), s.getString(R.string.tab_planning)),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        when (subTab) {
            0 -> AnalyticsOverview(
                baseCurrency = baseCurrency,
                langCode = langCode,
                accounts = accounts,
                categories = categories,
                transactions = transactions,
                healthScore = healthScore,
                streaks = streaks,
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
                accounts = accounts,
                categories = categories,
                onOpenCreate = {
                    editingObligation = null
                    showCreateObligationDialog = true
                },
                onEdit = {
                    editingObligation = it
                    showCreateObligationDialog = true
                },
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
            title = { Text(s.getString(R.string.create_budget_button)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = bName,
                        onValueChange = { bName = it },
                        label = { Text(s.getString(R.string.envelope_name)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = bLimit,
                        onValueChange = { bLimit = it },
                        label = { Text(s.getString(R.string.budget_limit, baseCurrency)) },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("WEEKLY", "MONTHLY", "YEARLY").forEach { p ->
                            KakeiChip(text = s.period(p), selected = bPeriod == p, onClick = { bPeriod = p })
                        }
                    }
                    Text(s.getString(R.string.category_label), style = MaterialTheme.typography.labelLarge, color = c.muted)
                    // A group covers its subcategories, like the server-side budget totals.
                    CategoryPickerRow(
                        categories = categories,
                        selectedId = null,
                        langCode = langCode,
                        isSelected = { it in selectedCats },
                        recordType = "EXPENSE",
                        onSelect = { cat ->
                            selectedCats = if (cat.id in selectedCats) selectedCats - cat.id else selectedCats + cat.id
                        }
                    )
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
                    Text(s.getString(R.string.save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateBudgetDialog = false }) {
                    Text(s.getString(R.string.cancel_button))
                }
            }
        )
    }

    if (showCreateObligationDialog) key(editingObligation?.id) {
        val editing = editingObligation
        val editingStart = editing?.let { ob ->
            runCatching { LocalDate.parse(ob.startDate.ifBlank { ob.nextDueDate }.take(10)) }.getOrNull()
        }
        var title by remember { mutableStateOf(editing?.title.orEmpty()) }
        var amount by remember { mutableStateOf(editing?.amount?.stripTrailingZeros()?.toPlainString() ?: "25000") }
        var freq by remember { mutableStateOf(editing?.frequency ?: "MONTHLY") }
        var opType by remember { mutableStateOf(editing?.type ?: "EXPENSE") }
        var startDate by remember { mutableStateOf(editingStart ?: LocalDate.now()) }
        var dayOfMonth by remember {
            mutableStateOf((editing?.dayOfMonth ?: (editingStart ?: LocalDate.now()).dayOfMonth).toString())
        }
        var intervalDays by remember { mutableStateOf((editing?.intervalDays ?: 30).toString()) }
        var showStartPicker by remember { mutableStateOf(false) }
        // The payment's own account stays listed even if it was archived or made read-only since.
        val pickableAccounts = remember(accounts, editing?.accountId) {
            accounts.filter {
                it.id == editing?.accountId || (!it.isArchived && it.permissionLevel != "READ_ONLY")
            }
        }
        var accountId by remember {
            mutableStateOf(editing?.accountId ?: pickableAccounts.firstOrNull()?.id.orEmpty())
        }
        val selectedAccount = accounts.firstOrNull { it.id == accountId }
        // Payments are posted in the currency of the account they are charged to.
        val dialogCurrency = selectedAccount?.currency ?: editing?.currency ?: baseCurrency

        AlertDialog(
            onDismissRequest = { showCreateObligationDialog = false },
            title = { Text(if (editing != null) s.getString(R.string.edit_payment) else s.getString(R.string.create_obligation_button)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(s.getString(R.string.title_payee)) },
                        singleLine = true
                    )
                    AccountPickerField(
                        caption = s.getString(R.string.account),
                        accounts = pickableAccounts,
                        selectedId = accountId,
                        onSelect = { accountId = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("${s.getString(R.string.amount_label)} ($dialogCurrency)") },
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
                        listOf("WEEKLY", "MONTHLY", "EVERY_N_DAYS", "YEARLY", "ONE_OFF").forEach { f ->
                            KakeiChip(text = s.period(f), selected = freq == f, onClick = { freq = f })
                        }
                    }
                    OutlinedButton(onClick = { showStartPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.getString(R.string.start_on) +
                                (shortDate(startDate.toString(), s, withYear = true) ?: startDate.toString())
                        )
                    }
                    when (freq) {
                        "MONTHLY" -> OutlinedTextField(
                            value = dayOfMonth,
                            onValueChange = { v -> dayOfMonth = v.filter { it.isDigit() }.take(2) },
                            label = { Text(s.getString(R.string.day_of_month_1_31)) },
                            supportingText = {
                                Text(s.getString(R.string.shorter_months_use_their_last))
                            },
                            isError = dayOfMonth.toIntOrNull()?.let { it in 1..31 } != true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        "EVERY_N_DAYS" -> OutlinedTextField(
                            value = intervalDays,
                            onValueChange = { v -> intervalDays = v.filter { it.isDigit() }.take(4) },
                            label = { Text(s.getString(R.string.repeat_every_n_days)) },
                            isError = intervalDays.toIntOrNull()?.let { it in 1..3660 } != true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val dom = dayOfMonth.toIntOrNull()?.takeIf { it in 1..31 }
                        val interval = intervalDays.toIntOrNull()?.takeIf { it in 1..3660 }
                        val scheduleValid = when (freq) {
                            "MONTHLY" -> dom != null
                            "EVERY_N_DAYS" -> interval != null
                            else -> true
                        }
                        if (editing != null && title.isNotBlank() && amt > BigDecimal.ZERO && accountId.isNotBlank() && scheduleValid) {
                            repository.updateObligation(
                                id = editing.id,
                                title = title,
                                accountId = accountId,
                                currency = dialogCurrency,
                                type = opType,
                                amount = amt,
                                frequency = freq,
                                startDate = startDate,
                                dayOfMonth = dom,
                                intervalDays = interval
                            )
                            showCreateObligationDialog = false
                        } else if (editing == null && title.isNotBlank() && amt > BigDecimal.ZERO && accountId.isNotBlank() && scheduleValid) {
                            repository.createObligation(
                                title = title,
                                accountId = accountId,
                                categoryId = null,
                                type = opType,
                                amount = amt,
                                currency = dialogCurrency,
                                frequency = freq,
                                startDate = startDate,
                                dayOfMonth = dom,
                                intervalDays = interval,
                                payee = title
                            )
                            showCreateObligationDialog = false
                        }
                    }
                ) {
                    Text(s.getString(R.string.save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateObligationDialog = false }) {
                    Text(s.getString(R.string.cancel_button))
                }
            }
        )

        if (showStartPicker) {
            val zone = ZoneOffset.UTC
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { showStartPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let {
                            val picked = Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
                            // Picking a start date suggests repeating on that day of the month.
                            if (startDate.dayOfMonth.toString() == dayOfMonth) dayOfMonth = picked.dayOfMonth.toString()
                            startDate = picked
                        }
                        showStartPicker = false
                    }) { Text(s.getString(R.string.done)) }
                },
                dismissButton = {
                    TextButton(onClick = { showStartPicker = false }) { Text(s.getString(R.string.cancel_button)) }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }
    }
}

// ============================================================================
// Overview
// ============================================================================

@Composable
internal fun StatColumn(caption: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    val c = KakeiTheme.colors
    Column(modifier = modifier) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ForecastChart(forecast: CashFlowForecastDto) {
    val s = LocalResources.current
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
    val dateFormat = DateTimeFormatter.ofPattern(s.getString(R.string.date_pattern_day_month_short), localeFor(s))
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

/**
 * Spending by category: donut with a share legend and the total. Used for this month on
 * Analytics and for the last 30 days on Home. [delta] is the change vs the previous period, in %.
 */
@Composable
internal fun CategoryDonutCard(
    spend: List<Pair<String?, BigDecimal>>,
    categories: List<CategoryDto>,
    langCode: String,
    currency: String,
    title: String,
    subtitle: String,
    delta: BigDecimal?,
    deltaSuffix: String
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val total = spend.fold(BigDecimal.ZERO) { acc, p -> acc + p.second }

    data class Slice(val label: String, val amount: BigDecimal, val color: Color)

    val slices = buildList {
        spend.take(DONUT_TOP_CATEGORIES).forEach { (catId, amt) ->
            val cat = catId?.let { catMap[it] }
            add(
                Slice(
                    label = cat?.localizedName(langCode) ?: s.getString(R.string.uncategorized),
                    amount = amt,
                    color = cat?.let { categoryVisual(it).color } ?: c.faint
                )
            )
        }
        val rest = spend.drop(DONUT_TOP_CATEGORIES).fold(BigDecimal.ZERO) { acc, p -> acc + p.second }
        if (rest.signum() > 0) add(Slice(s.getString(R.string.the_rest), rest, c.faint))
    }
    fun share(amount: BigDecimal): BigDecimal =
        if (total.signum() == 0) BigDecimal.ZERO else amount.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.text)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            val pct = delta?.setScale(0, RoundingMode.HALF_UP)
            if (pct != null && pct.signum() != 0) {
                KakeiBadge(
                    text = (if (pct.signum() > 0) "+" else "−") + pct.abs().toPlainString() + "% " + deltaSuffix,
                    color = if (pct.signum() > 0) c.coral else c.mint
                )
            }
        }
        if (slices.isEmpty()) {
            Text(
                s.getString(R.string.no_spending_in_this_period),
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(132.dp)) {
                val stroke = 18.dp.toPx()
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
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                slices.forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(slice.color)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            slice.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${share(slice.amount)}%", style = MaterialTheme.typography.labelMedium, color = c.text)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.getString(R.string.total), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
            Text(formatMoney(total, currency), style = MaterialTheme.typography.headlineMedium, color = c.text, maxLines = 1)
        }
    }
}

// ============================================================================
// Budgets
// ============================================================================

@Composable
private fun BudgetsSubScreen(
    baseCurrency: String,
    budgets: List<BudgetEnvelopeDto>,
    categories: List<CategoryDto>,
    onOpenCreate: () -> Unit,
    onDelete: (String) -> Unit
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val catMap = remember(categories) { categories.associateBy { it.id } }
    var menuBudgetId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Period summary across all budgets in the base currency
            val summed = budgets.filter { it.currency.equals(baseCurrency, ignoreCase = true) }
            if (summed.isNotEmpty()) {
                item {
                    BudgetSummaryCard(summed, baseCurrency)
                }
            }

            if (budgets.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        title = s.getString(R.string.no_budgets_yet),
                        hint = s.getString(R.string.set_a_limit_for_some)
                    )
                }
            }

            items(budgets, key = { it.id }) { b ->
                val exceeded = b.spentAmount > b.limitAmount ||
                    b.alertLevel == "EXCEEDED_100" || b.alertLevel == "CRITICAL_100"
                val projected = projectedSpend(b)
                val atRisk = !exceeded && projected != null && projected > b.limitAmount
                val cat = b.categoryIds.firstNotNullOfOrNull { catMap[it] }
                val visual = cat?.let { categoryVisual(it) } ?: CategoryVisual(Icons.Outlined.AccountBalanceWallet, c.mint)
                val barColor = when {
                    exceeded -> c.coral
                    atRisk -> c.amber
                    else -> visual.color
                }
                val caption = when {
                    exceeded -> s.getString(R.string.over_the_limit_by) +
                        formatMoney(b.spentAmount - b.limitAmount, b.currency)
                    atRisk -> {
                        val end = shortDate(b.periodEnd, s)
                        s.getString(R.string.forecast) + (end?.let { s.getString(R.string.by, it) } ?: "") +
                            ": " + formatMoney(projected!!, b.currency)
                    }
                    projected != null -> s.getString(R.string.on_track_forecast) +
                        formatMoney(projected, b.currency)
                    else -> "${s.period(b.periodType)} · ${s.getString(R.string.left)} ${s.days(b.remainingDaysInPeriod)}"
                }
                val limit = b.limitAmount.takeIf { it.signum() > 0 }

                Box {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .kakeiCard()
                            .combinedClickable(onClick = { menuBudgetId = b.id }, onLongClick = { menuBudgetId = b.id })
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTile(icon = visual.icon, color = visual.color)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        b.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = c.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (exceeded || atRisk) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        KakeiBadge(
                                            text = if (exceeded) s.getString(R.string.over) else s.getString(R.string.at_risk),
                                            color = barColor
                                        )
                                    }
                                }
                                Text(
                                    caption,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatMoney(b.spentAmount, b.currency),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (exceeded) c.coral else c.text,
                                    maxLines = 1
                                )
                                Text(
                                    "${s.getString(R.string.of)} ${CurrencyFormatter.format(b.limitAmount, b.currency).substringBeforeLast(' ')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.faint,
                                    maxLines = 1
                                )
                            }
                        }
                        KakeiProgressBar(
                            progress = b.usagePercentage.toFloat() / 100f,
                            color = barColor,
                            height = 8.dp,
                            forecast = if (limit != null && projected != null) {
                                projected.divide(limit, 4, RoundingMode.HALF_UP).toFloat()
                            } else null
                        )
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
                            text = { Text(s.getString(R.string.delete_button), color = c.coral) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.coral) },
                            onClick = {
                                menuBudgetId = null
                                onDelete(b.id)
                            }
                        )
                    }
                }
            }
        }

        KakeiButton(
            text = s.getString(R.string.create_budget),
            onClick = onOpenCreate,
            icon = Icons.Outlined.Add,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
        )
    }
}

/**
 * Spend at the end of the period if the current pace holds; null before the period has data.
 * [BudgetEnvelopeDto.remainingDaysInPeriod] counts today, so the period is elapsed-before-today + remaining.
 */
private fun projectedSpend(b: BudgetEnvelopeDto): BigDecimal? {
    val start = runCatching { LocalDate.parse(b.periodStart.take(10)) }.getOrNull() ?: return null
    val before = ChronoUnit.DAYS.between(start, LocalDate.now())
    if (before < 0 || b.remainingDaysInPeriod <= 0) return null
    val total = before + b.remainingDaysInPeriod
    return b.spentAmount.multiply(BigDecimal(total)).divide(BigDecimal(before + 1), 0, RoundingMode.HALF_UP)
}

@Composable
private fun BudgetSummaryCard(budgets: List<BudgetEnvelopeDto>, currency: String) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val spent = budgets.fold(BigDecimal.ZERO) { acc, b -> acc + b.spentAmount }
    val limit = budgets.fold(BigDecimal.ZERO) { acc, b -> acc + b.limitAmount }
    val remaining = budgets.fold(BigDecimal.ZERO) { acc, b -> acc + b.remainingAmount.max(BigDecimal.ZERO) }
    val today = LocalDate.now()
    val daysLeft = (today.lengthOfMonth() - today.dayOfMonth + 1).toLong()
    val usage = if (limit.signum() == 0) BigDecimal.ZERO else spent.multiply(BigDecimal(100)).divide(limit, 0, RoundingMode.HALF_UP)
    val ringColor = when {
        usage >= BigDecimal(100) -> c.coral
        usage >= BigDecimal(75) -> c.amber
        else -> c.mint
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KakeiRing(progress = usage.toFloat() / 100f, color = ringColor, size = 72.dp, strokeWidth = 7.dp) {
            Text("${usage.toPlainString()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.text)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${monthName(today.monthValue, s)} · " + s.getQuantityString(R.plurals.days_left, daysLeft.toInt(), daysLeft),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted
            )
            Text(formatMoney(remaining, currency), style = MaterialTheme.typography.headlineLarge, color = c.text, maxLines = 1)
            Text(
                s.getString(R.string.free_of) + formatMoney(limit, currency),
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
                maxLines = 1
            )
        }
    }
}

// ============================================================================
// Plan
// ============================================================================

@Composable
private fun PlanningSubScreen(
    forecast: CashFlowForecastDto,
    obligations: List<PlannedObligationDto>,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    onOpenCreate: () -> Unit,
    onEdit: (PlannedObligationDto) -> Unit,
    onExecute: (PlannedObligationDto) -> Unit,
    onDelete: (String) -> Unit
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
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
                Text(s.getString(R.string.cash_flow_forecast_title), style = MaterialTheme.typography.titleLarge, color = c.text)
                if (forecast.liquidityDeficitAlert) {
                    Text(s.getString(R.string.liquidity_alert_text), style = MaterialTheme.typography.bodyMedium, color = c.coral)
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatColumn(
                        caption = s.getString(R.string.available_now),
                        value = formatMoney(forecast.currentLiquidBalance, forecast.baseCurrency),
                        valueColor = c.text,
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        caption = s.getString(R.string.in_days, forecast.horizonDays),
                        value = formatMoney(forecast.forecastedEndBalance, forecast.baseCurrency),
                        valueColor = if (forecast.liquidityDeficitAlert) c.coral else c.mint,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (forecast.dailyTimeline.size >= 2) {
                    ForecastChart(forecast)
                }
                RowDivider()
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatColumn(
                        caption = s.getString(R.string.income),
                        value = "+" + CurrencyFormatter.format(forecast.plannedIncomeTotal, forecast.baseCurrency),
                        valueColor = c.mint,
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        caption = s.getString(R.string.payments),
                        value = "−" + CurrencyFormatter.format(forecast.plannedExpenseTotal, forecast.baseCurrency),
                        valueColor = c.coral,
                        modifier = Modifier.weight(1f)
                    )
                    StatColumn(
                        caption = s.getString(R.string.load),
                        value = "${forecast.obligationToLiquidityRatio.setScale(0, RoundingMode.HALF_UP).toPlainString()}%",
                        valueColor = c.text,
                        modifier = Modifier.weight(0.7f)
                    )
                }
            }
        }

        item {
            SectionHeader(title = s.getString(R.string.planned_payments))
        }

        if (obligations.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.EventRepeat,
                    title = s.getString(R.string.no_planned_payments),
                    hint = s.getString(R.string.add_rent_subscriptions_or_salary)
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
                                subtitle = listOfNotNull(
                                    s.obligationPeriod(ob.frequency, ob.dayOfMonth, ob.intervalDays),
                                    accMap[ob.accountId]?.name
                                ).joinToString(" · "),
                                amount = (if (ob.type == "INCOME") "+" else "−") + CurrencyFormatter.format(ob.amount, ob.currency),
                                amountColor = if (ob.type == "INCOME") c.mint else c.text,
                                trailingCaption = shortDate(ob.nextDueDate, s) ?: ob.nextDueDate,
                                modifier = Modifier.combinedClickableCompat { menuId = ob.id }
                            )
                            DropdownMenu(expanded = menuId == ob.id, onDismissRequest = { menuId = null }) {
                                DropdownMenuItem(
                                    text = { Text(s.getString(R.string.edit)) },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = {
                                        menuId = null
                                        onEdit(ob)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(s.getString(R.string.execute_now_button)) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                                    onClick = {
                                        menuId = null
                                        onExecute(ob)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(s.getString(R.string.delete_button), color = c.coral) },
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
            DashedAddButton(text = s.getString(R.string.create_obligation_button), onClick = onOpenCreate)
        }
    }
}

/** Tap and long-press both open the row's action menu. */
private fun Modifier.combinedClickableCompat(onOpen: () -> Unit): Modifier =
    this.combinedClickable(onClick = onOpen, onLongClick = onOpen)
