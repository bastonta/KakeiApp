package com.kakei.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.ConvertedTotal
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Records shown on Home; the full history lives on the transactions page. */
private const val RECENT_RECORDS_LIMIT = 15

/**
 * Tab 1: Home & Ledger Screen combining:
 * - Greeting header with profile avatar, search and review notifications
 * - Net Worth hero card with 30-day balance trend, forecast and top budget burn rate
 * - Instant Captured Alert card with one-tap categorization
 * - Horizontal multi-currency accounts carousel (doubles as the ledger account filter) + Create Account dialog
 * - `NeedsReview` queue banner and the day-grouped ledger with splits, labels and clearing badges
 */
@Composable
fun HomeScreen(
    settings: AppSettingsState,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    labels: List<LabelDto>,
    transactions: List<TransactionDto>,
    budgets: List<BudgetEnvelopeDto>,
    forecast: CashFlowForecastDto,
    instantCapturedRecord: TransactionDto?,
    repository: KakeiRepository,
    onOpenProfile: () -> Unit,
    onOpenTransactions: (accountId: String?, focusSearch: Boolean) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val baseCurrency = settings.profile.defaultCurrency
    val langCode = settings.language.code

    var selectedAccountFilter by remember { mutableStateOf<String?>(null) }
    var showCreateAccountDialog by remember { mutableStateOf(false) }
    var editAccount by remember { mutableStateOf<AccountDto?>(null) }
    var reviewTargetRecord by remember { mutableStateOf<TransactionDto?>(null) }
    var showNoNotifications by remember { mutableStateOf(false) }

    val activeAccounts = accounts.filter { !it.isArchived }
    val rateBook by repository.rateBook.collectAsState()
    // Balances without a known exchange rate are shown in their own currency, never converted with a guess.
    val netWorthTotal = remember(activeAccounts, rateBook, baseCurrency) {
        ConvertedTotal.of(
            activeAccounts.filter { !it.isExcludedFromStats }.map { it.currentBalance to it.currency },
            baseCurrency,
            rateBook
        )
    }
    val netWorth = netWorthTotal.total
    val netWorthUnconverted = netWorthTotal.unconverted.entries.joinToString(" + ") { (cur, amt) ->
        CurrencyFormatter.format(amt, cur)
    }

    // Net income − expense per day in the base currency (transfers excluded); drives the
    // month delta and the hero sparkline. Records without a known rate are left out.
    val dailyNet = remember(transactions, rateBook, baseCurrency) {
        val map = mutableMapOf<LocalDate, BigDecimal>()
        transactions.forEach { tx ->
            if (tx.type == "TRANSFER") return@forEach
            val date = bookingDateTime(tx.bookingDate)?.toLocalDate() ?: return@forEach
            val converted = CurrencyFormatter.convert(tx.amount.abs(), tx.currency, baseCurrency, rateBook)
                ?: return@forEach
            val signed = if (tx.type == "INCOME") converted else converted.negate()
            map[date] = (map[date] ?: BigDecimal.ZERO) + signed
        }
        map
    }
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    val monthNet = remember(dailyNet) {
        dailyNet.filterKeys { YearMonth.from(it) == currentMonth }.values.fold(BigDecimal.ZERO, BigDecimal::add)
    }
    val monthStartWorth = netWorth - monthNet
    val monthDeltaPercent = if (monthStartWorth.signum() > 0 && monthNet.signum() != 0) {
        monthNet.multiply(BigDecimal(100)).divide(monthStartWorth, 1, RoundingMode.HALF_UP)
    } else null
    // Walk back from today's net worth to reconstruct the last 30 days of balances.
    val trend = remember(dailyNet, netWorth) {
        val points = ArrayList<BigDecimal>(31)
        var running = netWorth
        for (offset in 0..30) {
            points.add(running)
            running -= dailyNet[today.minusDays(offset.toLong())] ?: BigDecimal.ZERO
        }
        points.reversed()
    }

    val needsReviewItems = remember(transactions) { transactions.filter { it.needsReview } }
    val accountTransactions = remember(transactions, selectedAccountFilter) {
        if (selectedAccountFilter == null) transactions else transactions.filter { it.accountId == selectedAccountFilter }
    }
    // Home only shows the latest records; the full, filterable history is a separate page.
    val recordsByDay = remember(accountTransactions) {
        accountTransactions.take(RECENT_RECORDS_LIMIT)
            .groupBy { bookingDateTime(it.bookingDate)?.toLocalDate() }.toList()
    }

    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val labelMap = remember(labels) { labels.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Greeting header: avatar (opens Profile), month, search and notifications
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = settings.profile.name,
                    color = c.mint,
                    contentColor = c.mintInk,
                    size = 44.dp,
                    modifier = Modifier.clickable(onClick = onOpenProfile)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${monthName(today.monthValue, s)} ${today.year}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted
                    )
                    Text(
                        text = greeting(s),
                        style = MaterialTheme.typography.headlineMedium,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CircleIconButton(
                    icon = Icons.Outlined.Search,
                    contentDescription = s.searchPayeeHint,
                    onClick = { onOpenTransactions(selectedAccountFilter, true) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    CircleIconButton(
                        icon = Icons.Outlined.Notifications,
                        contentDescription = s.needsReviewBanner,
                        badge = needsReviewItems.isNotEmpty(),
                        onClick = {
                            val first = needsReviewItems.firstOrNull()
                            if (first != null) reviewTargetRecord = first else showNoNotifications = true
                        }
                    )
                    DropdownMenu(expanded = showNoNotifications, onDismissRequest = { showNoNotifications = false }) {
                        DropdownMenuItem(
                            text = { Text(s.pick("Новых уведомлений нет", "No new notifications")) },
                            onClick = { showNoNotifications = false }
                        )
                    }
                }
            }
        }

        // 2. Net Worth hero card
        item {
            NetWorthHeroCard(
                netWorth = netWorth,
                baseCurrency = baseCurrency,
                unconverted = netWorthUnconverted,
                monthNet = monthNet,
                monthDeltaPercent = monthDeltaPercent,
                trend = trend,
                forecast = forecast,
                topBudget = budgets.firstOrNull()
            )
        }

        // 3. Instant captured alert (InstantPopupService UX, shown in-app)
        if (instantCapturedRecord != null) {
            item {
                InstantCaptureCard(
                    record = instantCapturedRecord,
                    categories = categories,
                    langCode = langCode,
                    onCategory = { cat ->
                        repository.confirmReview(record = instantCapturedRecord, categoryId = cat.id, createRule = true)
                    },
                    onDismiss = { repository.dismissInstantCapturedBanner() }
                )
            }
        }

        // 4. Accounts carousel (also filters the ledger below)
        item {
            SectionHeader(
                title = s.accountsTitle,
                action = s.pick("+ Добавить", "+ Add"),
                actionColor = c.mint,
                onAction = { showCreateAccountDialog = true }
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    AccountCard(
                        tile = { LetterTile("Σ", c.mint, contentColor = c.mintInk) },
                        caption = s.pick("Активных: ${activeAccounts.size}", "${activeAccounts.size} active"),
                        name = s.allAccountsFilter,
                        amount = formatCompactMoney(netWorth, baseCurrency, s),
                        selected = selectedAccountFilter == null,
                        onClick = { selectedAccountFilter = null }
                    )
                }
                items(activeAccounts, key = { it.id }) { acc ->
                    val isSelected = selectedAccountFilter == acc.id
                    AccountCard(
                        tile = { LetterTile(acc.name.take(1).uppercase(), accentForName(acc.name)) },
                        // Permission is only worth showing on accounts shared with the user.
                        caption = listOfNotNull(
                            s.accountType(acc.accountType),
                            acc.permissionLevel.takeIf { it != "ADMIN" }?.let { s.permissionLevel(it) }
                        ).joinToString(" · "),
                        name = acc.name,
                        amount = formatMoney(acc.currentBalance, acc.currency),
                        selected = isSelected,
                        onClick = { selectedAccountFilter = if (isSelected) null else acc.id },
                        // Only account admins may rename it or change its opening balance.
                        onLongClick = { editAccount = acc }.takeIf { acc.permissionLevel == "ADMIN" }
                    )
                }
            }
        }

        // 5. NeedsReview queue banner
        if (needsReviewItems.isNotEmpty()) {
            item {
                val n = needsReviewItems.size.toLong()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kakeiCard(cornerRadius = 16.dp, color = c.amber.copy(alpha = 0.10f), borderColor = c.amber.copy(alpha = 0.25f))
                        .clickable { reviewTargetRecord = needsReviewItems.first() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Sell, contentDescription = null, tint = c.amber, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = s.pick(
                            "$n ${ruPlural(n, "операция ждёт", "операции ждут", "операций ждут")} категорию",
                            "$n ${if (n == 1L) "record needs" else "records need"} a category"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                        modifier = Modifier.weight(1f)
                    )
                    Text(s.pick("Разобрать", "Review"), style = MaterialTheme.typography.labelLarge, color = c.amber)
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = c.amber,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 6. Day-grouped ledger (latest records)
        item {
            SectionHeader(
                title = s.recentTransactions,
                action = s.pick("Все", "All").takeIf { accountTransactions.isNotEmpty() },
                onAction = { onOpenTransactions(selectedAccountFilter, false) }
            )
        }

        if (recordsByDay.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = s.pick("Операций пока нет", "No records yet"),
                    hint = s.pick(
                        "Нажмите «+», чтобы добавить расход или доход, или включите автозахват SMS в профиле.",
                        "Tap \"+\" to add an expense or income, or enable SMS capture in your profile."
                    )
                )
            }
        } else {
            // One lazy item per day: composing the whole history inside a single item froze the UI.
            items(recordsByDay, key = { (date, _) -> "day-${date ?: "none"}" }) { (date, dayRecords) ->
                GroupCard {
                    val dayExpense = remember(dayRecords, rateBook, baseCurrency) {
                        dayRecords.filter { it.type == "EXPENSE" }.mapNotNull {
                            CurrencyFormatter.convert(it.amount.abs(), it.currency, baseCurrency, rateBook)
                        }.fold(BigDecimal.ZERO, BigDecimal::add)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = date?.let { dayLabel(it, s) } ?: "—",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.muted
                        )
                        if (dayExpense.signum() > 0) {
                            Text(
                                text = "−" + CurrencyFormatter.format(dayExpense, baseCurrency),
                                style = MaterialTheme.typography.labelLarge,
                                color = c.muted
                            )
                        }
                    }
                    dayRecords.forEach { tx ->
                        LedgerRecordItem(
                            tx = tx,
                            category = tx.categoryId?.let { categoryMap[it] },
                            account = accountMap[tx.accountId],
                            labels = tx.labelIds.mapNotNull { labelMap[it] },
                            langCode = langCode,
                            onReview = { reviewTargetRecord = it },
                            onDelete = { repository.deleteRecord(it) }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }

    // Create Account Dialog
    if (showCreateAccountDialog) {
        var accName by remember { mutableStateOf("") }
        var accType by remember { mutableStateOf("CHECKING") }
        var accCurrency by remember { mutableStateOf(baseCurrency) }
        var accBalance by remember { mutableStateOf("0") }
        var excluded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreateAccountDialog = false },
            title = { Text(s.addAccount) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = accName,
                        onValueChange = { accName = it },
                        label = { Text(s.pick("Название счёта", "Account Name")) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("CASH", "CHECKING", "CREDIT", "DEBT", "INVESTMENT").forEach { t ->
                            KakeiChip(text = s.accountType(t), selected = accType == t, onClick = { accType = t })
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CurrencyFormatter.supportedCurrencies.forEach { cur ->
                            KakeiChip(text = cur, selected = accCurrency == cur, onClick = { accCurrency = cur })
                        }
                    }
                    OutlinedTextField(
                        value = accBalance,
                        onValueChange = { accBalance = it },
                        label = { Text(s.pick("Начальный баланс", "Initial Balance")) },
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = excluded, onCheckedChange = { excluded = it })
                        Text(s.pick("Не учитывать в чистом капитале", "Exclude from Net Worth stats"))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bal = accBalance.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (accName.isNotBlank()) {
                            repository.createAccount(accName, accType, accCurrency, bal, excluded)
                            showCreateAccountDialog = false
                        }
                    }
                ) {
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAccountDialog = false }) {
                    Text(s.cancelButton)
                }
            }
        )
    }

    // Edit Account Dialog (long press on an account card): name and balance, the latter either
    // through a correction record or directly on the account (shifting its opening balance).
    editAccount?.let { acc ->
        var accName by remember(acc.id) { mutableStateOf(acc.name) }
        var accBalance by remember(acc.id) { mutableStateOf(acc.currentBalance.stripTrailingZeros().toPlainString()) }
        var viaRecord by remember(acc.id) { mutableStateOf(true) }
        var excluded by remember(acc.id) { mutableStateOf(acc.isExcludedFromStats) }
        val targetBalance = accBalance.replace(',', '.').replace(" ", "").toBigDecimalOrNull()
        val delta = targetBalance?.let { it - acc.currentBalance }

        AlertDialog(
            onDismissRequest = { editAccount = null },
            title = { Text(s.pick("Изменить счёт", "Edit account")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = accName,
                        onValueChange = { accName = it },
                        label = { Text(s.pick("Название счёта", "Account Name")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = accBalance,
                        onValueChange = { accBalance = it },
                        label = { Text(s.pick("Текущий баланс", "Current balance") + ", ${acc.currency}") },
                        isError = targetBalance == null,
                        singleLine = true
                    )
                    KakeiSegmented(
                        options = listOf(s.pick("Операцией", "Via record"), s.pick("В счёте", "On account")),
                        selectedIndex = if (viaRecord) 0 else 1,
                        onSelect = { viaRecord = it == 0 }
                    )
                    val diff = delta?.takeIf { it.signum() != 0 }?.let {
                        (if (it.signum() > 0) "+" else "−") + CurrencyFormatter.format(it.abs(), acc.currency)
                    }
                    Text(
                        text = when {
                            diff == null -> s.pick("Баланс не меняется.", "The balance stays the same.")
                            viaRecord -> s.pick(
                                "Будет добавлена операция «Корректировка баланса» на $diff. Она войдёт в доходы/расходы.",
                                "A \"Balance adjustment\" record of $diff will be added. It counts as income/expense."
                            )
                            else -> s.pick(
                                "Начальный баланс изменится на $diff, без операции и без влияния на статистику.",
                                "The opening balance changes by $diff, with no record and no effect on statistics."
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = excluded, onCheckedChange = { excluded = it })
                        Text(s.pick("Не учитывать в чистом капитале", "Exclude from Net Worth stats"))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = targetBalance != null && accName.isNotBlank(),
                    onClick = {
                        val d = delta ?: BigDecimal.ZERO
                        if (viaRecord) {
                            repository.updateAccount(acc.id, accName, acc.initialBalance, excluded)
                            repository.adjustBalanceWithRecord(acc.id, acc.currentBalance + d)
                        } else {
                            repository.updateAccount(acc.id, accName, acc.initialBalance + d, excluded)
                        }
                        editAccount = null
                    }
                ) {
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { editAccount = null }) {
                    Text(s.cancelButton)
                }
            }
        )
    }

    // Confirm Review Dialog (POST /records/{id}/review)
    reviewTargetRecord?.let { item ->
        ReviewRecordDialog(
            record = item,
            categories = categories,
            langCode = langCode,
            onConfirm = { catId, createRule -> repository.confirmReview(item, catId, createRule) },
            onDismiss = { reviewTargetRecord = null }
        )
    }
}

private fun greeting(s: com.kakei.app.core.i18n.AppStrings): String = when (LocalTime.now().hour) {
    in 5..11 -> s.pick("Доброе утро", "Good morning")
    in 12..17 -> s.pick("Добрый день", "Good afternoon")
    in 18..22 -> s.pick("Добрый вечер", "Good evening")
    else -> s.pick("Доброй ночи", "Good night")
}

@Composable
private fun NetWorthHeroCard(
    netWorth: BigDecimal,
    baseCurrency: String,
    unconverted: String,
    monthNet: BigDecimal,
    monthDeltaPercent: BigDecimal?,
    trend: List<BigDecimal>,
    forecast: CashFlowForecastDto,
    topBudget: BudgetEnvelopeDto?
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val onHero = Color.White
    val onHeroMuted = Color.White.copy(alpha = 0.72f)
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(c.heroA, c.heroB), start = Offset.Zero, end = Offset.Infinite))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.netWorthTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = onHeroMuted,
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Language, contentDescription = null, tint = onHero, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(baseCurrency, style = MaterialTheme.typography.labelMedium, color = onHero)
            }
        }
        Text(
            text = formatMoney(netWorth, baseCurrency),
            style = MaterialTheme.typography.displayLarge,
            color = onHero,
            maxLines = 1
        )
        if (unconverted.isNotEmpty()) {
            Text(
                text = "+ $unconverted · ${s.fxRateUnavailable}",
                style = MaterialTheme.typography.bodySmall,
                color = onHeroMuted
            )
        }
        if (monthNet.signum() != 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (monthDeltaPercent != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (monthNet.signum() >= 0) Icons.Outlined.NorthEast else Icons.Outlined.SouthEast,
                            contentDescription = null,
                            tint = onHero,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = (if (monthDeltaPercent.signum() > 0) "+" else "") +
                                monthDeltaPercent.stripTrailingZeros().toPlainString().replace('.', ',') + "%",
                            style = MaterialTheme.typography.labelLarge,
                            color = onHero
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "${formatMoney(monthNet, baseCurrency, showPlus = true)} ${s.pick("за", "in")} " +
                        monthName(LocalDate.now().monthValue, s).lowercase(localeFor(s)),
                    style = MaterialTheme.typography.bodySmall,
                    color = onHeroMuted
                )
            }
        }

        Sparkline(
            points = trend,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroMiniTile(
                caption = s.pick("Прогноз · 30 дней", "Forecast · 30 days"),
                value = formatMoney(forecast.forecastedEndBalance, baseCurrency),
                valueColor = if (forecast.liquidityDeficitAlert) Color(0xFFFFB4A9) else onHero,
                modifier = Modifier.weight(1f)
            )
            if (topBudget != null) {
                HeroMiniTile(
                    caption = "${topBudget.name} · ${topBudget.usagePercentage}%",
                    value = "${formatMoney(topBudget.dailyBurnRateLimit, topBudget.currency)} ${s.pick("/ день", "/ day")}",
                    valueColor = onHero,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HeroMiniTile(caption: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1)
    }
}

/** Smooth balance trend line; values are only normalized to pixels, never used as money. */
@Composable
private fun Sparkline(points: List<BigDecimal>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val min = points.minOrNull() ?: return@Canvas
        val max = points.maxOrNull() ?: return@Canvas
        val range = max - min
        val stroke = 2.dp.toPx()
        val usable = size.height - stroke * 2
        fun y(v: BigDecimal): Float = if (range.signum() == 0) {
            size.height / 2f
        } else {
            stroke + usable * (1f - (v - min).divide(range, 6, RoundingMode.HALF_UP).toFloat())
        }
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            if (i == 0) {
                path.moveTo(x, y(v))
            } else {
                val prevX = (i - 1) * stepX
                val prevY = y(points[i - 1])
                val midX = (prevX + x) / 2f
                path.cubicTo(midX, prevY, midX, y(v), x, y(v))
            }
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun AccountCard(
    tile: @Composable () -> Unit,
    caption: String,
    name: String,
    amount: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val c = KakeiTheme.colors
    Column(
        modifier = Modifier
            .width(172.dp)
            .kakeiCard(
                cornerRadius = 20.dp,
                color = if (selected) c.mint.copy(alpha = 0.07f).compositeOver(c.surf) else c.surf,
                borderColor = if (selected) c.mint else c.line,
                borderWidth = if (selected) 1.5.dp else 1.dp
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            tile()
            Spacer(modifier = Modifier.weight(1f))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            amount,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InstantCaptureCard(
    record: TransactionDto,
    categories: List<CategoryDto>,
    langCode: String,
    onCategory: (CategoryDto) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(color = c.amber.copy(alpha = 0.08f), borderColor = c.amber.copy(alpha = 0.45f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = Icons.Outlined.Bolt, color = c.amber, size = 36.dp, iconSize = 18.dp, cornerRadius = 10.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.pick("Новая операция", "New transaction"),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text
                )
                Text(
                    text = "${record.payee ?: s.pick("Банковское уведомление", "Bank alert")} · ${s.pick("только что", "just now")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(signedRecordAmount(record), style = MaterialTheme.typography.titleLarge, color = c.text)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = s.dismissButton, tint = c.muted, modifier = Modifier.size(18.dp))
            }
        }
        CategoryPickerRow(
            categories = categories,
            selectedId = record.categoryId.takeIf { !record.needsReview },
            langCode = langCode,
            onSelect = onCategory
        )
    }
}
