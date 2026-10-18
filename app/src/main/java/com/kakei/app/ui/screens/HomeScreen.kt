package com.kakei.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.*
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.*
import java.math.BigDecimal

/**
 * Tab 1: Home & Ledger Screen combining:
 * - Consolidated Net Worth hero card & sync status badge
 * - Wallet by BudgetBakers Horizontal Multi-Currency Accounts Carousel + Create Account dialog
 * - Instant Captured Alert & `NeedsReview` auto-categorization confirmation banner
 * - Searchable & filterable Ledger Transactions list with splits, labels, and clearing badges
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onOpenQuickAdd: () -> Unit
) {
    val s = LocalAppStrings.current
    val baseCurrency = settings.profile.defaultCurrency

    var selectedAccountFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateAccountDialog by remember { mutableStateOf(false) }
    var reviewTargetRecord by remember { mutableStateOf<TransactionDto?>(null) }

    val activeAccounts = accounts.filter { !it.isArchived }
    val netWorth = activeAccounts.filter { !it.isExcludedFromStats }
        .fold(BigDecimal.ZERO) { acc, a ->
            acc + CurrencyFormatter.convert(a.currentBalance, a.currency, baseCurrency)
        }

    val needsReviewItems = transactions.filter { it.needsReview }
    val filteredTransactions = transactions.filter { tx ->
        val matchesAcc = selectedAccountFilter == null || tx.accountId == selectedAccountFilter
        val matchesQuery = searchQuery.isBlank() ||
            (tx.payee?.contains(searchQuery, ignoreCase = true) == true) ||
            (tx.note?.contains(searchQuery, ignoreCase = true) == true)
        matchesAcc && matchesQuery
    }

    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val labelMap = remember(labels) { labels.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Top Consolidated Net Worth Hero Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = EmeraldBrand)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = s.netWorthTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CurrencyFormatter.format(netWorth, baseCurrency),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = {
                                val next = if (settings.language == AppLanguage.RU) AppLanguage.EN else AppLanguage.RU
                                repository.setLanguage(next)
                            },
                            label = { Text(settings.language.code.uppercase()) }
                        )
                        IconButton(
                            onClick = onOpenQuickAdd,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(EmeraldBrand)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = s.quickAddTransaction, tint = Color.White)
                        }
                    }
                }

                // Forecast & Budget mini telemetry pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (s.language == AppLanguage.RU) "Прогноз 30д" else "30d Forecast",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = CurrencyFormatter.format(forecast.forecastedEndBalance, baseCurrency),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (forecast.liquidityDeficitAlert) CoralExpense else emeraldText()
                            )
                        }
                    }
                    val topBudget = budgets.firstOrNull()
                    if (topBudget != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "${topBudget.name} (${topBudget.usagePercentage}%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${s.dailyBurnLimitLabel}: ${CurrencyFormatter.format(topBudget.dailyBurnRateLimit, topBudget.currency)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Instant SMS/Push Popup Overlay Card (InstantPopupService UX)
        if (instantCapturedRecord != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .frostedGlassCard(accentColor = AmberWarning)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = AmberWarning)
                            Text(
                                text = "${instantCapturedRecord.payee ?: "Bank Alert"} · ${CurrencyFormatter.format(instantCapturedRecord.amount, instantCapturedRecord.currency)}",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        IconButton(onClick = { repository.dismissInstantCapturedBanner() }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                    Text(
                        text = instantCapturedRecord.note ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            SuggestionChip(
                                onClick = {
                                    repository.confirmReview(
                                        record = instantCapturedRecord,
                                        categoryId = cat.id,
                                        createRule = true
                                    )
                                },
                                label = { Text("✓ ${cat.localizedName(settings.language.code)}") }
                            )
                        }
                    }
                }
            }
        }

        // 3. Wallet-style Multi-Currency Accounts Carousel
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.accountsTitle, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showCreateAccountDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s.addAccount)
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item {
                    val selected = selectedAccountFilter == null
                    Column(
                        modifier = Modifier
                            .width(165.dp)
                            .frostedGlassCard(
                                cornerRadius = 16.dp,
                                accentColor = if (selected) EmeraldBrand else IndigoAccent
                            )
                            .clickable { selectedAccountFilter = null }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(s.allAccountsFilter, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${activeAccounts.size} active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CurrencyFormatter.format(netWorth, baseCurrency),
                            style = MaterialTheme.typography.titleMedium,
                            color = emeraldText()
                        )
                    }
                }

                items(activeAccounts, key = { it.id }) { acc ->
                    val isSelected = selectedAccountFilter == acc.id
                    Column(
                        modifier = Modifier
                            .width(195.dp)
                            .frostedGlassCard(
                                cornerRadius = 16.dp,
                                accentColor = if (isSelected) EmeraldBrand else IndigoAccent
                            )
                            .clickable {
                                selectedAccountFilter = if (isSelected) null else acc.id
                            }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = acc.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Text(
                                text = acc.accountType,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = CurrencyFormatter.format(acc.currentBalance, acc.currency),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${acc.currency} · ${acc.permissionLevel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "rev #${acc.syncRevision}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 4. NeedsReview Queue Banner (if any unclassified records exist)
        if (needsReviewItems.isNotEmpty()) {
            item {
                Surface(
                    color = AmberWarning.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AmberWarning)
                            Text(
                                text = "${s.needsReviewBanner} (${needsReviewItems.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = AmberWarning
                            )
                        }
                        needsReviewItems.take(3).forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${item.payee ?: "Merchant"} · ${CurrencyFormatter.format(item.amount, item.currency)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { reviewTargetRecord = item }) {
                                    Text(s.confirmCategory)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Search bar & Ledger Transactions Header
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(s.searchPayeeHint) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text(
                text = "${s.recentTransactions} (${filteredTransactions.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        // 6. Transaction Rows
        items(filteredTransactions, key = { it.id }) { tx ->
            val cat = tx.categoryId?.let { categoryMap[it] }
            val acc = accountMap[tx.accountId]
            val txColor = when (tx.type) {
                "INCOME" -> emeraldText()
                "TRANSFER" -> CyanTransfer
                else -> CoralExpense
            }
            val signPrefix = when (tx.type) {
                "INCOME" -> "+"
                "EXPENSE" -> "-"
                else -> "⇄ "
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(cornerRadius = 16.dp, accentColor = txColor)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = tx.payee ?: cat?.localizedName(settings.language.code) ?: tx.type,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = listOfNotNull(
                                acc?.name,
                                cat?.localizedName(settings.language.code),
                                CurrencyFormatter.formatShortDate(tx.bookingDate)
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$signPrefix${CurrencyFormatter.format(tx.amount, tx.currency)}",
                            style = MaterialTheme.typography.titleLarge,
                            color = txColor
                        )
                        Text(
                            text = tx.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!tx.note.isNullOrBlank()) {
                    Text(
                        text = tx.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Splits & Labels badges + Review/Delete actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (tx.splits.isNotEmpty()) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("${tx.splits.size} splits", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        tx.labelIds.mapNotNull { labelMap[it] }.forEach { lbl ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text("#${lbl.name}", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        if (tx.needsReview) {
                            AssistChip(
                                onClick = { reviewTargetRecord = tx },
                                label = { Text("Needs Review", color = AmberWarning) }
                            )
                        }
                    }
                    IconButton(
                        onClick = { repository.deleteRecord(tx) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = s.deleteButton,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
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
                        label = { Text("Account Name") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("CASH", "CHECKING", "CREDIT", "DEBT", "INVESTMENT").forEach { t ->
                            FilterChip(
                                selected = accType == t,
                                onClick = { accType = t },
                                label = { Text(t) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CurrencyFormatter.supportedCurrencies.forEach { c ->
                            FilterChip(
                                selected = accCurrency == c,
                                onClick = { accCurrency = c },
                                label = { Text(c) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = accBalance,
                        onValueChange = { accBalance = it },
                        label = { Text("Initial Balance") },
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = excluded, onCheckedChange = { excluded = it })
                        Text("Exclude from Net Worth stats")
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

    // Confirm Review Dialog (POST /records/{id}/review)
    val reviewItem = reviewTargetRecord
    if (reviewItem != null) {
        var chosenCategory by remember { mutableStateOf(reviewItem.categoryId ?: categories.firstOrNull()?.id.orEmpty()) }
        var createSmartRule by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { reviewTargetRecord = null },
            title = { Text(s.confirmCategory) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${reviewItem.payee ?: "Record"} · ${CurrencyFormatter.format(reviewItem.amount, reviewItem.currency)}")
                    categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosenCategory = cat.id }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = chosenCategory == cat.id,
                                onClick = { chosenCategory = cat.id }
                            )
                            Text(cat.localizedName(settings.language.code))
                        }
                    }
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
                            repository.confirmReview(reviewItem, chosenCategory, createSmartRule)
                            reviewTargetRecord = null
                        }
                    }
                ) {
                    Text(s.confirmCategory)
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewTargetRecord = null }) {
                    Text(s.cancelButton)
                }
            }
        )
    }
}
