package com.kakei.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.time.YearMonth

/** Records shown on Home; the full history lives on the transactions page. */
private const val RECENT_RECORDS_LIMIT = 5

/** Window of the "Структура расходов" donut. */
private const val SPEND_WINDOW_DAYS = 30L

/**
 * Tab 1: Home combining:
 * - "Главная" header with search, review notifications and the profile avatar
 * - Net Worth hero card with the month delta and the month's income / expense
 * - Instant Captured Alert card with one-tap categorization
 * - Two-column accounts grid (tap opens the account's records) + Create Account dialog
 * - `NeedsReview` queue banner, 30-day spending structure and the latest records
 */
@Composable
fun HomeScreen(
    settings: AppSettingsState,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    labels: List<LabelDto>,
    transactions: List<TransactionDto>,
    instantCapturedRecord: TransactionDto?,
    repository: KakeiRepository,
    onOpenProfile: () -> Unit,
    onOpenTransactions: (accountId: String?, focusSearch: Boolean) -> Unit,
    onEditRecord: (TransactionDto) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val baseCurrency = settings.profile.defaultCurrency
    val langCode = settings.language.code

    var showCreateAccountDialog by remember { mutableStateOf(false) }
    var editAccount by remember { mutableStateOf<AccountDto?>(null) }
    var showReorderAccountsDialog by remember { mutableStateOf(false) }
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
    // This month's income and expense in the base currency (transfers excluded).
    val (monthIncome, monthExpense) = remember(transactions, rateBook, baseCurrency) {
        var income = BigDecimal.ZERO
        var expense = BigDecimal.ZERO
        transactions.forEach { tx ->
            if (tx.type == "TRANSFER") return@forEach
            val date = bookingDateTime(tx.bookingDate)?.toLocalDate() ?: return@forEach
            if (YearMonth.from(date) != currentMonth) return@forEach
            val converted = CurrencyFormatter.convert(tx.amount.abs(), tx.currency, baseCurrency, rateBook)
                ?: return@forEach
            if (tx.type == "INCOME") income += converted else expense += converted
        }
        income to expense
    }

    // Expenses per top-level category over the last 30 days, and the 30 days before for the delta.
    // Split records count towards their split categories; records without a known rate are left out.
    val (windowSpend, previousWindowTotal) = remember(transactions, categories, rateBook, baseCurrency) {
        val parentOf = categories.associate { it.id to it.parentId }
        fun topLevel(catId: String?): String? = catId?.let { parentOf[it] ?: it }
        val windowStart = today.minusDays(SPEND_WINDOW_DAYS - 1)
        val previousStart = windowStart.minusDays(SPEND_WINDOW_DAYS)
        val totals = linkedMapOf<String?, BigDecimal>()
        var previous = BigDecimal.ZERO
        transactions.forEach { tx ->
            if (tx.type != "EXPENSE") return@forEach
            val date = bookingDateTime(tx.bookingDate)?.toLocalDate() ?: return@forEach
            if (date < previousStart || date > today) return@forEach
            if (date < windowStart) {
                previous += CurrencyFormatter.convert(tx.amount.abs(), tx.currency, baseCurrency, rateBook) ?: BigDecimal.ZERO
                return@forEach
            }
            val parts = if (tx.splits.isNotEmpty()) tx.splits.map { topLevel(it.categoryId) to it.amount } else listOf(topLevel(tx.categoryId) to tx.amount)
            parts.forEach { (catId, amt) ->
                val converted = CurrencyFormatter.convert(amt.abs(), tx.currency, baseCurrency, rateBook) ?: return@forEach
                totals[catId] = (totals[catId] ?: BigDecimal.ZERO) + converted
            }
        }
        totals.entries.sortedByDescending { it.value }.map { it.key to it.value } to previous
    }
    val windowTotal = windowSpend.fold(BigDecimal.ZERO) { acc, p -> acc + p.second }
    val windowDelta = if (previousWindowTotal.signum() > 0 && windowTotal.signum() > 0) {
        (windowTotal - previousWindowTotal).multiply(BigDecimal(100)).divide(previousWindowTotal, 1, RoundingMode.HALF_UP)
    } else null

    val needsReviewItems = remember(transactions) { transactions.filter { it.needsReview } }
    // Home only shows the latest records; the full, filterable history is a separate page.
    val recentRecords = remember(transactions) { transactions.take(RECENT_RECORDS_LIMIT) }

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
        // 1. Header: search, notifications and the profile avatar
        item {
            ScreenHeader(title = s.navHome) {
                CircleIconButton(
                    icon = Icons.Outlined.Search,
                    contentDescription = s.searchPayeeHint,
                    onClick = { onOpenTransactions(null, true) },
                    size = 44.dp
                )
                Box {
                    CircleIconButton(
                        icon = Icons.Outlined.Notifications,
                        contentDescription = s.needsReviewBanner,
                        badge = needsReviewItems.isNotEmpty(),
                        size = 44.dp,
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
                Avatar(
                    name = settings.profile.name,
                    color = c.mint,
                    contentColor = c.mintInk,
                    size = 44.dp,
                    modifier = Modifier.clickable(onClick = onOpenProfile)
                )
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
                monthIncome = monthIncome,
                monthExpense = monthExpense
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

        // 4. Accounts grid; a tap opens the account's records, a long press edits it.
        // One item, so the grid keeps its own 10dp rhythm instead of the list's 16dp gaps.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(
                    title = s.accountsTitle,
                    action = s.pick("+ Добавить", "+ Add"),
                    onAction = { showCreateAccountDialog = true },
                    secondaryAction = s.pick("Порядок", "Order").takeIf { activeAccounts.size > 1 },
                    onSecondaryAction = { showReorderAccountsDialog = true }
                )
                activeAccounts.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { acc ->
                            AccountCard(
                                letter = acc.name.take(1).uppercase(),
                                color = accentForName(acc.name),
                                name = acc.name,
                                amount = formatMoney(acc.currentBalance, acc.currency),
                                negative = acc.currentBalance.signum() < 0,
                                onClick = { onOpenTransactions(acc.id, false) },
                                // Only account admins may rename it or change its opening balance.
                                onLongClick = { editAccount = acc }.takeIf { acc.permissionLevel == "ADMIN" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
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

        // 6. Spending structure over the last 30 days
        if (windowSpend.isNotEmpty()) {
            item {
                CategoryDonutCard(
                    spend = windowSpend,
                    categories = categories,
                    langCode = langCode,
                    currency = baseCurrency,
                    title = s.pick("Структура расходов", "Spending structure"),
                    subtitle = s.pick("Последние 30 дней", "Last 30 days"),
                    delta = windowDelta,
                    deltaSuffix = s.pick("к прошлому", "vs previous")
                )
            }
        }

        // 7. Latest records
        item {
            if (recentRecords.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = s.pick("Операций пока нет", "No records yet"),
                    hint = s.pick(
                        "Нажмите «+», чтобы добавить расход или доход, или включите автозахват SMS в профиле.",
                        "Tap \"+\" to add an expense or income, or enable SMS capture in your profile."
                    )
                )
            } else {
                GroupCard {
                    SectionHeader(
                        title = s.pick("Последние операции", "Recent records"),
                        action = s.pick("Все", "All"),
                        onAction = { onOpenTransactions(null, false) },
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 2.dp)
                    )
                    recentRecords.forEachIndexed { index, tx ->
                        if (index > 0) {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) { RowDivider() }
                        }
                        LedgerRecordItem(
                            tx = tx,
                            category = tx.categoryId?.let { categoryMap[it] },
                            account = accountMap[tx.accountId],
                            labels = tx.labelIds.mapNotNull { labelMap[it] },
                            langCode = langCode,
                            onReview = { reviewTargetRecord = it },
                            onEdit = onEditRecord,
                            onDelete = { repository.deleteRecord(it) },
                            trailingCaption = bookingDateTime(tx.bookingDate)?.toLocalDate()?.let { dayLabel(it, s) }
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

    // Reorder Accounts Dialog: the order applies to Home and every account picker.
    if (showReorderAccountsDialog) {
        ReorderAccountsDialog(
            accounts = activeAccounts,
            onSave = { ids ->
                repository.reorderAccounts(ids)
                showReorderAccountsDialog = false
            },
            onDismiss = { showReorderAccountsDialog = false }
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

@Composable
private fun NetWorthHeroCard(
    netWorth: BigDecimal,
    baseCurrency: String,
    unconverted: String,
    monthNet: BigDecimal,
    monthDeltaPercent: BigDecimal?,
    monthIncome: BigDecimal,
    monthExpense: BigDecimal
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val onHero = Color.White
    val onHeroMuted = Color.White.copy(alpha = 0.75f)
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(c.heroA, c.heroB), start = Offset.Zero, end = Offset.Infinite))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(s.pick("Капитал", "Net worth"), style = MaterialTheme.typography.bodyMedium, color = onHeroMuted)
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
                    KakeiBadge(
                        text = (if (monthDeltaPercent.signum() >= 0) "▲ " else "▼ ") +
                            monthDeltaPercent.abs().stripTrailingZeros().toPlainString().replace('.', ',') + "%",
                        color = onHero
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "${formatMoney(monthNet, baseCurrency, showPlus = true)} ${s.pick("за", "in")} " +
                        monthName(LocalDate.now().monthValue, s).lowercase(localeFor(s)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onHeroMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroMiniTile(
                caption = s.pick("Доходы", "Income"),
                value = formatMoney(monthIncome, baseCurrency, showPlus = true),
                modifier = Modifier.weight(1f)
            )
            HeroMiniTile(
                caption = s.pick("Расходы", "Expenses"),
                value = (if (monthExpense.signum() > 0) "−" else "") + formatMoney(monthExpense, baseCurrency),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HeroMiniTile(caption: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun AccountCard(
    letter: String,
    color: Color,
    name: String,
    amount: String,
    negative: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val c = KakeiTheme.colors
    Column(
        modifier = modifier
            .kakeiCard(cornerRadius = 16.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterTile(letter, color)
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            amount,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (negative) c.coral else c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReorderAccountsDialog(
    accounts: List<AccountDto>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val order = remember { accounts.toMutableStateList() }

    fun move(from: Int, to: Int) {
        if (to in order.indices) order.add(to, order.removeAt(from))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.pick("Порядок счетов", "Account order")) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                order.forEachIndexed { index, acc ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                acc.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatMoney(acc.currentBalance, acc.currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.muted
                            )
                        }
                        IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = s.pick("Выше", "Move up"))
                        }
                        IconButton(onClick = { move(index, index + 1) }, enabled = index < order.lastIndex) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = s.pick("Ниже", "Move down"))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(order.map { it.id }) }) {
                Text(s.saveButton)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancelButton)
            }
        }
    )
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
