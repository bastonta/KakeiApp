package com.kakei.app.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakei.app.R
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.domain.calculators.*
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Category rows shown in the breakdown before "Show all". */
private const val CATEGORY_ROWS_COLLAPSED = 6

/** Slices drawn in the breakdown donut before the rest are merged. */
private const val DONUT_SLICES = 5

/**
 * Analytics → Overview: statistics for a month, a year or any date range (totals and the
 * comparison with the period before, an income / expense chart, the category breakdown,
 * spending by weekday, a day calendar and highlights), plus the Financial Health Score.
 * Everything is computed locally from the ledger, so it works offline and updates instantly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalyticsOverview(
    baseCurrency: String,
    langCode: String,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    transactions: List<TransactionDto>,
    healthScore: FinancialHealthScoreDto,
    streaks: NoSpendStreakDto,
    repository: KakeiRepository
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val rateBook by repository.rateBook.collectAsState()
    val today = LocalDate.now()

    var kindName by rememberSaveable { mutableStateOf(StatsPeriodKind.MONTH.name) }
    var startDay by rememberSaveable { mutableLongStateOf(YearMonth.from(today).atDay(1).toEpochDay()) }
    var endDay by rememberSaveable { mutableLongStateOf(YearMonth.from(today).atEndOfMonth().toEpochDay()) }
    var showRangePicker by remember { mutableStateOf(false) }

    val period = remember(kindName, startDay, endDay, today) {
        val start = LocalDate.ofEpochDay(startDay)
        when (StatsPeriodKind.valueOf(kindName)) {
            StatsPeriodKind.MONTH -> StatsPeriod.month(YearMonth.from(start), today)
            StatsPeriodKind.MONTHS_3, StatsPeriodKind.MONTHS_6 ->
                StatsPeriod.months(StatsPeriodKind.valueOf(kindName), YearMonth.from(LocalDate.ofEpochDay(endDay)), today)
            StatsPeriodKind.YEAR -> StatsPeriod.year(start.year, today)
            StatsPeriodKind.CUSTOM -> StatsPeriod.custom(start, LocalDate.ofEpochDay(endDay), today)
        }
    }
    fun select(p: StatsPeriod) {
        kindName = p.kind.name
        startDay = p.start.toEpochDay()
        endDay = p.end.toEpochDay()
    }

    val stats = remember(transactions, categories, accounts, rateBook, period, baseCurrency) {
        PeriodStatistics.calculate(transactions, categories, accounts, period, baseCurrency, rateBook)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            PeriodSelectorCard(
                period = period,
                onKind = { kind ->
                    when (kind) {
                        StatsPeriodKind.MONTH -> select(StatsPeriod.month(YearMonth.from(period.elapsedEnd), today))
                        StatsPeriodKind.MONTHS_3, StatsPeriodKind.MONTHS_6 ->
                            select(StatsPeriod.months(kind, YearMonth.from(period.elapsedEnd), today))
                        StatsPeriodKind.YEAR -> select(StatsPeriod.year(period.elapsedEnd.year, today))
                        StatsPeriodKind.CUSTOM -> showRangePicker = true
                    }
                },
                onShift = { select(period.shift(it, today)) },
                onPickRange = { showRangePicker = true }
            )
        }
        item { PeriodSummaryCard(stats) }
        item { CashFlowChartCard(stats) }
        item { CategoryBreakdownCard(stats, categories, langCode) }
        if (stats.expense.signum() > 0) {
            item { WeekdayCard(stats) }
        }
        if (period.kind == StatsPeriodKind.MONTH) {
            item { MonthCalendarCard(stats, streaks) }
        }
        if (stats.largestExpense != null || stats.topPayees.isNotEmpty()) {
            item { HighlightsCard(stats, categories, langCode) }
        }
        item { HealthScoreCard(healthScore) }
        if (stats.unconvertedCount > 0) {
            item {
                Text(
                    s.getString(R.string.stats_unconverted, stats.unconvertedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    if (showRangePicker) {
        val zone = ZoneOffset.UTC
        val initial = period.takeIf { it.kind == StatsPeriodKind.CUSTOM }
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = initial?.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = initial?.end?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                        val end = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: start
                        if (start != null && end != null) select(StatsPeriod.custom(start, end, today))
                        showRangePicker = false
                    }
                ) { Text(s.getString(R.string.apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) { Text(s.getString(R.string.cancel_button)) }
            }
        ) {
            DateRangePicker(
                state = state,
                showModeToggle = true,
                modifier = Modifier.weight(1f),
                title = {
                    Text(
                        s.getString(R.string.stats_pick_range),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                },
                headline = {
                    val from = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    val to = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    Text(
                        when {
                            from == null -> s.getString(R.string.stats_pick_range_hint)
                            to == null -> rangeLabel(from, from, s) + " – …"
                            else -> rangeLabel(from, to, s)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp)
                    )
                }
            )
        }
    }
}

// ============================================================================
// Period selector
// ============================================================================

@Composable
private fun PeriodSelectorCard(
    period: StatsPeriod,
    onKind: (StatsPeriodKind) -> Unit,
    onShift: (Long) -> Unit,
    onPickRange: () -> Unit
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val canGoForward = !period.shift(1).isInFuture()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Five chips don't fit on narrow screens, so the row scrolls sideways.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                StatsPeriodKind.MONTH to R.string.stats_period_month,
                StatsPeriodKind.MONTHS_3 to R.string.stats_period_3_months,
                StatsPeriodKind.MONTHS_6 to R.string.stats_period_6_months,
                StatsPeriodKind.YEAR to R.string.stats_period_year,
                StatsPeriodKind.CUSTOM to R.string.stats_period_custom
            ).forEach { (kind, label) ->
                KakeiChip(
                    text = s.getString(label),
                    selected = period.kind == kind,
                    icon = if (kind == StatsPeriodKind.CUSTOM) Icons.Outlined.DateRange else null,
                    onClick = { onKind(kind) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onShift(-1) }) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = s.getString(R.string.stats_previous_period), tint = c.text)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = period.kind == StatsPeriodKind.CUSTOM, onClick = onPickRange)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    periodTitle(period, s),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    s.getQuantityString(R.plurals.stats_days, period.elapsedDays.toInt(), period.elapsedDays.toInt()) +
                        if (period.elapsedEnd.isBefore(period.end)) " · " + s.getString(R.string.stats_in_progress) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted
                )
            }
            IconButton(onClick = { onShift(1) }, enabled = canGoForward) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = s.getString(R.string.stats_next_period),
                    tint = if (canGoForward) c.text else c.faint
                )
            }
        }
    }
}

// ============================================================================
// Summary
// ============================================================================

@Composable
private fun PeriodSummaryCard(stats: PeriodStats) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val cur = stats.currency
    val period = stats.period

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(s.getString(R.string.expenses), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                Text(
                    formatMoney(stats.expense, cur),
                    style = MaterialTheme.typography.headlineLarge,
                    color = c.text,
                    maxLines = 1
                )
            }
            DeltaBadge(PeriodStatistics.deltaPercent(stats.expense, stats.previousExpense), higherIsBad = true)
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatWithDelta(
                caption = s.getString(R.string.income),
                value = formatMoney(stats.income, cur),
                valueColor = c.mint,
                delta = PeriodStatistics.deltaPercent(stats.income, stats.previousIncome),
                higherIsBad = false,
                modifier = Modifier.weight(1f)
            )
            StatWithDelta(
                caption = s.getString(R.string.stats_net),
                value = formatMoney(stats.net, cur, showPlus = true),
                valueColor = if (stats.net.signum() < 0) c.coral else c.text,
                delta = null,
                higherIsBad = false,
                modifier = Modifier.weight(1f)
            )
        }
        RowDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn(
                caption = s.getString(R.string.stats_savings_rate),
                value = stats.savingsRate?.let { "${it.setScale(0, RoundingMode.HALF_UP).toPlainString()}%" } ?: "—",
                valueColor = when {
                    stats.savingsRate == null -> c.muted
                    stats.savingsRate!! >= BigDecimal(20) -> c.mint
                    stats.savingsRate!!.signum() < 0 -> c.coral
                    else -> c.text
                },
                modifier = Modifier.weight(1f)
            )
            StatColumn(
                caption = s.getString(R.string.stats_avg_per_day),
                value = formatMoney(stats.averageDailyExpense.setScale(0, RoundingMode.HALF_UP), cur),
                valueColor = c.text,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn(
                caption = s.getString(R.string.stats_records),
                value = "${stats.expenseCount + stats.incomeCount}",
                valueColor = c.text,
                modifier = Modifier.weight(1f)
            )
            StatColumn(
                caption = s.getString(R.string.stats_no_spend_days),
                value = "${stats.noSpendDays} / ${period.elapsedDays}",
                valueColor = c.text,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            s.getString(R.string.stats_compared_with, rangeLabel(period.previousStart, period.previousEnd, s)),
            style = MaterialTheme.typography.bodySmall,
            color = c.faint
        )
    }
}

@Composable
private fun StatWithDelta(
    caption: String,
    value: String,
    valueColor: Color,
    delta: BigDecimal?,
    higherIsBad: Boolean,
    modifier: Modifier = Modifier
) {
    val c = KakeiTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (delta != null && delta.signum() != 0) {
                Spacer(modifier = Modifier.width(6.dp))
                DeltaBadge(delta, higherIsBad)
            }
        }
    }
}

/** "+12%" / "−8%"; red when the change is unfavourable. */
@Composable
private fun DeltaBadge(delta: BigDecimal?, higherIsBad: Boolean) {
    val c = KakeiTheme.colors
    if (delta == null || delta.signum() == 0) return
    val up = delta.signum() > 0
    val bad = up == higherIsBad
    KakeiBadge(
        text = (if (up) "+" else "−") + delta.abs().toPlainString() + "%",
        color = if (bad) c.coral else c.mint
    )
}

// ============================================================================
// Income / expense chart
// ============================================================================

@Composable
private fun CashFlowChartCard(stats: PeriodStats) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val buckets = stats.buckets
    val size = stats.period.bucketSize
    var selected by remember(stats.period) { mutableStateOf<Int?>(null) }
    val max = buckets.maxOfOrNull { maxOf(it.income, it.expense) } ?: BigDecimal.ZERO
    fun ratio(v: BigDecimal): Float =
        if (max.signum() == 0) 0f else v.divide(max, 4, RoundingMode.HALF_UP).toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(s.getString(R.string.stats_cash_flow_title), style = MaterialTheme.typography.titleLarge, color = c.text)

        // Selected bar values, or the legend while nothing is selected.
        val sel = selected?.let { buckets.getOrNull(it) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 22.dp)) {
            if (sel != null) {
                Text(
                    bucketTitle(sel.start, size, stats.period, s),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.text,
                    modifier = Modifier.weight(1f)
                )
                Text("+" + formatMoney(sel.income, stats.currency), style = MaterialTheme.typography.labelLarge, color = c.mint)
                Spacer(modifier = Modifier.width(10.dp))
                Text("−" + formatMoney(sel.expense, stats.currency), style = MaterialTheme.typography.labelLarge, color = c.coral)
            } else {
                LegendDot(c.mint, s.getString(R.string.income))
                Spacer(modifier = Modifier.width(14.dp))
                LegendDot(c.coral, s.getString(R.string.expenses))
                Spacer(modifier = Modifier.weight(1f))
                Text(s.getString(R.string.stats_tap_bar_hint), style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
        }

        val chartHeight = 140.dp
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .pointerInput(buckets) {
                    detectTapGestures { pos ->
                        if (buckets.isEmpty()) return@detectTapGestures
                        val idx = (pos.x / (this.size.width.toFloat() / buckets.size)).toInt().coerceIn(0, buckets.lastIndex)
                        selected = if (selected == idx) null else idx
                    }
                }
        ) {
            val n = buckets.size.coerceAtLeast(1)
            val slot = this.size.width / n
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                drawLine(
                    color = c.line,
                    start = Offset(0f, this.size.height * f),
                    end = Offset(this.size.width, this.size.height * f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                )
            }
            val gap = (slot * 0.18f).coerceAtMost(6.dp.toPx())
            val barW = ((slot - gap) / 2f).coerceAtLeast(1f)
            val radius = CornerRadius((barW / 2f).coerceAtMost(4.dp.toPx()))
            buckets.forEachIndexed { i, b ->
                val x = i * slot + gap / 2f
                val dim = selected != null && selected != i
                if (selected == i) {
                    drawRoundRect(c.surf2, topLeft = Offset(i * slot, 0f), size = Size(slot, this.size.height), cornerRadius = CornerRadius(6.dp.toPx()))
                }
                val hi = this.size.height * ratio(b.income)
                val he = this.size.height * ratio(b.expense)
                if (hi > 0f) drawRoundRect(
                    c.mint.copy(alpha = if (dim) 0.35f else 1f),
                    topLeft = Offset(x, this.size.height - hi), size = Size(barW, hi), cornerRadius = radius
                )
                if (he > 0f) drawRoundRect(
                    c.coral.copy(alpha = if (dim) 0.35f else 1f),
                    topLeft = Offset(x + barW, this.size.height - he), size = Size(barW, he), cornerRadius = radius
                )
            }
        }
        if (buckets.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val last = buckets.lastIndex
                listOf(0, last / 3, last * 2 / 3, last).distinct().forEach { i ->
                    Text(bucketAxisLabel(buckets[i].start, size, s), style = MaterialTheme.typography.labelSmall, color = c.faint)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val c = KakeiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

// ============================================================================
// Category breakdown
// ============================================================================

@Composable
private fun CategoryBreakdownCard(stats: PeriodStats, categories: List<CategoryDto>, langCode: String) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    var showIncome by rememberSaveable { mutableStateOf(false) }
    var showAll by remember(showIncome) { mutableStateOf(false) }
    var expanded by remember(showIncome, stats.period) { mutableStateOf<String?>(null) }
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val rows = if (showIncome) stats.incomeByCategory else stats.expenseByCategory
    val total = if (showIncome) stats.income else stats.expense
    val fallbackColors = listOf(c.violet, c.cyan, c.amber, c.pink, c.blue)

    @Composable
    fun colorOf(stat: CategoryStat, index: Int): Color =
        stat.categoryId?.let { catMap[it] }?.let { categoryVisual(it).color } ?: fallbackColors[index % fallbackColors.size]
    fun nameOf(id: String?): String = id?.let { catMap[it] }?.localizedName(langCode) ?: s.getString(R.string.uncategorized)
    fun share(amount: BigDecimal): BigDecimal =
        if (total.signum() == 0) BigDecimal.ZERO else amount.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(s.getString(R.string.stats_by_category), style = MaterialTheme.typography.titleLarge, color = c.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KakeiChip(text = s.getString(R.string.expenses), selected = !showIncome, color = c.coral, onClick = { showIncome = false })
            KakeiChip(text = s.getString(R.string.income), selected = showIncome, onClick = { showIncome = true })
        }
        if (rows.isEmpty()) {
            Text(
                s.getString(if (showIncome) R.string.stats_no_income else R.string.no_spending_in_this_period),
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted
            )
            return@Column
        }

        val sliceColors = rows.take(DONUT_SLICES).mapIndexed { i, r -> colorOf(r, i) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(128.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 16.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val slices = rows.take(DONUT_SLICES).map { it.amount } +
                        listOfNotNull(rows.drop(DONUT_SLICES).fold(BigDecimal.ZERO) { a, r -> a + r.amount }.takeIf { it.signum() > 0 })
                    val gap = if (slices.size > 1) 2.5f else 0f
                    var start = -90f
                    slices.forEachIndexed { i, amt ->
                        val sweep = amt.divide(total, 6, RoundingMode.HALF_UP).toFloat() * 360f
                        drawArc(
                            color = sliceColors.getOrNull(i) ?: c.faint,
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
                    Text(s.getString(R.string.total), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    Text(
                        formatCompactMoney(total.setScale(0, RoundingMode.HALF_UP), stats.currency, s),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            // Short insight next to the donut: the biggest category and its share.
            val top = rows.first()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.getString(R.string.stats_top_category), style = MaterialTheme.typography.bodySmall, color = c.muted)
                Text(nameOf(top.categoryId), style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${share(top.amount)}% · " + formatMoney(top.amount, stats.currency),
                    style = MaterialTheme.typography.bodyMedium,
                    color = sliceColors.first()
                )
            }
        }

        val visible = if (showAll) rows else rows.take(CATEGORY_ROWS_COLLAPSED)
        visible.forEachIndexed { index, row ->
            val color = if (index < DONUT_SLICES) sliceColors[index] else c.faint
            val isOpen = expanded == (row.categoryId ?: "")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = row.children.isNotEmpty()) { expanded = if (isOpen) null else (row.categoryId ?: "") }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CategoryLine(
                    name = nameOf(row.categoryId),
                    stat = row,
                    share = share(row.amount),
                    color = color,
                    currency = stats.currency,
                    higherIsBad = !showIncome,
                    expandable = row.children.isNotEmpty(),
                    expandedNow = isOpen
                )
                KakeiProgressBar(progress = share(row.amount).toFloat() / 100f, color = color, height = 4.dp)
                if (isOpen) {
                    row.children.forEach { child ->
                        Row(modifier = Modifier.padding(start = 18.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (child.categoryId == row.categoryId) s.getString(R.string.stats_without_subcategory) else nameOf(child.categoryId),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(formatMoney(child.amount, stats.currency), style = MaterialTheme.typography.labelMedium, color = c.text)
                        }
                    }
                }
            }
        }
        if (rows.size > CATEGORY_ROWS_COLLAPSED) {
            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(
                    if (showAll) s.getString(R.string.stats_show_less)
                    else s.getString(R.string.stats_show_all, rows.size)
                )
            }
        }
    }
}

@Composable
private fun CategoryLine(
    name: String,
    stat: CategoryStat,
    share: BigDecimal,
    color: Color,
    currency: String,
    higherIsBad: Boolean,
    expandable: Boolean,
    expandedNow: Boolean
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (expandable) {
                    Icon(
                        if (expandedNow) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = c.faint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                "$share% · " + s.getString(R.string.records_short, stat.count.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatMoney(stat.amount, currency), style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
            val delta = PeriodStatistics.deltaPercent(stat.amount, stat.previousAmount)
            if (delta != null && delta.signum() != 0) {
                val up = delta.signum() > 0
                Text(
                    (if (up) "↑ " else "↓ ") + delta.abs().toPlainString() + "%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (up == higherIsBad) c.coral else c.mint
                )
            }
        }
    }
}

// ============================================================================
// Weekday
// ============================================================================

@Composable
private fun WeekdayCard(stats: PeriodStats) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val locale = localeFor(s)
    val values = stats.weekdayAverage
    val max = values.maxOrNull() ?: BigDecimal.ZERO
    val peak = values.indices.maxByOrNull { values[it] }?.takeIf { max.signum() > 0 }
    val weekdays = values.indices.map { i -> DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale) }
    val weekend = (values[5] + values[6]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
    val workdays = values.take(5).fold(BigDecimal.ZERO) { a, v -> a + v }.divide(BigDecimal(5), 2, RoundingMode.HALF_UP)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(s.getString(R.string.stats_weekday_title), style = MaterialTheme.typography.titleLarge, color = c.text)
        if (peak != null) {
            Text(
                s.getString(R.string.stats_weekday_peak, weekdays[peak], formatMoney(values[peak].setScale(0, RoundingMode.HALF_UP), stats.currency)),
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { i, v ->
                val r = if (max.signum() == 0) 0f else v.divide(max, 4, RoundingMode.HALF_UP).toFloat()
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(r.coerceAtLeast(0.02f) * 0.82f)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (i == peak) c.coral else c.coral.copy(alpha = 0.35f))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == peak) c.text else c.faint
                    )
                }
            }
        }
        RowDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn(
                caption = s.getString(R.string.stats_avg_workday),
                value = formatMoney(workdays.setScale(0, RoundingMode.HALF_UP), stats.currency),
                valueColor = c.text,
                modifier = Modifier.weight(1f)
            )
            StatColumn(
                caption = s.getString(R.string.stats_avg_weekend),
                value = formatMoney(weekend.setScale(0, RoundingMode.HALF_UP), stats.currency),
                valueColor = c.text,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ============================================================================
// Month calendar
// ============================================================================

/** Calendar of the selected month shaded by daily spending; tap a day to see its total. */
@Composable
private fun MonthCalendarCard(stats: PeriodStats, streaks: NoSpendStreakDto) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val ym = YearMonth.from(stats.period.start)
    val today = LocalDate.now()
    val max = stats.dailyExpense.values.maxOrNull() ?: BigDecimal.ZERO
    var selected by remember(ym) { mutableStateOf<LocalDate?>(null) }
    fun level(d: LocalDate): Int {
        val v = stats.dailyExpense[d] ?: return 0
        if (max.signum() == 0) return 0
        val r = v.divide(max, 4, RoundingMode.HALF_UP).toDouble()
        return when {
            r > 0.75 -> 4
            r > 0.5 -> 3
            r > 0.25 -> 2
            else -> 1
        }
    }
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
            Text(s.getString(R.string.daily_spending), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            Text(monthName(ym.monthValue, s), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
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
        val leading = ym.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val slots: List<LocalDate?> = List(leading) { null } + (1..ym.lengthOfMonth()).map { ym.atDay(it) }
        slots.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        val lvl = level(day)
                        val future = day.isAfter(today)
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.15f)
                                .clip(shape)
                                .background(if (future) Color.Transparent else levelColor(lvl))
                                .then(
                                    when {
                                        day == selected -> Modifier.border(1.5.dp, c.text, shape)
                                        day == today -> Modifier.border(1.5.dp, c.text.copy(alpha = 0.5f), shape)
                                        future -> Modifier.border(1.dp, c.line, shape)
                                        else -> Modifier
                                    }
                                )
                                .clickable(enabled = !future) { selected = if (selected == day) null else day },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${day.dayOfMonth}",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    lvl >= 3 -> c.mintInk
                                    lvl > 0 -> c.text
                                    else -> c.faint
                                }
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        val sel = selected
        if (sel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayLabel(sel, s), style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                val spent = stats.dailyExpense[sel]
                Text(
                    spent?.let { "−" + formatMoney(it, stats.currency) } ?: s.getString(R.string.stats_no_spending_day),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (spent != null) c.coral else c.mint
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                val n = streaks.currentStreakDays
                Text(
                    s.getQuantityString(R.plurals.days_without_spending, n.toInt(), n),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(s.getString(R.string.less), style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(modifier = Modifier.width(4.dp))
                (0..4).forEach { lvl ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.5.dp)
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(levelColor(lvl))
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(s.getString(R.string.more), style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
        }
    }
}

// ============================================================================
// Highlights
// ============================================================================

@Composable
private fun HighlightsCard(stats: PeriodStats, categories: List<CategoryDto>, langCode: String) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val catMap = remember(categories) { categories.associateBy { it.id } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(vertical = 8.dp)
    ) {
        Text(
            s.getString(R.string.stats_highlights),
            style = MaterialTheme.typography.titleLarge,
            color = c.text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
        stats.largestExpense?.let { big ->
            val tx = big.transaction
            val cat = (tx.splits.firstOrNull()?.categoryId ?: tx.categoryId)?.let { catMap[it] }
            HighlightRow(
                icon = Icons.Outlined.ShoppingBag,
                tint = c.coral,
                title = s.getString(R.string.stats_largest_expense),
                subtitle = listOfNotNull(
                    tx.payee?.takeIf { it.isNotBlank() } ?: cat?.localizedName(langCode),
                    dayLabel(big.date, s)
                ).joinToString(" · "),
                value = formatMoney(big.amount, stats.currency)
            )
        }
        stats.busiestDay?.let { (day, amount) ->
            HighlightRow(
                icon = Icons.Outlined.CalendarToday,
                tint = c.amber,
                title = s.getString(R.string.stats_busiest_day),
                subtitle = dayLabel(day, s),
                value = formatMoney(amount, stats.currency)
            )
        }
        if (stats.topPayees.isNotEmpty()) {
            RowDivider(startIndent = 18.dp)
            Text(
                s.getString(R.string.top_payees_title),
                style = MaterialTheme.typography.labelLarge,
                color = c.muted,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp)
            )
            stats.topPayees.forEachIndexed { idx, p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${idx + 1}", style = MaterialTheme.typography.labelLarge, color = c.faint, modifier = Modifier.width(22.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.payee, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.getString(R.string.records_short, p.count.toLong()), style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Text(formatMoney(p.amount, stats.currency), style = MaterialTheme.typography.titleSmall, color = c.text)
                }
            }
        }
    }
}

@Composable
private fun HighlightRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, subtitle: String, value: String) {
    val c = KakeiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = c.muted)
            Text(subtitle, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = MaterialTheme.typography.titleSmall, color = c.text)
    }
}

// ============================================================================
// Financial Health Score
// ============================================================================

/**
 * Health score ring with the three parts as earned / maximum points, localized tips, and a
 * "How it works" dialog. The formula is shared with the backend (see FinancialCalculators).
 */
@Composable
private fun HealthScoreCard(health: FinancialHealthScoreDto) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    var showInfo by remember { mutableStateOf(false) }
    val scoreColor = when {
        health.overallScore >= 70 -> c.mint
        health.overallScore >= 40 -> c.amber
        else -> c.coral
    }
    fun pct(v: BigDecimal) = "${v.setScale(0, RoundingMode.HALF_UP).toPlainString()}%"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KakeiRing(progress = health.overallScore / 100f, color = scoreColor, size = 84.dp, strokeWidth = 8.dp) {
                Text("${health.overallScore}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.text)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.getString(R.string.health_score_title), style = MaterialTheme.typography.titleLarge, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KakeiBadge(text = health.grade, color = scoreColor)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(healthGradeLabel(health.overallScore, s), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
                Text(s.getString(R.string.health_period_hint), style = MaterialTheme.typography.bodySmall, color = c.faint)
            }
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Outlined.Info, contentDescription = s.getString(R.string.health_how_it_works), tint = c.muted)
            }
        }

        HealthPart(
            label = s.getString(R.string.savings_rate),
            value = pct(health.savingsRatePercentage),
            points = health.savingsRateScore,
            maxPoints = 40,
            color = c.mint
        )
        HealthPart(
            label = s.getString(R.string.health_budgets_within_limit),
            value = pct(health.budgetCompliancePercentage),
            points = health.budgetComplianceScore,
            maxPoints = 30,
            color = c.cyan
        )
        HealthPart(
            label = s.getString(R.string.debt_income),
            value = pct(health.debtToIncomePercentage),
            points = health.debtToIncomeScore,
            maxPoints = 30,
            color = c.amber
        )

        val tips = healthTips(health, s)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.mint.copy(alpha = 0.10f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tips.forEach { tip ->
                Row {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, tint = c.mint, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tip, style = MaterialTheme.typography.bodyMedium, color = c.text)
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(s.getString(R.string.health_how_it_works)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(s.getString(R.string.health_info_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(s.getString(R.string.health_info_savings), style = MaterialTheme.typography.bodyMedium)
                    Text(s.getString(R.string.health_info_budgets), style = MaterialTheme.typography.bodyMedium)
                    Text(s.getString(R.string.health_info_debt), style = MaterialTheme.typography.bodyMedium)
                    Text(s.getString(R.string.health_info_grades), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text(s.getString(R.string.close)) }
            }
        )
    }
}

@Composable
private fun HealthPart(label: String, value: String, points: Int, maxPoints: Int, color: Color) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = c.muted)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                s.getString(R.string.health_points, points, maxPoints),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = c.text
            )
        }
        KakeiProgressBar(progress = points.toFloat() / maxPoints, color = color, height = 5.dp)
    }
}

/** Grade wording matching the A–F letter (85 / 70 / 55 / 40 thresholds). */
private fun healthGradeLabel(score: Int, s: Resources): String = when {
    score >= 85 -> s.getString(R.string.excellent)
    score >= 70 -> s.getString(R.string.good)
    score >= 55 -> s.getString(R.string.fair)
    else -> s.getString(R.string.needs_attention)
}

/** Localized advice built from the score parts (the server's English tips are not shown). */
internal fun healthTips(h: FinancialHealthScoreDto, s: Resources): List<String> = buildList {
    val rate = h.savingsRatePercentage.setScale(0, RoundingMode.HALF_UP).toPlainString()
    when {
        h.periodIncome.signum() == 0 && h.periodExpense.signum() > 0 -> add(s.getString(R.string.health_tip_no_income))
        h.savingsRatePercentage.signum() < 0 -> add(s.getString(R.string.health_tip_overspent, rate.removePrefix("-")))
        h.savingsRatePercentage < BigDecimal(20) -> add(s.getString(R.string.health_tip_savings_low, rate))
    }
    if (h.budgetCompliancePercentage < BigDecimal(100)) add(s.getString(R.string.health_tip_budgets))
    if (h.debtToIncomePercentage > BigDecimal(30)) {
        add(s.getString(R.string.health_tip_debt, h.debtToIncomePercentage.setScale(0, RoundingMode.HALF_UP).toPlainString()))
    }
    if (isEmpty()) add(s.getString(R.string.health_tip_great))
}

// ============================================================================
// Labels
// ============================================================================

/** "Ноябрь 2026", "2026" or "1 окт – 4 нояб". */
private fun periodTitle(p: StatsPeriod, s: Resources): String = when (p.kind) {
    StatsPeriodKind.MONTH -> "${monthName(p.start.monthValue, s)} ${p.start.year}"
    StatsPeriodKind.MONTHS_3, StatsPeriodKind.MONTHS_6 -> monthsLabel(p.start, p.end, s)
    StatsPeriodKind.YEAR -> "${p.start.year}"
    StatsPeriodKind.CUSTOM -> rangeLabel(p.start, p.end, s)
}

/** "Сентябрь – Ноябрь 2026" or "Октябрь 2025 – Март 2026". */
private fun monthsLabel(start: LocalDate, end: LocalDate, s: Resources): String =
    if (start.year == end.year) "${monthName(start.monthValue, s)} – ${monthName(end.monthValue, s)} ${end.year}"
    else "${monthName(start.monthValue, s)} ${start.year} – ${monthName(end.monthValue, s)} ${end.year}"

/** Compact date range: "1–4 окт", "28 сент – 4 окт", with the year when it is not the current one. */
internal fun rangeLabel(start: LocalDate, end: LocalDate, s: Resources): String {
    val locale = localeFor(s)
    val dm = s.getString(R.string.date_pattern_day_month_short)
    val needYear = start.year != end.year || end.year != LocalDate.now().year
    fun fmt(d: LocalDate, withYear: Boolean) =
        DateTimeFormatter.ofPattern(if (withYear) "$dm yyyy" else dm, locale).format(d).replace(".", "")
    return when {
        start == end -> fmt(start, needYear)
        start.year == end.year && start.month == end.month && start.dayOfMonth == 1 && end == YearMonth.from(end).atEndOfMonth() ->
            "${monthName(start.monthValue, s)} ${start.year}"
        start.year == end.year && start.month == end.month -> {
            // "1–4 окт" / "Oct 1–4": keep the month on the side the pattern puts it.
            val full = fmt(end, needYear)
            val dayFirst = dm.trim().startsWith("d")
            if (dayFirst) "${start.dayOfMonth}–$full" else full.replaceFirst("${end.dayOfMonth}", "${start.dayOfMonth}–${end.dayOfMonth}")
        }
        else -> "${fmt(start, start.year != end.year)} – ${fmt(end, needYear)}"
    }
}

private fun bucketAxisLabel(start: LocalDate, size: StatsBucketSize, s: Resources): String = when (size) {
    StatsBucketSize.DAY -> DateTimeFormatter.ofPattern(s.getString(R.string.date_pattern_day_month_short), localeFor(s)).format(start).replace(".", "")
    StatsBucketSize.WEEK -> DateTimeFormatter.ofPattern(s.getString(R.string.date_pattern_day_month_short), localeFor(s)).format(start).replace(".", "")
    StatsBucketSize.MONTH -> start.month.getDisplayName(TextStyle.SHORT_STANDALONE, localeFor(s)).replace(".", "")
}

private fun bucketTitle(start: LocalDate, size: StatsBucketSize, period: StatsPeriod, s: Resources): String = when (size) {
    StatsBucketSize.DAY -> dayLabel(start, s)
    StatsBucketSize.WEEK -> rangeLabel(maxOf(start, period.start), minOf(start.plusDays(6), period.end), s)
    StatsBucketSize.MONTH -> "${monthName(start.monthValue, s)} ${start.year}"
}
