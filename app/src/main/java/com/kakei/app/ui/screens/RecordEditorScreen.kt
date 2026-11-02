package com.kakei.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Full-screen New Record / Edit Record flow of three pages sharing one form state:
 * - Main: EXPENSE / INCOME / TRANSFER tabs, the amount with a built-in expression calculator
 *   (`+`, `-`, `×`, `÷`, `⌫`; long-press `⌫` clears), Account and Category (or Target Account),
 *   and a summary row that opens the details
 * - Category: a full page with search, the categories used most for this record type, and groups
 * - Details: payee autocomplete (filling in the payee's last category), note, booking date and
 *   time (now for a new record), labels, clearing status and splits
 *
 * With [initialRecord] the form is prefilled for editing; type and accounts stay fixed.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    /** Record being edited (the debit leg for a transfer), or null for a new record. */
    initialRecord: TransactionDto? = null,
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
        targetAmount: BigDecimal?,
        /** ISO instant picked by the user, or null when left untouched (now / unchanged). */
        bookingDateIso: String?
    ) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val payeeHistory = remember(transactions) { buildPayeeHistory(transactions) }

    val editing = initialRecord != null
    // Credit leg of an edited transfer: its amount prefills "received" for cross-currency pairs.
    val initialCreditLeg = remember(initialRecord) {
        initialRecord?.transferPairId?.let { pairId ->
            transactions.firstOrNull { it.transferPairId == pairId && it.isTransferCredit }
        }
    }

    var recordType by remember { mutableStateOf(initialRecord?.type ?: "EXPENSE") }
    var expression by remember {
        mutableStateOf(
            initialRecord?.amount?.abs()?.setScale(2, RoundingMode.HALF_UP)?.stripTrailingZeros()
                ?.toPlainString() ?: "0"
        )
    }
    var selectedAccountId by remember {
        mutableStateOf(initialRecord?.accountId ?: accounts.firstOrNull()?.id.orEmpty())
    }
    var targetAccountId by remember {
        mutableStateOf(
            initialRecord?.transferTargetAccountId
                ?: accounts.drop(1).firstOrNull()?.id ?: accounts.firstOrNull()?.id.orEmpty()
        )
    }
    var selectedCategoryId by remember { mutableStateOf(initialRecord?.categoryId) }
    // Once the user picks a category themselves, typing a known payee no longer overrides it.
    var categoryPickedManually by remember { mutableStateOf(editing) }
    var payee by remember { mutableStateOf(initialRecord?.payee.orEmpty()) }
    var note by remember { mutableStateOf(initialRecord?.note.orEmpty()) }
    var clearingStatus by remember { mutableStateOf(initialRecord?.status ?: "CLEARED") }
    var selectedLabels by remember { mutableStateOf(initialRecord?.labelIds?.toSet() ?: setOf()) }
    var splits by remember { mutableStateOf(initialRecord?.splits ?: listOf()) }
    var newLabelName by remember { mutableStateOf<String?>(null) }
    var bookingAt by remember {
        mutableStateOf(
            initialRecord?.bookingDate?.let(::bookingDateTime)
                ?: LocalDateTime.now().withSecond(0).withNano(0)
        )
    }
    // Untouched: a new record is booked at the moment of saving, an edit keeps its timestamp.
    var bookingTouched by remember { mutableStateOf(false) }

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
    val initialReceived = initialCreditLeg?.takeIf { it.currency.equals(targetCurrency, ignoreCase = true) }
        ?.amount?.setScale(2, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
    var receivedInput by remember(currency, targetCurrency) { mutableStateOf(initialReceived.orEmpty()) }
    var receivedIsAnchor by remember(currency, targetCurrency) { mutableStateOf(initialReceived != null) }
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
    val saveLabel = when {
        editing -> s.pick("Сохранить изменения", "Save changes")
        recordType == "INCOME" -> s.pick("Сохранить доход", "Save income")
        recordType == "TRANSFER" -> s.pick("Сохранить перевод", "Save transfer")
        else -> s.pick("Сохранить расход", "Save expense")
    }
    val types = listOf("EXPENSE" to s.expenseLabel, "INCOME" to s.incomeLabel, "TRANSFER" to s.transferLabel)

    var page by remember { mutableStateOf(EditorPage.MAIN) }
    val frequentIds = remember(transactions, categories, recordType) {
        frequentCategoryIds(transactions, categories, recordType)
    }
    val labelsById = remember(labels) { labels.associateBy { it.id } }

    // The category page handles back itself (an open group closes first).
    BackHandler(enabled = page == EditorPage.MAIN, onBack = onDismiss)
    BackHandler(enabled = page == EditorPage.DETAILS) { page = EditorPage.MAIN }

    when (page) {
        EditorPage.CATEGORY -> CategoryPickerPage(
            categories = categories,
            selectedId = selectedCategoryId,
            langCode = langCode,
            recordType = recordType,
            frequentIds = frequentIds,
            onPick = {
                selectedCategoryId = it.id
                categoryPickedManually = true
                page = EditorPage.MAIN
            },
            onBack = { page = EditorPage.MAIN }
        )

        EditorPage.DETAILS -> {
            val scrollState = rememberScrollState()
            val signedAmount = when (recordType) {
                "INCOME" -> "+"
                "TRANSFER" -> "⇄ "
                else -> "−"
            } + CurrencyFormatter.format(sentAmount, currency)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.bg)
            ) {
                PageTopBar(
                    title = signedAmount,
                    backContentDescription = s.pick("Назад", "Back"),
                    onBack = { page = EditorPage.MAIN },
                    scrolled = scrollState.canScrollBackward
                ) {
                    CircleIconButton(
                        icon = Icons.Outlined.Check,
                        contentDescription = s.pick("Готово", "Done"),
                        onClick = { page = EditorPage.MAIN },
                        size = 44.dp,
                        containerColor = accentColor,
                        contentColor = if (recordType == "INCOME") c.mintInk else Color.White,
                        bordered = false
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Payee / merchant with suggestions from past records; a known payee fills in its last category
                    GroupCaption(s.pick("Получатель", "Payee"))
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

                    GroupCaption(s.noteLabel)
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text(s.pick("Описание", "Description")) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = c.muted) },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = c.surf2,
                            focusedContainerColor = c.surf2,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    GroupCaption(s.pick("Дата и время", "Date and time"))
                    BookingDateTimeField(
                        value = bookingAt,
                        onChange = {
                            bookingAt = it
                            bookingTouched = true
                        }
                    )

                    // Orthogonal Labels / Tags: pick existing ones or create a new one inline
                    GroupCaption(s.labelsTitle)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        KakeiChip(
                            text = s.pick("Метка", "Label"),
                            icon = Icons.Outlined.Add,
                            iconTint = c.mint,
                            selected = newLabelName != null,
                            onClick = { newLabelName = if (newLabelName == null) "" else null }
                        )
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

                    // Clearing Lifecycle Status: UNCLEARED / CLEARED / RECONCILED
                    GroupCaption(s.statusLabel)
                    val statuses = listOf("UNCLEARED", "CLEARED", "RECONCILED")
                    KakeiSegmented(
                        options = statuses.map { s.clearingStatus(it) },
                        selectedIndex = statuses.indexOf(clearingStatus).coerceAtLeast(0),
                        onSelect = { clearingStatus = statuses[it] }
                    )

                    // Splits Builder
                    if (recordType != "TRANSFER") {
                        GroupCaption(s.splitsTitle)
                        GroupCard {
                            splits.forEachIndexed { index, split ->
                                val category = categories.firstOrNull { it.id == split.categoryId }
                                val visual = category?.let { categoryVisual(it) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconTile(
                                        icon = visual?.icon ?: Icons.Outlined.Apps,
                                        color = visual?.color ?: c.muted,
                                        size = 36.dp,
                                        iconSize = 18.dp,
                                        cornerRadius = 10.dp
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = category?.localizedName(langCode) ?: s.pick("Без категории", "Uncategorized"),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = c.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = CurrencyFormatter.format(split.amount, currency),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = c.text
                                    )
                                    IconButton(onClick = { splits = splits.filterIndexed { i, _ -> i != index } }) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = s.deleteButton, tint = c.muted)
                                    }
                                }
                                RowDivider(startIndent = if (index == splits.lastIndex) 0.dp else 66.dp)
                            }
                            val remainder = sentAmount - splits.fold(BigDecimal.ZERO) { acc, it -> acc + it.amount }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val half = if (sentAmount > BigDecimal.ZERO) {
                                            sentAmount.divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)
                                        } else BigDecimal("1000.00")
                                        splits = splits + SplitDto(
                                            id = UUID.randomUUID().toString(),
                                            categoryId = selectedCategoryId,
                                            amount = half,
                                            note = "Split part ${splits.size + 1}"
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, tint = c.mint, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = s.addSplitButton.removePrefix("+").trim(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = c.mint,
                                    modifier = Modifier.weight(1f)
                                )
                                if (splits.isNotEmpty()) {
                                    Text(
                                        text = "${s.pick("Остаток", "Remaining")} ${formatMoney(remainder, currency)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (remainder < BigDecimal.ZERO) c.coral else c.muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        EditorPage.MAIN -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.bg)
            ) {
                PageTopBar(
                    title = if (editing) s.pick("Изменить операцию", "Edit record") else s.quickAddTransaction,
                    backContentDescription = s.pick("Назад", "Back"),
                    onBack = onDismiss,
                    scrolled = false
                )
                // Form above the keypad; the amount takes whatever height is left over.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Type Tabs: EXPENSE / INCOME / TRANSFER (fixed once the record exists)
                    if (!editing) {
                        KakeiSegmented(
                            options = types.map { it.second },
                            selectedIndex = types.indexOfFirst { it.first == recordType },
                            onSelect = { index ->
                                HapticHelper.tick(context)
                                recordType = types[index].first
                            }
                        )
                    }

                    // Big calculator amount display: the running total, with the raw expression below it
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = CurrencyFormatter.symbolFor(currency),
                                fontSize = 44.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.faint
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = displayAmount(expression),
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-1.5).sp,
                                color = accentColor,
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 72.sp)
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

                    // Account and category (or transfer target) side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AccountPickerField(
                            caption = s.pick("Счёт", "Account"),
                            accounts = if (editing) accounts else accounts.filter { !it.isArchived },
                            selectedId = selectedAccountId,
                            onSelect = { selectedAccountId = it },
                            modifier = Modifier.weight(1f),
                            enabled = !editing
                        )
                        if (recordType == "TRANSFER") {
                            AccountPickerField(
                                caption = s.targetAccountLabel,
                                accounts = accounts.filter { (editing || !it.isArchived) && it.id != selectedAccountId },
                                selectedId = targetAccount?.id,
                                onSelect = { targetAccountId = it },
                                modifier = Modifier.weight(1f),
                                transferTarget = true,
                                enabled = !editing
                            )
                        } else {
                            val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
                            val visual = selectedCategory?.let { categoryVisual(it) }
                            NavigationField(
                                caption = s.pick("Категория", "Category"),
                                valueText = selectedCategory?.localizedName(langCode) ?: s.pick("Не выбрана", "Not selected"),
                                icon = visual?.icon ?: Icons.Outlined.Apps,
                                iconTint = visual?.color ?: c.muted,
                                onClick = {
                                    focusManager.clearFocus()
                                    page = EditorPage.CATEGORY
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

                    // Secondary details on their own page; the row summarises what is set there
                    NavigationField(
                        caption = s.pick("Детали: получатель, дата, метки…", "Details: payee, date, labels…"),
                        valueText = detailsSummary(
                            payee = payee,
                            whenText = "${dayLabel(bookingAt.toLocalDate(), s)}, ${bookingAt.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                            labelNames = selectedLabels.mapNotNull { labelsById[it]?.name },
                            note = note,
                            splitCount = if (recordType == "TRANSFER") 0 else splits.size,
                            splitsCaption = s.pick("Частей", "Splits")
                        ),
                        icon = Icons.Outlined.Tune,
                        iconTint = c.muted,
                        onClick = {
                            focusManager.clearFocus()
                            page = EditorPage.DETAILS
                        },
                        height = 60.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Built-in calculator keypad, anchored above the save button
                val rows = listOf(
                    listOf("7", "8", "9", "÷"),
                    listOf("4", "5", "6", "×"),
                    listOf("1", "2", "3", "-"),
                    listOf(".", "0", "⌫", "+")
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                        .height(60.dp)
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
                                            fontSize = if (isOperator) 22.sp else 26.sp,
                                            color = if (isOperator) accentColor else c.text
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Save, pinned under the keypad
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
                                if (isCrossCurrency) effectiveReceived else null,
                                if (bookingTouched) bookingAt.atZone(ZoneId.systemDefault()).toInstant().toString() else null
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 12.dp)
                )
            }
        }
    }
}

/** The pages of the record editor flow. */
private enum class EditorPage { MAIN, CATEGORY, DETAILS }

/**
 * One-line summary of the details page for the editor's details row:
 * "Magnum · Сегодня, 19:42 · #семья · Частей: 2 · note".
 */
internal fun detailsSummary(
    payee: String,
    whenText: String,
    labelNames: List<String>,
    note: String,
    splitCount: Int,
    splitsCaption: String
): String = buildList {
    payee.trim().takeIf { it.isNotEmpty() }?.let(::add)
    add(whenText)
    labelNames.forEach { add("#$it") }
    if (splitCount > 0) add("$splitsCaption: $splitCount")
    note.trim().takeIf { it.isNotEmpty() }?.let(::add)
}.joinToString(" · ")

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
