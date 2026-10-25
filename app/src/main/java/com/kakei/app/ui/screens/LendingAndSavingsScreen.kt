package com.kakei.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.LendingListResponse
import com.kakei.app.data.remote.dto.LendingRecordDto
import com.kakei.app.data.remote.dto.SavingsGoalDto
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.*
import java.math.BigDecimal
import java.time.LocalDate

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
    val s = LocalAppStrings.current
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(openSplitBillInitially) }
    var showSettleAllDialog by remember { mutableStateOf(false) }
    var repayTarget by remember { mutableStateOf<LendingRecordDto?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Summary Header: Owed To Me / Owed By Me / Net Position
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = EmeraldBrand)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(s.owedToMeTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            CurrencyFormatter.format(lendingResponse.totalOwedToMe, baseCurrency),
                            style = MaterialTheme.typography.titleLarge,
                            color = emeraldText()
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.owedByMeTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            CurrencyFormatter.format(lendingResponse.totalOwedByMe, baseCurrency),
                            style = MaterialTheme.typography.titleLarge,
                            color = CoralExpense
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(s.netPositionTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            CurrencyFormatter.format(lendingResponse.netPosition, baseCurrency, showPlus = true),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(s.addLendingButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(
                        onClick = { showSplitDialog = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(s.splitExpenseButton, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    onClick = { showSettleAllDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.settleAllButton, maxLines = 1)
                }
            }
        }

        if (lendingResponse.items.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.Handshake,
                    title = s.pick("Долгов нет", "No debts"),
                    hint = s.pick(
                        "Запишите, кому вы дали или у кого взяли в долг, или разделите общий счёт.",
                        "Record money you lent or borrowed, or split a shared bill."
                    )
                )
            }
        }

        // 2. Lending Records List
        items(lendingResponse.items, key = { it.id }) { item ->
            val accent = if (item.type == "LENT") emeraldText() else CoralExpense
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = accent)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(item.personName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${if (item.type == "LENT") s.lentType else s.borrowedType} · ${s.pick("Срок", "Due")}: ${item.dueDate ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = CurrencyFormatter.format(item.outstandingAmount, item.currency),
                            style = MaterialTheme.typography.titleLarge,
                            color = accent
                        )
                        Text(
                            text = "${s.pick("из", "of")} ${CurrencyFormatter.format(item.principalAmount, item.currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!item.note.isNullOrBlank()) {
                    Text(item.note, style = MaterialTheme.typography.bodyMedium)
                }

                if (!item.isSettled && !item.isForgiven) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { repayTarget = item }) {
                            Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(s.recordRepaymentButton)
                        }
                        TextButton(onClick = { repository.forgiveDebt(item) }) {
                            Text(s.forgiveDebtButton)
                        }
                        IconButton(
                            onClick = {
                                val shareText = s.pick(
                                    "${item.personName}, напоминание из Kakei: остаток долга ${CurrencyFormatter.format(item.outstandingAmount, item.currency)}.",
                                    "Hi ${item.personName}, friendly reminder via Kakei: outstanding balance is ${CurrencyFormatter.format(item.outstandingAmount, item.currency)}."
                                )
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, s.pick("Отправить напоминание", "Send Reminder")))
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = s.pick("Отправить напоминание", "Share reminder"))
                        }
                    }
                } else {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(if (item.isForgiven) s.forgivenBadge else s.settledBadge) }
                    )
                }
            }
        }
    }

    // Create Lending Dialog
    if (showCreateDialog) {
        var person by remember { mutableStateOf("") }
        var lType by remember { mutableStateOf("LENT") }
        var amount by remember { mutableStateOf("15000") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(s.addLendingButton) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = person,
                        onValueChange = { person = it },
                        label = { Text(s.pick("Имя", "Person Name")) },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = lType == "LENT",
                            onClick = { lType = "LENT" },
                            label = { Text(s.lentType) }
                        )
                        FilterChip(
                            selected = lType == "BORROWED",
                            onClick = { lType = "BORROWED" },
                            label = { Text(s.borrowedType) }
                        )
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("${s.amountLabel} ($baseCurrency)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(s.noteLabel) },
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
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(s.cancelButton) }
            }
        )
    }

    // Split Expense Dialog (POST /lending/split-expense)
    if (showSplitDialog) {
        var total by remember { mutableStateOf("30000") }
        var namesCsv by remember { mutableStateOf("Тимур, Алия, Данияр") }
        var includeSelf by remember { mutableStateOf(true) }
        var note by remember { mutableStateOf(s.pick("Общий ужин", "Shared Dinner")) }

        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text(s.splitExpenseButton) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = total,
                        onValueChange = { total = it },
                        label = { Text(s.pick("Сумма счёта ($baseCurrency)", "Total Bill ($baseCurrency)")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = namesCsv,
                        onValueChange = { namesCsv = it },
                        label = { Text(s.pick("Участники (через запятую)", "Participants (comma separated)")) }
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(s.noteLabel) },
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeSelf, onCheckedChange = { includeSelf = it })
                        Text(s.pick("Учесть и мою долю", "Include my own share"))
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
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitDialog = false }) { Text(s.cancelButton) }
            }
        )
    }

    // Settle All Dialog (POST /lending/settle-all)
    if (showSettleAllDialog) {
        val distinctPeople = lendingResponse.items
            .filter { !it.isSettled && !it.isForgiven }
            .map { it.personName }
            .distinct()
        var chosenPerson by remember { mutableStateOf(distinctPeople.firstOrNull().orEmpty()) }

        AlertDialog(
            onDismissRequest = { showSettleAllDialog = false },
            title = { Text(s.settleAllButton) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = chosenPerson,
                        onValueChange = { chosenPerson = it },
                        label = { Text(s.pick("Имя", "Person Name")) },
                        singleLine = true
                    )
                    distinctPeople.forEach { p ->
                        AssistChip(onClick = { chosenPerson = p }, label = { Text(p) })
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
                    Text(s.confirmCategory)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettleAllDialog = false }) { Text(s.cancelButton) }
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
            title = { Text("${s.recordRepaymentButton}: ${target.personName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = repayAmt,
                        onValueChange = { repayAmt = it },
                        label = { Text(s.pick("Сумма погашения (${target.currency})", "Repayment Amount (${target.currency})")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = repayNote,
                        onValueChange = { repayNote = it },
                        label = { Text(s.noteLabel) },
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
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { repayTarget = null }) { Text(s.cancelButton) }
            }
        )
    }
}

/**
 * Tab 4: Savings Goals (Piggybanks) with Target Pacing & Quick Steppers (+500 / +1000 / +2000 / +5000).
 */
@Composable
fun SavingsScreen(
    baseCurrency: String,
    goals: List<SavingsGoalDto>,
    repository: KakeiRepository
) {
    val s = LocalAppStrings.current
    var showCreateGoalDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    s.savingsGoalsTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                Button(onClick = { showCreateGoalDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s.createGoalButton)
                }
            }
        }

        if (goals.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Default.Savings,
                    title = s.pick("Целей пока нет", "No goals yet"),
                    hint = s.pick(
                        "Создайте цель — приложение рассчитает, сколько откладывать в день и в месяц.",
                        "Create a goal to see how much to put aside per day and per month."
                    )
                )
            }
        }

        items(goals, key = { it.id }) { goal ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedGlassCard(accentColor = EmeraldBrand)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(goal.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${s.pick("Срок", "Deadline")}: ${goal.deadlineDate ?: s.pick("без срока", "Flexible")} · ${goal.progressPercentage}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { repository.deleteSavingsGoal(goal.id) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = s.deleteButton)
                    }
                }

                LinearProgressIndicator(
                    progress = { (goal.progressPercentage.toFloat() / 100f).coerceIn(0f, 1f) },
                    color = EmeraldBrand,
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
                        "${s.currentSavedLabel}: ${CurrencyFormatter.format(goal.currentAmount, goal.currency)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = EmeraldLight
                    )
                    Text(
                        "${s.targetAmountLabel}: ${CurrencyFormatter.format(goal.targetAmount, goal.currency)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (goal.requiredDailyContribution != null && goal.requiredMonthlyContribution != null) {
                    Text(
                        text = "${s.dailyPaceLabel}: ${CurrencyFormatter.format(goal.requiredDailyContribution, goal.currency)} · ${s.monthlyPaceLabel}: ${CurrencyFormatter.format(goal.requiredMonthlyContribution, goal.currency)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Quick Steppers (+500 / +1000 / +2000 / +5000)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(500, 1000, 2000, 5000).forEach { step ->
                        FilledTonalButton(
                            onClick = {
                                repository.addSavingsContribution(goal, BigDecimal.valueOf(step.toLong()))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            Text("+$step", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    if (showCreateGoalDialog) {
        var title by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("500000") }
        var days by remember { mutableStateOf("90") }

        AlertDialog(
            onDismissRequest = { showCreateGoalDialog = false },
            title = { Text(s.createGoalButton) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(s.pick("Название цели", "Goal Title")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text(s.pick("Сумма цели ($baseCurrency)", "Target Amount ($baseCurrency)")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it },
                        label = { Text(s.pick("Срок, дней", "Target Days")) },
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
                    Text(s.saveButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGoalDialog = false }) { Text(s.cancelButton) }
            }
        )
    }
}
