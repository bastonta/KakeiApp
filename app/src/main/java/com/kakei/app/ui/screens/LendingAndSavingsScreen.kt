package com.kakei.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.R
import com.kakei.app.core.i18n.days
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.LendingListResponse
import com.kakei.app.data.remote.dto.LendingRecordDto
import com.kakei.app.data.remote.dto.SavingsGoalDto
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Goal color stored by older clients for every goal; such goals get a per-title accent instead. */
private const val LEGACY_DEFAULT_GOAL_COLOR = "#30A46C"

/**
 * Tab 3: Lending, Partial Repayments, Debt Forgiveness, Split Bill (2..20 people) & Settle All.
 */
@Composable
fun LendingScreen(
    baseCurrency: String,
    lendingResponse: LendingListResponse,
    repository: KakeiRepository,
    openSplitBillInitially: Boolean = false
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    val context = LocalContext.current

    var createType by remember { mutableStateOf<String?>(null) }
    var showSplitDialog by remember { mutableStateOf(openSplitBillInitially) }
    var showSettleAllDialog by remember { mutableStateOf(false) }
    var repayTarget by remember { mutableStateOf<LendingRecordDto?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuId by remember { mutableStateOf<String?>(null) }

    val open = lendingResponse.items.filter { !it.isSettled && !it.isForgiven }
    val lent = open.filter { it.type == "LENT" }
    val borrowed = open.filter { it.type == "BORROWED" }
    val closed = lendingResponse.items.filter { it.isSettled || it.isForgiven }
    val shown = when (filter) {
        0 -> lent
        1 -> borrowed
        else -> closed
    }.filter { searchQuery.isBlank() || it.personName.contains(searchQuery, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeader(title = s.getString(R.string.nav_lending)) {
                CircleIconButton(
                    icon = Icons.Outlined.Search,
                    contentDescription = s.getString(R.string.search),
                    onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible) searchQuery = ""
                    }
                )
            }
        }

        if (searchVisible) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(s.getString(R.string.person_name_short)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 1. Summary: Owed To Me / Owed By Me / Net Position
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(c.mint.copy(alpha = 0.10f), c.surf)))
                    .kakeiCard(cornerRadius = 24.dp, color = Color.Transparent, borderColor = c.mint.copy(alpha = 0.45f))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.getString(R.string.owed_to_me_title), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        Text(
                            formatMoney(lendingResponse.totalOwedToMe, baseCurrency),
                            style = MaterialTheme.typography.headlineLarge,
                            color = c.mint,
                            maxLines = 1
                        )
                    }
                    VerticalDivider(color = c.line, modifier = Modifier.padding(horizontal = 14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.getString(R.string.owed_by_me_title), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        Text(
                            formatMoney(lendingResponse.totalOwedByMe, baseCurrency),
                            style = MaterialTheme.typography.headlineLarge,
                            color = c.coral,
                            maxLines = 1
                        )
                    }
                }
                RowDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.getString(R.string.net_position_title), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
                    Text(
                        formatMoney(lendingResponse.netPosition, baseCurrency, showPlus = true),
                        style = MaterialTheme.typography.titleLarge,
                        color = c.text
                    )
                }
            }
        }

        // 2. Quick actions
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Icons.Outlined.NorthEast, c.mint, s.getString(R.string.lend), Modifier.weight(1f)) {
                    createType = "LENT"
                }
                QuickAction(Icons.Outlined.SouthWest, c.coral, s.getString(R.string.borrow), Modifier.weight(1f)) {
                    createType = "BORROWED"
                }
                QuickAction(Icons.Outlined.People, c.cyan, s.getString(R.string.split), Modifier.weight(1f)) {
                    showSplitDialog = true
                }
                QuickAction(Icons.Outlined.Check, c.violet, s.getString(R.string.settle_all), Modifier.weight(1f)) {
                    showSettleAllDialog = true
                }
            }
        }

        item {
            KakeiSegmented(
                options = listOf(
                    "${s.getString(R.string.lent)} · ${lent.size}",
                    "${s.getString(R.string.borrowed)} · ${borrowed.size}",
                    s.getString(R.string.closed)
                ),
                selectedIndex = filter,
                onSelect = { filter = it }
            )
        }

        if (shown.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.Handshake,
                    title = if (lendingResponse.items.isEmpty()) s.getString(R.string.no_debts) else s.getString(R.string.nothing_here),
                    hint = s.getString(R.string.record_money_you_lent_or)
                )
            }
        }

        // 3. Lending Records List
        items(shown, key = { it.id }) { item ->
            val isOpen = !item.isSettled && !item.isForgiven
            val accent = if (item.type == "LENT") c.mint else c.coral
            val due = item.dueDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            val overdueDays = due?.let { ChronoUnit.DAYS.between(it, LocalDate.now()) }?.takeIf { it > 0 && isOpen }
            val progress = if (item.principalAmount.signum() > 0) {
                item.settledAmount.divide(item.principalAmount, 4, RoundingMode.HALF_UP).toFloat()
            } else 0f

            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kakeiCard(cornerRadius = 24.dp)
                        .combinedClickable(onClick = {}, onLongClick = { if (isOpen) menuId = item.id })
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = item.personName, color = accentForName(item.personName), size = 52.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.personName,
                                style = MaterialTheme.typography.titleLarge,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = shortDate(item.dueDate, s)?.let { s.getString(R.string.lending_due, it) } ?: s.getString(R.string.no_due_date),
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.muted
                            )
                            if (overdueDays != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                KakeiBadge(text = s.getString(R.string.overdue, s.days(overdueDays)), color = c.coral)
                            }
                            if (!isOpen) {
                                Spacer(modifier = Modifier.height(4.dp))
                                KakeiBadge(text = if (item.isForgiven) s.getString(R.string.forgiven_badge) else s.getString(R.string.settled_badge), color = c.muted)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                formatMoney(if (isOpen) item.outstandingAmount else item.principalAmount, item.currency),
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (isOpen) accent else c.muted
                            )
                            if (isOpen) {
                                Text(
                                    "${s.getString(R.string.of)} ${CurrencyFormatter.format(item.principalAmount, item.currency)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.faint
                                )
                            }
                        }
                    }

                    if (!item.note.isNullOrBlank()) {
                        Text(item.note, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    }

                    if (isOpen) {
                        KakeiProgressBar(progress = progress, color = accent, height = 6.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KakeiButton(
                                text = s.getString(R.string.record_repayment_button),
                                icon = Icons.Outlined.Download,
                                kind = KakeiButtonKind.TONAL,
                                color = accent,
                                onClick = { repayTarget = item },
                                modifier = Modifier.weight(1f)
                            )
                            KakeiButton(
                                text = s.getString(R.string.remind),
                                icon = Icons.Outlined.IosShare,
                                kind = KakeiButtonKind.OUTLINE,
                                onClick = {
                                    val shareText = s.getString(R.string.hi_friendly_reminder_via_kakei, item.personName, CurrencyFormatter.format(item.outstandingAmount, item.currency))
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, s.getString(R.string.send_reminder)))
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                DropdownMenu(expanded = menuId == item.id, onDismissRequest = { menuId = null }) {
                    DropdownMenuItem(
                        text = { Text(s.getString(R.string.forgive_debt_button)) },
                        leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
                        onClick = {
                            menuId = null
                            repository.forgiveDebt(item)
                        }
                    )
                }
            }
        }

        if (open.isNotEmpty()) {
            item {
                Text(
                    s.getString(R.string.long_press_a_card_to),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // Create Lending Dialog
    val newType = createType
    if (newType != null) {
        var person by remember { mutableStateOf("") }
        var lType by remember { mutableStateOf(newType) }
        var amount by remember { mutableStateOf("15000") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { createType = null },
            title = { Text(s.getString(R.string.add_lending_button)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = person,
                        onValueChange = { person = it },
                        label = { Text(s.getString(R.string.person_name)) },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KakeiChip(text = s.getString(R.string.lent_type), selected = lType == "LENT", color = c.mint, onClick = { lType = "LENT" })
                        KakeiChip(text = s.getString(R.string.borrowed_type), selected = lType == "BORROWED", color = c.coral, onClick = { lType = "BORROWED" })
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("${s.getString(R.string.amount_label)} ($baseCurrency)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(s.getString(R.string.note_label)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (person.isNotBlank() && amt > BigDecimal.ZERO) {
                            repository.createLendingRecord(
                                personName = person,
                                type = lType,
                                principalAmount = amt,
                                currency = baseCurrency,
                                dueDate = LocalDate.now().plusDays(14).toString(),
                                note = note
                            )
                            createType = null
                        }
                    }
                ) {
                    Text(s.getString(R.string.save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { createType = null }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    // Split Expense Dialog (POST /lending/split-expense)
    if (showSplitDialog) {
        var total by remember { mutableStateOf("30000") }
        var namesCsv by remember { mutableStateOf("Тимур, Алия, Данияр") }
        var includeSelf by remember { mutableStateOf(true) }
        var note by remember { mutableStateOf(s.getString(R.string.shared_dinner)) }

        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text(s.getString(R.string.split_expense_button)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = total,
                        onValueChange = { total = it },
                        label = { Text(s.getString(R.string.total_bill, baseCurrency)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = namesCsv,
                        onValueChange = { namesCsv = it },
                        label = { Text(s.getString(R.string.participants_comma_separated)) }
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(s.getString(R.string.note_label)) },
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeSelf, onCheckedChange = { includeSelf = it })
                        Text(s.getString(R.string.include_my_own_share))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = total.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val list = namesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        if (amt > BigDecimal.ZERO && list.isNotEmpty()) {
                            repository.splitExpenseAcrossParticipants(amt, baseCurrency, list, includeSelf, note)
                            showSplitDialog = false
                        }
                    }
                ) {
                    Text(s.getString(R.string.save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitDialog = false }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    // Settle All Dialog (POST /lending/settle-all)
    if (showSettleAllDialog) {
        val distinctPeople = open.map { it.personName }.distinct()
        var chosenPerson by remember { mutableStateOf(distinctPeople.firstOrNull().orEmpty()) }

        AlertDialog(
            onDismissRequest = { showSettleAllDialog = false },
            title = { Text(s.getString(R.string.settle_all_button)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = chosenPerson,
                        onValueChange = { chosenPerson = it },
                        label = { Text(s.getString(R.string.person_name)) },
                        singleLine = true
                    )
                    distinctPeople.forEach { p ->
                        KakeiChip(text = p, selected = chosenPerson == p, onClick = { chosenPerson = p })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (chosenPerson.isNotBlank()) {
                            repository.settleAllForPerson(chosenPerson)
                            showSettleAllDialog = false
                        }
                    }
                ) {
                    Text(s.getString(R.string.confirm_category))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettleAllDialog = false }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    // Partial Repayment Dialog
    val target = repayTarget
    if (target != null) {
        var repayAmt by remember { mutableStateOf(target.outstandingAmount.toPlainString()) }
        var repayNote by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { repayTarget = null },
            title = { Text("${s.getString(R.string.record_repayment_button)}: ${target.personName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = repayAmt,
                        onValueChange = { repayAmt = it },
                        label = { Text(s.getString(R.string.repayment_amount, target.currency)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = repayNote,
                        onValueChange = { repayNote = it },
                        label = { Text(s.getString(R.string.note_label)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = repayAmt.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (amt > BigDecimal.ZERO) {
                            repository.recordRepayment(target, amt, repayNote)
                            repayTarget = null
                        }
                    }
                ) {
                    Text(s.getString(R.string.save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { repayTarget = null }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconTile(icon = icon, color = color, size = 64.dp, iconSize = 24.dp, cornerRadius = 20.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = KakeiTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Tab 4: Savings Goals (Piggybanks) with Target Pacing & Quick Steppers.
 */
@Composable
fun SavingsScreen(
    baseCurrency: String,
    goals: List<SavingsGoalDto>,
    repository: KakeiRepository
) {
    val s = LocalResources.current
    val c = KakeiTheme.colors
    var showCreateGoalDialog by remember { mutableStateOf(false) }
    var customTarget by remember { mutableStateOf<SavingsGoalDto?>(null) }
    var menuId by remember { mutableStateOf<String?>(null) }

    val baseGoals = goals.filter { it.currency.equals(baseCurrency, ignoreCase = true) }
    val totalSaved = baseGoals.fold(BigDecimal.ZERO) { acc, g -> acc + g.currentAmount }
    val totalTarget = baseGoals.fold(BigDecimal.ZERO) { acc, g -> acc + g.targetAmount }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(title = s.getString(R.string.nav_goals))
        }

        // Total saved across goals in the base currency
        if (baseGoals.isNotEmpty()) {
            item {
                val percent = if (totalTarget.signum() > 0) {
                    totalSaved.multiply(BigDecimal(100)).divide(totalTarget, 0, RoundingMode.HALF_UP)
                } else BigDecimal.ZERO
                val n = baseGoals.size.toLong()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(c.violet.copy(alpha = 0.14f))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.getString(R.string.total_saved), style = MaterialTheme.typography.bodySmall, color = c.muted)
                        Text(formatMoney(totalSaved, baseCurrency), style = MaterialTheme.typography.headlineLarge, color = c.text, maxLines = 1)
                        Text(
                            "${s.getString(R.string.of)} ${formatMoney(totalTarget, baseCurrency)} " +
                                s.getQuantityString(R.plurals.across_goals, n.toInt(), n),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.muted
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    KakeiBadge(text = "${percent.toPlainString()}%", color = c.violet, large = true)
                }
            }
        }

        if (goals.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.Savings,
                    title = s.getString(R.string.no_goals_yet),
                    hint = s.getString(R.string.create_a_goal_to_see)
                )
            }
        }

        items(goals, key = { it.id }) { goal ->
            val color = if (goal.colorHex.equals(LEGACY_DEFAULT_GOAL_COLOR, ignoreCase = true)) {
                accentForName(goal.title)
            } else {
                parseHexColor(goal.colorHex, c.mint)
            }

            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .kakeiCard()
                        // Tap adds money to the goal; long press opens the menu.
                        .combinedClickable(onClick = { customTarget = goal }, onLongClick = { menuId = goal.id })
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(
                            icon = iconForKey(goal.iconKey),
                            color = color,
                            size = 44.dp,
                            iconSize = 22.dp,
                            cornerRadius = 22.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                goal.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                shortDate(goal.deadlineDate, s, withYear = true)?.let { s.getString(R.string.goal_due, it) }
                                    ?: s.getString(R.string.no_deadline),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.muted,
                                maxLines = 1
                            )
                        }
                        Text(
                            "${goal.progressPercentage.setScale(0, RoundingMode.DOWN).toPlainString()}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                    KakeiProgressBar(progress = goal.progressPercentage.toFloat() / 100f, color = color, height = 8.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.getString(R.string.saved) + formatMoney(goal.currentAmount, goal.currency),
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            s.getString(R.string.target) + formatMoney(goal.targetAmount, goal.currency),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted
                        )
                    }
                    goal.requiredMonthlyContribution?.takeIf { it.signum() > 0 }?.let {
                        Text(
                            s.getString(R.string.put_aside) +
                                "${CurrencyFormatter.format(it, goal.currency)}${s.getString(R.string.mo)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.faint
                        )
                    }
                }
                DropdownMenu(expanded = menuId == goal.id, onDismissRequest = { menuId = null }) {
                    DropdownMenuItem(
                        text = { Text(s.getString(R.string.delete_button), color = c.coral) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.coral) },
                        onClick = {
                            menuId = null
                            repository.deleteSavingsGoal(goal.id)
                        }
                    )
                }
            }
        }
    }
    KakeiButton(
        text = s.getString(R.string.create_goal_button),
        onClick = { showCreateGoalDialog = true },
        icon = Icons.Outlined.Add,
        color = c.violet,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
    )
    }

    if (showCreateGoalDialog) {
        var title by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("500000") }
        var days by remember { mutableStateOf("90") }

        AlertDialog(
            onDismissRequest = { showCreateGoalDialog = false },
            title = { Text(s.getString(R.string.create_goal_button)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(s.getString(R.string.goal_title)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text(s.getString(R.string.target_amount, baseCurrency)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it },
                        label = { Text(s.getString(R.string.target_days)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = target.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val d = days.toLongOrNull() ?: 90L
                        if (title.isNotBlank() && amt > BigDecimal.ZERO) {
                            repository.createSavingsGoal(
                                title = title,
                                targetAmount = amt,
                                currency = baseCurrency,
                                deadlineDate = LocalDate.now().plusDays(d).toString()
                            )
                            showCreateGoalDialog = false
                        }
                    }
                ) {
                    Text(s.getString(R.string.save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGoalDialog = false }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }

    // Custom contribution amount
    val customGoal = customTarget
    if (customGoal != null) {
        var amount by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { customTarget = null },
            title = { Text(customGoal.title) },
            text = {
                // Bigger goals get bigger one-tap steps.
                val steps = if (customGoal.targetAmount >= BigDecimal(200_000)) listOf(5_000L, 10_000L, 20_000L) else listOf(500L, 1_000L, 2_000L)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        steps.forEach { step ->
                            StepButton(
                                text = "+" + CurrencyFormatter.format(BigDecimal.valueOf(step), customGoal.currency).substringBeforeLast(' '),
                                color = c.violet,
                                modifier = Modifier.weight(1f)
                            ) {
                                repository.addSavingsContribution(customGoal, BigDecimal.valueOf(step))
                                customTarget = null
                            }
                        }
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("${s.getString(R.string.amount_label)} (${customGoal.currency})") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amount.trim().replace(" ", "").replace(",", ".").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (amt > BigDecimal.ZERO) {
                            repository.addSavingsContribution(customGoal, amt)
                            customTarget = null
                        }
                    }
                ) {
                    Text(s.getString(R.string.save_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { customTarget = null }) { Text(s.getString(R.string.cancel_button)) }
            }
        )
    }
}

@Composable
private fun StepButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            maxLines = 1
        )
    }
}
