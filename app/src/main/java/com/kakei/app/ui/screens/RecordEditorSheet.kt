package com.kakei.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.security.HapticHelper
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.LabelDto
import com.kakei.app.data.remote.dto.SplitDto
import com.kakei.app.ui.theme.CoralExpense
import com.kakei.app.ui.theme.CyanTransfer
import com.kakei.app.ui.theme.EmeraldBrand
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Wallet by BudgetBakers inspired Transaction Editor Dialog/Sheet with:
 * - EXPENSE / INCOME / TRANSFER tabs
 * - Built-in expression calculator keypad (`+`, `-`, `×`, `÷`, `=`, `⌫`)
 * - Source & Target Account selectors
 * - Category & Clearing Status (`UNCLEARED`, `CLEARED`, `RECONCILED`) selectors
 * - Multi-category Splits (`SplitDto`) editor
 * - Orthogonal Labels (`LabelDto`) multi-select chips
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditorDialog(
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    labels: List<LabelDto>,
    langCode: String,
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
    val context = LocalContext.current

    var recordType by remember { mutableStateOf("EXPENSE") }
    var expression by remember { mutableStateOf("0") }
    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var targetAccountId by remember {
        mutableStateOf(accounts.drop(1).firstOrNull()?.id ?: accounts.firstOrNull()?.id.orEmpty())
    }
    var selectedCategoryId by remember { mutableStateOf(categories.firstOrNull()?.id) }
    var payee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var clearingStatus by remember { mutableStateOf("CLEARED") }
    var selectedLabels by remember { mutableStateOf(setOf<String>()) }
    var splits by remember { mutableStateOf(listOf<SplitDto>()) }
    var showKeypad by remember { mutableStateOf(true) }

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
        "INCOME" -> EmeraldBrand
        "TRANSFER" -> CyanTransfer
        else -> CoralExpense
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Type Tabs: EXPENSE / INCOME / TRANSFER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "EXPENSE" to s.expenseLabel,
                    "INCOME" to s.incomeLabel,
                    "TRANSFER" to s.transferLabel
                ).forEach { (typeKey, label) ->
                    val active = recordType == typeKey
                    FilterChip(
                        selected = active,
                        onClick = {
                            HapticHelper.tick(context)
                            recordType = typeKey
                        },
                        label = { Text(label, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Big Calculator Amount Display
            Surface(
                color = accentColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(18.dp),
                onClick = { showKeypad = !showKeypad },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currency,
                        style = MaterialTheme.typography.titleMedium,
                        color = accentColor
                    )
                    Text(
                        text = "$expression ${CurrencyFormatter.symbolFor(currency)}",
                        style = MaterialTheme.typography.headlineLarge,
                        color = accentColor
                    )
                }
            }

            // Built-in Wallet Calculator Keypad
            if (showKeypad) {
                val rows = listOf(
                    listOf("7", "8", "9", "÷"),
                    listOf("4", "5", "6", "×"),
                    listOf("1", "2", "3", "-"),
                    listOf(".", "0", "⌫", "+")
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { key ->
                                FilledTonalButton(
                                    onClick = {
                                        HapticHelper.tick(context)
                                        expression = applyCalculatorKey(expression, key)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(key, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { expression = "0" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("C")
                        }
                        Button(
                            onClick = {
                                expression = evaluateExpression(expression).toPlainString()
                            },
                            modifier = Modifier.weight(2f)
                        ) {
                            Text("=")
                        }
                    }
                }
            }

            // Source Account Selector
            Text(s.accountLabel, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                accounts.filter { !it.isArchived }.forEach { acc ->
                    FilterChip(
                        selected = selectedAccountId == acc.id,
                        onClick = { selectedAccountId = acc.id },
                        label = { Text("${acc.name} (${acc.currency})") }
                    )
                }
            }

            // Target Account Selector for TRANSFER
            if (recordType == "TRANSFER") {
                Text(s.targetAccountLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    accounts.filter { !it.isArchived && it.id != selectedAccountId }.forEach { acc ->
                        FilterChip(
                            selected = targetAccountId == acc.id,
                            onClick = { targetAccountId = acc.id },
                            label = { Text("${acc.name} (${acc.currency})") }
                        )
                    }
                }
                if (isCrossCurrency && targetCurrency != null) {
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
            } else {
                // Category Selector
                Text(s.categoryLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategoryId == cat.id,
                            onClick = { selectedCategoryId = cat.id },
                            label = { Text(cat.localizedName(langCode)) }
                        )
                    }
                }
            }

            // Payee & Note
            OutlinedTextField(
                value = payee,
                onValueChange = { payee = it },
                label = { Text(s.payeeLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(s.noteLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Clearing Lifecycle Status: UNCLEARED / CLEARED / RECONCILED
            Text(s.statusLabel, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("UNCLEARED", "CLEARED", "RECONCILED").forEach { st ->
                    FilterChip(
                        selected = clearingStatus == st,
                        onClick = { clearingStatus = st },
                        label = { Text(st) }
                    )
                }
            }

            // Orthogonal Labels / Tags
            if (labels.isNotEmpty()) {
                Text(s.labelsTitle, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    labels.forEach { lbl ->
                        val isSelected = selectedLabels.contains(lbl.id)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedLabels = if (isSelected) selectedLabels - lbl.id else selectedLabels + lbl.id
                            },
                            label = { Text("#${lbl.name}") }
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
                    Text(s.splitsTitle, style = MaterialTheme.typography.labelLarge)
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
                        Text("• $catName: ${CurrencyFormatter.format(split.amount, currency)}")
                        IconButton(onClick = { splits = splits.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    }
                }
            }

            // Save & Cancel Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(s.cancelButton)
                }
                Button(
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
                    enabled = crossCurrencyReady,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(s.saveButton, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
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
