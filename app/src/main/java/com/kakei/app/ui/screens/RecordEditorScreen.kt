package com.kakei.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.security.HapticHelper
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.LabelDto
import com.kakei.app.data.remote.dto.SplitDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.ui.components.*
import com.kakei.app.ui.theme.KakeiTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Full-screen New Record page with:
 * - EXPENSE / INCOME / TRANSFER segmented tabs
 * - Built-in expression calculator keypad (`+`, `-`, `×`, `÷`, `⌫`; long-press `⌫` clears), evaluated live
 * - Account and Category (or Target Account) fields in one row, picked from bottom-sheet lists
 * - Payee autocomplete from past records, filling in the payee's last category
 * - Clearing Status (`UNCLEARED`, `CLEARED`, `RECONCILED`) selectors
 * - Multi-category Splits (`SplitDto`) editor
 * - Orthogonal Labels (`LabelDto`) multi-select chips
 */
@Composable
fun RecordEditorScreen(
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    labels: List<LabelDto>,
    /** Past records, the source of payee suggestions and their remembered categories. */
    transactions: List<TransactionDto>,
    langCode: String,
    /** Creates a label inline from the form; the returned label gets selected on this record. */
    onCreateLabel: (name: String, colorHex: String) -> LabelDto,
    /** Effective server rate (target units per 1 source unit), or null when unknown. */
    rateFor: (fromCurrency: String, toCurrency: String) -> BigDecimal?,
    onDismiss: () -> Unit,
    onSave: (
        accountId: String,
        type: String,
        amount: BigDecimal,
        categoryId: String?,
        payee: String?,
        note: String?,
        status: String,
        transferTargetAccountId: String?,
        splits: List<SplitDto>,
        labelIds: List<String>,
        /** Cross-currency transfers: amount credited to the target account, in its currency. */
        targetAmount: BigDecimal?
    ) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val payeeHistory = remember(transactions) { buildPayeeHistory(transactions) }

    var recordType by remember { mutableStateOf("EXPENSE") }
    var expression by remember { mutableStateOf("0") }
    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var targetAccountId by remember {
        mutableStateOf(accounts.drop(1).firstOrNull()?.id ?: accounts.firstOrNull()?.id.orEmpty())
    }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    // Once the user picks a category themselves, typing a known payee no longer overrides it.
    var categoryPickedManually by remember { mutableStateOf(false) }
    var payee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var clearingStatus by remember { mutableStateOf("CLEARED") }
    var selectedLabels by remember { mutableStateOf(setOf<String>()) }
    var splits by remember { mutableStateOf(listOf<SplitDto>()) }
    var newLabelName by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    val selectedAccount = accounts.firstOrNull { it.id == selectedAccountId } ?: accounts.firstOrNull()
    val currency = selectedAccount?.currency ?: "KZT"

    // Cross-currency transfer: the user states what the bank actually credited, or the
    // rate; the other value is derived live from whichever field was edited last.
    val targetAccount = accounts.firstOrNull { it.id == targetAccountId && it.id != selectedAccountId }
    val targetCurrency = targetAccount?.currency
    val isCrossCurrency = recordType == "TRANSFER" && targetCurrency != null &&
        !targetCurrency.equals(currency, ignoreCase = true)
    val sentAmount = evaluateExpression(expression)
    var rateInput by remember(currency, targetCurrency) {
        mutableStateOf(
            targetCurrency?.let { rateFor(currency, it) }?.setScale(6, RoundingMode.HALF_UP)
                ?.stripTrailingZeros()?.toPlainString().orEmpty()
        )
    }
    var receivedInput by remember(currency, targetCurrency) { mutableStateOf("") }
    var receivedIsAnchor by remember(currency, targetCurrency) { mutableStateOf(false) }
    val enteredRate = parseDecimalInput(rateInput)
    val enteredReceived = parseDecimalInput(receivedInput)
    val effectiveReceived: BigDecimal? = if (receivedIsAnchor) {
        enteredReceived
    } else {
        enteredRate?.let { sentAmount.multiply(it).setScale(2, RoundingMode.HALF_UP) }
    }
    val effectiveRate: BigDecimal? = if (receivedIsAnchor) {
        if (enteredReceived != null && sentAmount > BigDecimal.ZERO) {
            enteredReceived.divide(sentAmount, 6, RoundingMode.HALF_UP).stripTrailingZeros()
        } else null
    } else enteredRate
    val crossCurrencyReady = !isCrossCurrency || (effectiveReceived != null && effectiveReceived > BigDecimal.ZERO)

    val accentColor = when (recordType) {
        "INCOME" -> c.mint
        "TRANSFER" -> c.cyan
        else -> c.coral
    }
    val saveLabel = when (recordType) {
        "INCOME" -> s.pick("Сохранить доход", "Save income")
        "TRANSFER" -> s.pick("Сохранить перевод", "Save transfer")
        else -> s.pick("Сохранить расход", "Save expense")
    }
    val types = listOf("EXPENSE" to s.expenseLabel, "INCOME" to s.incomeLabel, "TRANSFER" to s.transferLabel)

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Back & title
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = s.pick("Назад", "Back"),
                    onClick = onDismiss,
                    size = 44.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = s.quickAddTransaction,
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.text,
                    modifier = Modifier.weight(1f)
                )
            }

            // Type Tabs: EXPENSE / INCOME / TRANSFER
            KakeiSegmented(
                options = types.map { it.second },
                selectedIndex = types.indexOfFirst { it.first == recordType },
                onSelect = { index ->
                    HapticHelper.tick(context)
                    recordType = types[index].first
                }
            )

            // Big calculator amount display: the running total, with the raw expression below it
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = CurrencyFormatter.symbolFor(currency),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.faint
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = displayAmount(expression),
                        fontSize = 60.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1.5).sp,
                        color = accentColor,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 60.sp)
                    )
                }
                if (expression.any { it in OPERATORS }) {
                    Text(
                        text = expression
                            .replace("+", " + ")
                            .replace("-", " − ")
                            .replace("×", " × ")
                            .replace("÷", " ÷ ")
                            .replace('.', ','),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = c.faint,
                        maxLines = 1
                    )
                }
            }

            // Account and category (or transfer target) side by side, each picked from a list
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccountPickerField(
                    caption = s.pick("Счёт", "Account"),
                    accounts = accounts.filter { !it.isArchived },
                    selectedId = selectedAccountId,
                    onSelect = { selectedAccountId = it },
                    modifier = Modifier.weight(1f)
                )
                if (recordType == "TRANSFER") {
                    AccountPickerField(
                        caption = s.targetAccountLabel,
                        accounts = accounts.filter { !it.isArchived && it.id != selectedAccountId },
                        selectedId = targetAccount?.id,
                        onSelect = { targetAccountId = it },
                        modifier = Modifier.weight(1f),
                        transferTarget = true
                    )
                } else {
                    CategoryPickerField(
                        categories = categories,
                        selectedId = selectedCategoryId,
                        langCode = langCode,
                        recordType = recordType,
                        onSelect = {
                            selectedCategoryId = it.id
                            categoryPickedManually = true
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (isCrossCurrency) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = if (receivedIsAnchor) receivedInput else effectiveReceived?.toPlainString().orEmpty(),
                        onValueChange = {
                            receivedInput = it
                            receivedIsAnchor = true
                        },
                        label = { Text("${s.transferReceivedLabel} ($targetCurrency)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = if (receivedIsAnchor) effectiveRate?.toPlainString().orEmpty() else rateInput,
                        onValueChange = {
                            rateInput = it
                            receivedIsAnchor = false
                        },
                        label = { Text("${s.transferRateLabel}: 1 $currency = ? $targetCurrency") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!crossCurrencyReady) {
                    Text(
                        text = s.transferRateMissing,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Payee / merchant with suggestions from past records; a known payee fills in its last category
            PayeeAutocompleteField(
                value = payee,
                onValueChange = { text ->
                    payee = text
                    if (!categoryPickedManually) {
                        payeeHistory.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
                            ?.lastCategoryByType?.get(recordType)
                            ?.let { selectedCategoryId = it }
                    }
                },
                history = payeeHistory,
                recordType = recordType,
                categories = categories,
                langCode = langCode,
                onPick = { entry ->
                    payee = entry.name
                    entry.lastCategoryByType[recordType]?.let { selectedCategoryId = it }
                    focusManager.clearFocus()
                }
            )

            // Built-in calculator keypad
            val rows = listOf(
                listOf("7", "8", "9", "÷"),
                listOf("4", "5", "6", "×"),
                listOf("1", "2", "3", "-"),
                listOf(".", "0", "⌫", "+")
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            val isOperator = key in OPERATORS
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isOperator) accentColor.copy(alpha = 0.14f) else c.surf2)
                                    .combinedClickable(
                                        onClick = {
                                            HapticHelper.tick(context)
                                            expression = applyCalculatorKey(expression, key)
                                        },
                                        // Long-press on backspace clears the whole expression.
                                        onLongClick = if (key == "⌫") {
                                            {
                                                HapticHelper.tick(context)
                                                expression = "0"
                                            }
                                        } else null
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (key == "⌫") {
                                    Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = null, tint = c.text)
                                } else {
                                    Text(
                                        text = when (key) {
                                            "." -> ","
                                            "-" -> "−"
                                            else -> key
                                        },
                                        fontSize = if (isOperator) 22.sp else 24.sp,
                                        color = if (isOperator) accentColor else c.text
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Secondary details: note, clearing status, labels, splits
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showDetails = !showDetails }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = s.pick("Заметка, статус, метки, части", "Note, status, labels, splits"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (showDetails) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = c.muted
                )
            }

            if (showDetails) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(s.noteLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Clearing Lifecycle Status: UNCLEARED / CLEARED / RECONCILED
                Text(s.statusLabel, style = MaterialTheme.typography.labelLarge, color = c.muted)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("UNCLEARED", "CLEARED", "RECONCILED").forEach { st ->
                        KakeiChip(
                            text = s.clearingStatus(st),
                            selected = clearingStatus == st,
                            onClick = { clearingStatus = st }
                        )
                    }
                }

                // Orthogonal Labels / Tags: pick existing ones or create a new one inline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.labelsTitle, style = MaterialTheme.typography.labelLarge, color = c.muted)
                    TextButton(onClick = { newLabelName = if (newLabelName == null) "" else null }) {
                        Text(s.pick("+ Добавить метку", "+ Add label"))
                    }
                }

                newLabelName?.let { draft ->
                    val createLabel = {
                        val name = draft.trim().removePrefix("#").trim()
                        if (name.isNotEmpty()) {
                            val existing = labels.firstOrNull { it.name.equals(name, ignoreCase = true) }
                            val label = existing ?: onCreateLabel(name, LABEL_COLORS[labels.size % LABEL_COLORS.size])
                            selectedLabels = selectedLabels + label.id
                            newLabelName = null
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { newLabelName = it },
                        label = { Text(s.pick("Новая метка", "New label")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { createLabel() }),
                        trailingIcon = {
                            IconButton(onClick = { createLabel() }, enabled = draft.isNotBlank()) {
                                Icon(Icons.Outlined.Check, contentDescription = s.saveButton)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (labels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        labels.forEach { lbl ->
                            val isSelected = selectedLabels.contains(lbl.id)
                            KakeiChip(
                                text = "#${lbl.name}",
                                selected = isSelected,
                                color = parseHexColor(lbl.colorHex, c.violet),
                                onClick = {
                                    selectedLabels = if (isSelected) selectedLabels - lbl.id else selectedLabels + lbl.id
                                }
                            )
                        }
                    }
                }

                // Splits Builder
                if (recordType != "TRANSFER") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.splitsTitle, style = MaterialTheme.typography.labelLarge, color = c.muted)
                        TextButton(
                            onClick = {
                                val currentAmt = evaluateExpression(expression)
                                val half = if (currentAmt > BigDecimal.ZERO) {
                                    currentAmt.divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)
                                } else BigDecimal("1000.00")
                                splits = splits + SplitDto(
                                    id = UUID.randomUUID().toString(),
                                    categoryId = selectedCategoryId,
                                    amount = half,
                                    note = "Split part ${splits.size + 1}"
                                )
                            }
                        ) {
                            Text(s.addSplitButton)
                        }
                    }

                    splits.forEachIndexed { index, split ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val catName = categories.firstOrNull { it.id == split.categoryId }?.localizedName(langCode) ?: "Category"
                            Text("• $catName: ${CurrencyFormatter.format(split.amount, currency)}", color = c.text)
                            IconButton(onClick = { splits = splits.filterIndexed { i, _ -> i != index } }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = s.deleteButton, tint = c.muted)
                            }
                        }
                    }
                }
            }

            // Save
            KakeiButton(
                text = saveLabel,
                icon = Icons.Outlined.Check,
                color = accentColor,
                enabled = crossCurrencyReady && sentAmount > BigDecimal.ZERO,
                height = 58.dp,
                onClick = {
                    val evaluated = evaluateExpression(expression)
                    if (evaluated > BigDecimal.ZERO && selectedAccountId.isNotBlank() && crossCurrencyReady) {
                        onSave(
                            selectedAccountId,
                            recordType,
                            evaluated,
                            if (recordType == "TRANSFER") null else selectedCategoryId,
                            payee,
                            note,
                            clearingStatus,
                            if (recordType == "TRANSFER") targetAccountId else null,
                            splits,
                            selectedLabels.toList(),
                            if (isCrossCurrency) effectiveReceived else null
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private const val OPERATORS = "+-×÷"

/** Colors handed out in turn to labels created from the record form. */
private val LABEL_COLORS = listOf("#6366F1", "#10B981", "#F59E0B", "#EF4444", "#06B6D4", "#EC4899", "#8B5CF6")

/**
 * Big-number rendering of the calculator state: a plain number keeps what the user typed
 * (so "12." stays visible), an expression shows its running result. Digits are grouped by 3.
 */
private fun displayAmount(expression: String): String {
    val plain = if (expression.any { it in OPERATORS }) {
        evaluateExpression(expression).stripTrailingZeros().toPlainString()
    } else expression
    val intPart = plain.substringBefore('.')
    val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
    return if ('.' in plain) "$grouped,${plain.substringAfter('.')}" else grouped
}

private fun parseDecimalInput(raw: String): BigDecimal? =
    raw.trim().replace(" ", "").replace(",", ".").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

private fun applyCalculatorKey(current: String, key: String): String {
    if (key == "⌫") {
        return if (current.length <= 1) "0" else current.dropLast(1)
    }
    if (current == "0" && key in "0".."9") {
        return key
    }
    return current + key
}

private fun evaluateExpression(expr: String): BigDecimal {
    return runCatching {
        val normalized = expr.replace("×", "*").replace("÷", "/").trim()
        val tokens = Regex("""([0-9]+(?:\.[0-9]+)?)|([+\-*/])""").findAll(normalized).map { it.value }.toList()
        if (tokens.isEmpty()) return BigDecimal.ZERO
        var acc = BigDecimal(tokens.first())
        var i = 1
        while (i + 1 < tokens.size) {
            val op = tokens[i]
            val rhs = BigDecimal(tokens[i + 1])
            acc = when (op) {
                "+" -> acc + rhs
                "-" -> acc - rhs
                "*" -> acc.multiply(rhs)
                "/" -> if (rhs.compareTo(BigDecimal.ZERO) != 0) acc.divide(rhs, 2, RoundingMode.HALF_UP) else acc
                else -> acc
            }
            i += 2
        }
        acc.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
    }.getOrDefault(BigDecimal.ZERO)
}
