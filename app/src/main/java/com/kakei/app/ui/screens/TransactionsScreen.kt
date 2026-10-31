package com.kakei.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.AppStrings
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.core.util.ExchangeRateBook
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.LabelDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Booking-date period of the transactions page; [CUSTOM] uses a user-picked range. */
enum class LedgerPeriod {
    THIS_MONTH, LAST_MONTH, THREE_MONTHS, THIS_YEAR, ALL_TIME, CUSTOM;

    /** Inclusive booking-date range, or null for no limit (or a custom range not picked yet). */
    fun range(today: LocalDate, custom: ClosedRange<LocalDate>?): ClosedRange<LocalDate>? = when (this) {
        THIS_MONTH -> YearMonth.from(today).let { it.atDay(1)..it.atEndOfMonth() }
        LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1)..it.atEndOfMonth() }
        THREE_MONTHS -> YearMonth.from(today).let { it.minusMonths(2).atDay(1)..it.atEndOfMonth() }
        THIS_YEAR -> today.withDayOfYear(1)..today.withDayOfYear(today.lengthOfYear())
        ALL_TIME -> null
        CUSTOM -> custom
    }
}

/** A record with its booking date parsed once, so filtering does not re-parse timestamps. */
private data class DatedRecord(val tx: TransactionDto, val date: LocalDate?)

/** One row of the flat transactions list: a day header or a record inside that day's card. */
private sealed interface LedgerEntry {
    val key: String

    data class DayHeader(val date: LocalDate?, val expense: BigDecimal) : LedgerEntry {
        override val key = "day-${date ?: "none"}"
    }

    data class Record(val tx: TransactionDto, val isFirst: Boolean, val isLast: Boolean) : LedgerEntry {
        override val key = tx.id
    }
}

/**
 * Full-screen transactions page opened from Home ("All" / search). Unlike the Home ledger it
 * lazily composes every record on its own, and filters by text, type, period (presets or a
 * custom date range), account, categories (a group includes its subcategories), merchant
 * (payee) and the review queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    baseCurrency: String,
    langCode: String,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    labels: List<LabelDto>,
    transactions: List<TransactionDto>,
    repository: KakeiRepository,
    initialAccountId: String? = null,
    focusSearch: Boolean = false,
    onBack: () -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val today = LocalDate.now()
    val rateBook by repository.rateBook.collectAsState()

    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    // A search over the default month would hide older matches, so searching starts from all time.
    var period by remember { mutableStateOf(if (focusSearch) LedgerPeriod.ALL_TIME else LedgerPeriod.THIS_MONTH) }
    var customRange by remember { mutableStateOf<ClosedRange<LocalDate>?>(null) }
    var accountFilter by remember { mutableStateOf(initialAccountId) }
    var categoryFilter by remember { mutableStateOf<Set<String>>(emptySet()) }
    var payeeFilter by remember { mutableStateOf<String?>(null) }
    var reviewOnly by remember { mutableStateOf(false) }

    var showRangePicker by remember { mutableStateOf(false) }
    var showAccountMenu by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showPayeeDialog by remember { mutableStateOf(false) }
    var reviewTarget by remember { mutableStateOf<TransactionDto?>(null) }

    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val labelMap = remember(labels) { labels.associateBy { it.id } }
    val tree = remember(categories, langCode) { CategoryTree.build(categories, langCode) }

    val dated = remember(transactions) {
        transactions.map { DatedRecord(it, bookingDateTime(it.bookingDate)?.toLocalDate()) }
    }
    // Payees ordered by how often they occur, for the merchant picker.
    val payees = remember(transactions) {
        transactions.mapNotNull { it.payee?.trim()?.takeIf(String::isNotEmpty) }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key to it.value }
    }
    // Picking a group also matches its subcategories.
    val effectiveCategories = remember(categoryFilter, tree) {
        categoryFilter.flatMapTo(HashSet()) { id -> listOf(id) + tree.children[id].orEmpty().map { it.id } }
    }
    val range = period.range(today, customRange)

    val filtered = remember(dated, query, typeFilter, range, accountFilter, effectiveCategories, payeeFilter, reviewOnly) {
        val q = query.trim()
        dated.filter { (tx, date) ->
            (typeFilter == null || tx.type == typeFilter) &&
                (accountFilter == null || tx.accountId == accountFilter) &&
                (!reviewOnly || tx.needsReview) &&
                (payeeFilter == null || tx.payee?.trim().equals(payeeFilter, ignoreCase = true)) &&
                (range == null || (date != null && !date.isBefore(range.start) && !date.isAfter(range.endInclusive))) &&
                (effectiveCategories.isEmpty() || tx.categoryId in effectiveCategories ||
                    tx.splits.any { it.categoryId in effectiveCategories }) &&
                (q.isEmpty() || tx.payee?.contains(q, ignoreCase = true) == true ||
                    tx.note?.contains(q, ignoreCase = true) == true)
        }
    }

    // Totals in the base currency; records without a known rate are left out of the sums.
    val totals = remember(filtered, rateBook, baseCurrency) {
        var income = BigDecimal.ZERO
        var expense = BigDecimal.ZERO
        filtered.forEach { (tx, _) ->
            val v = convert(tx, baseCurrency, rateBook) ?: return@forEach
            when (tx.type) {
                "INCOME" -> income += v
                "EXPENSE" -> expense += v
            }
        }
        income to expense
    }

    val entries = remember(filtered, rateBook, baseCurrency) {
        val out = ArrayList<LedgerEntry>(filtered.size + 64)
        filtered.groupBy { it.date }.forEach { (date, day) ->
            val dayExpense = day.filter { it.tx.type == "EXPENSE" }
                .mapNotNull { convert(it.tx, baseCurrency, rateBook) }
                .fold(BigDecimal.ZERO, BigDecimal::add)
            out += LedgerEntry.DayHeader(date, dayExpense)
            day.forEachIndexed { i, r -> out += LedgerEntry.Record(r.tx, i == 0, i == day.lastIndex) }
        }
        out
    }

    val activeFilterCount = listOf(
        typeFilter != null, accountFilter != null, categoryFilter.isNotEmpty(), payeeFilter != null, reviewOnly
    ).count { it }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) runCatching { focusRequester.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = s.pick("Назад", "Back"),
                    onClick = onBack,
                    size = 44.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    s.pick("Операции", "Transactions"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.text,
                    modifier = Modifier.weight(1f)
                )
                if (activeFilterCount > 0 || query.isNotEmpty()) {
                    TextButton(onClick = {
                        query = ""
                        typeFilter = null
                        accountFilter = null
                        categoryFilter = emptySet()
                        payeeFilter = null
                        reviewOnly = false
                    }) {
                        Text(s.pick("Сбросить", "Reset"), color = c.mint)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(s.searchPayeeHint) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = s.pick("Очистить", "Clear"))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Period presets + custom range
        item(key = "period") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LedgerPeriod.entries.forEach { p ->
                    KakeiChip(
                        text = when (p) {
                            LedgerPeriod.THIS_MONTH -> monthName(today.monthValue, s)
                            LedgerPeriod.LAST_MONTH -> monthName(today.minusMonths(1).monthValue, s)
                            LedgerPeriod.THREE_MONTHS -> s.pick("3 месяца", "3 months")
                            LedgerPeriod.THIS_YEAR -> today.year.toString()
                            LedgerPeriod.ALL_TIME -> s.pick("Всё время", "All time")
                            LedgerPeriod.CUSTOM -> customRange?.let { rangeLabel(it, s) } ?: s.pick("Даты…", "Dates…")
                        },
                        icon = Icons.Outlined.CalendarMonth.takeIf { p == LedgerPeriod.CUSTOM },
                        selected = period == p,
                        onClick = {
                            if (p == LedgerPeriod.CUSTOM) showRangePicker = true else period = p
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Type, account, category, merchant, review
        item(key = "filters") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(null, "EXPENSE", "INCOME", "TRANSFER").forEach { t ->
                    KakeiChip(
                        text = t?.let { s.operationType(it) } ?: s.pick("Все типы", "All types"),
                        selected = typeFilter == t,
                        color = when (t) {
                            "INCOME" -> c.mint
                            "TRANSFER" -> c.cyan
                            "EXPENSE" -> c.coral
                            else -> c.mint
                        },
                        onClick = { typeFilter = t }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box {
                    KakeiChip(
                        text = accountFilter?.let { accountMap[it]?.name } ?: s.pick("Все счета", "All accounts"),
                        icon = Icons.Outlined.AccountBalanceWallet,
                        selected = accountFilter != null,
                        onClick = { showAccountMenu = true }
                    )
                    DropdownMenu(expanded = showAccountMenu, onDismissRequest = { showAccountMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(s.allAccountsFilter) },
                            onClick = {
                                accountFilter = null
                                showAccountMenu = false
                            }
                        )
                        accounts.filter { !it.isArchived || it.id == accountFilter }.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text("${acc.name} · ${acc.currency}") },
                                trailingIcon = if (acc.id == accountFilter) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    accountFilter = acc.id
                                    showAccountMenu = false
                                }
                            )
                        }
                    }
                }
                KakeiChip(
                    text = when (categoryFilter.size) {
                        0 -> s.pick("Категории", "Categories")
                        1 -> categoryMap[categoryFilter.first()]?.localizedName(langCode) ?: s.pick("Категория", "Category")
                        else -> s.pick("Категорий: ${categoryFilter.size}", "${categoryFilter.size} categories")
                    },
                    icon = Icons.Outlined.Sell,
                    selected = categoryFilter.isNotEmpty(),
                    onClick = { showCategoryDialog = true }
                )
                KakeiChip(
                    text = payeeFilter ?: s.pick("Мерчант", "Merchant"),
                    icon = Icons.Outlined.Storefront,
                    selected = payeeFilter != null,
                    onClick = { showPayeeDialog = true }
                )
                KakeiChip(
                    text = s.pick("Проверить", "Needs review"),
                    icon = Icons.Outlined.Flag,
                    color = c.amber,
                    selected = reviewOnly,
                    onClick = { reviewOnly = !reviewOnly }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        item(key = "summary") {
            val (income, expense) = totals
            val n = filtered.size.toLong()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .kakeiCard(cornerRadius = 16.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    s.pick("$n ${ruPlural(n, "операция", "операции", "операций")}", "$n ${if (n == 1L) "record" else "records"}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted,
                    modifier = Modifier.weight(1f)
                )
                if (income.signum() > 0) {
                    Text("+" + CurrencyFormatter.format(income, baseCurrency), style = MaterialTheme.typography.labelLarge, color = c.mint)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text("−" + CurrencyFormatter.format(expense, baseCurrency), style = MaterialTheme.typography.labelLarge, color = c.text)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (entries.isEmpty()) {
            item(key = "empty") {
                Spacer(modifier = Modifier.height(8.dp))
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = s.pick("Ничего не найдено", "Nothing found"),
                    hint = s.pick(
                        "Измените период или сбросьте фильтры.",
                        "Change the period or reset the filters."
                    )
                )
            }
        } else {
            // Every record is its own lazy item, so long periods scroll without composing whole days at once.
            items(entries, key = { it.key }, contentType = { it::class }) { entry ->
                when (entry) {
                    is LedgerEntry.DayHeader -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(entry.date?.let { dayLabel(it, s) } ?: "—", style = MaterialTheme.typography.labelLarge, color = c.muted)
                        if (entry.expense.signum() > 0) {
                            Text(
                                "−" + CurrencyFormatter.format(entry.expense, baseCurrency),
                                style = MaterialTheme.typography.labelLarge,
                                color = c.muted
                            )
                        }
                    }
                    is LedgerEntry.Record -> {
                        val corner = 20.dp
                        val shape = RoundedCornerShape(
                            topStart = if (entry.isFirst) corner else 0.dp,
                            topEnd = if (entry.isFirst) corner else 0.dp,
                            bottomStart = if (entry.isLast) corner else 0.dp,
                            bottomEnd = if (entry.isLast) corner else 0.dp
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(c.surf)
                        ) {
                            if (!entry.isFirst) RowDivider(startIndent = 74.dp)
                            LedgerRecordItem(
                                tx = entry.tx,
                                category = entry.tx.categoryId?.let { categoryMap[it] },
                                account = accountMap[entry.tx.accountId],
                                labels = entry.tx.labelIds.mapNotNull { labelMap[it] },
                                langCode = langCode,
                                onReview = { reviewTarget = it },
                                onDelete = { repository.deleteRecord(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRangePicker) {
        val zone = ZoneOffset.UTC
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = customRange?.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = customRange?.endInclusive?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                        val end = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: start
                        if (start != null && end != null) {
                            customRange = start..end
                            period = LedgerPeriod.CUSTOM
                        }
                        showRangePicker = false
                    }
                ) { Text(s.pick("Применить", "Apply")) }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) { Text(s.cancelButton) }
            }
        ) {
            DateRangePicker(
                state = state,
                showModeToggle = true,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showCategoryDialog) {
        var draft by remember { mutableStateOf(categoryFilter) }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text(s.pick("Категории", "Categories")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        s.pick("Можно выбрать несколько. Группа включает подкатегории.", "Pick one or more. A group includes its subcategories."),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted
                    )
                    CategoryPickerRow(
                        categories = categories,
                        selectedId = null,
                        langCode = langCode,
                        isSelected = { it in draft },
                        onSelect = { cat -> draft = if (cat.id in draft) draft - cat.id else draft + cat.id }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    categoryFilter = draft
                    showCategoryDialog = false
                }) { Text(s.pick("Применить", "Apply")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    categoryFilter = emptySet()
                    showCategoryDialog = false
                }) { Text(s.pick("Сбросить", "Reset")) }
            }
        )
    }

    if (showPayeeDialog) {
        var payeeQuery by remember { mutableStateOf("") }
        val shown = remember(payees, payeeQuery) {
            if (payeeQuery.isBlank()) payees else payees.filter { it.first.contains(payeeQuery.trim(), ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showPayeeDialog = false },
            title = { Text(s.pick("Мерчант", "Merchant")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = payeeQuery,
                        onValueChange = { payeeQuery = it },
                        placeholder = { Text(s.pick("Поиск", "Search")) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.first }) { (name, count) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        payeeFilter = name
                                        showPayeeDialog = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (name == payeeFilter) c.mint else c.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = c.faint)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPayeeDialog = false }) { Text(s.cancelButton) }
            },
            dismissButton = {
                if (payeeFilter != null) {
                    TextButton(onClick = {
                        payeeFilter = null
                        showPayeeDialog = false
                    }) { Text(s.pick("Сбросить", "Reset")) }
                }
            }
        )
    }

    reviewTarget?.let { item ->
        ReviewRecordDialog(
            record = item,
            categories = categories,
            langCode = langCode,
            onConfirm = { catId, createRule -> repository.confirmReview(item, catId, createRule) },
            onDismiss = { reviewTarget = null }
        )
    }
}

private fun convert(tx: TransactionDto, baseCurrency: String, rateBook: ExchangeRateBook): BigDecimal? =
    CurrencyFormatter.convert(tx.amount.abs(), tx.currency, baseCurrency, rateBook)

private fun rangeLabel(range: ClosedRange<LocalDate>, s: AppStrings): String {
    val fmt = DateTimeFormatter.ofPattern("dd.MM.yy", localeFor(s))
    return if (range.start == range.endInclusive) fmt.format(range.start)
    else "${fmt.format(range.start)} – ${fmt.format(range.endInclusive)}"
}

/**
 * A ledger record row with its tap / long-press menu (confirm category, delete), shared by the
 * Home ledger and the transactions page. Tapping a record that needs review opens the review.
 */
@Composable
internal fun LedgerRecordItem(
    tx: TransactionDto,
    category: CategoryDto?,
    account: AccountDto?,
    labels: List<LabelDto>,
    langCode: String,
    onReview: (TransactionDto) -> Unit,
    onDelete: (TransactionDto) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val visual = recordVisual(tx, category)
    val catName = category?.localizedName(langCode)
    val userNote = tx.note?.takeIf { it.isNotBlank() && !it.startsWith("[Auto-") }
    Box {
        TransactionRow(
            icon = visual.icon,
            iconColor = visual.color,
            title = tx.payee ?: catName ?: s.operationType(tx.type),
            // Without a payee the category is already the title; don't repeat it.
            subtitle = listOfNotNull(
                catName.takeIf { tx.payee != null } ?: s.operationType(tx.type).takeIf { tx.type == "TRANSFER" },
                account?.name
            ).joinToString(" · "),
            amount = signedRecordAmount(tx),
            amountColor = when (tx.type) {
                "INCOME" -> c.mint
                "TRANSFER" -> c.cyan
                else -> c.text
            },
            trailingCaption = bookingDateTime(tx.bookingDate)?.toLocalTime()
                ?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "",
            badge = when {
                tx.needsReview -> s.pick("Проверить", "Review")
                tx.status == "UNCLEARED" -> s.clearingStatus(tx.status)
                else -> null
            },
            badgeColor = if (tx.needsReview) c.amber else c.muted,
            extra = listOfNotNull(
                userNote,
                s.pick("Частей: ${tx.splits.size}", "${tx.splits.size} splits").takeIf { tx.splits.isNotEmpty() },
                labels.joinToString(" ") { "#${it.name}" }.takeIf { labels.isNotEmpty() }
            ).joinToString(" · ").ifBlank { null },
            modifier = Modifier.combinedClickable(
                onClick = { if (tx.needsReview) onReview(tx) else menuOpen = true },
                onLongClick = { menuOpen = true }
            )
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (tx.type != "TRANSFER") {
                DropdownMenuItem(
                    text = { Text(s.confirmCategory) },
                    leadingIcon = { Icon(Icons.Outlined.Sell, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onReview(tx)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(s.deleteButton, color = c.coral) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.coral) },
                onClick = {
                    menuOpen = false
                    onDelete(tx)
                }
            )
        }
    }
}

/** Confirm Review Dialog (POST /records/{id}/review): pick a category, optionally learn a smart rule. */
@Composable
internal fun ReviewRecordDialog(
    record: TransactionDto,
    categories: List<CategoryDto>,
    langCode: String,
    onConfirm: (categoryId: String, createRule: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalAppStrings.current
    var chosenCategory by remember(record.id) { mutableStateOf(record.categoryId.orEmpty()) }
    var createSmartRule by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.confirmCategory) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${record.payee ?: s.pick("Операция", "Record")} · ${signedRecordAmount(record)}",
                    style = MaterialTheme.typography.titleMedium
                )
                CategoryPickerRow(
                    categories = categories,
                    selectedId = chosenCategory,
                    langCode = langCode,
                    onSelect = { chosenCategory = it.id }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = createSmartRule, onCheckedChange = { createSmartRule = it })
                    Text(s.createSmartRule)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (chosenCategory.isNotBlank()) {
                        onConfirm(chosenCategory, createSmartRule)
                        onDismiss()
                    }
                }
            ) {
                Text(s.confirmCategory)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancelButton)
            }
        }
    )
}
