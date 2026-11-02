package com.kakei.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.ui.components.IconTile
import com.kakei.app.ui.theme.KakeiTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A payee/merchant seen in past records, with the category it was last booked under per record type. */
data class PayeeHistoryEntry(
    val name: String,
    val count: Int,
    val lastCategoryByType: Map<String, String>
)

/**
 * Payees from past EXPENSE/INCOME records, merged case-insensitively, most used first.
 * The display name and categories come from the most recent record of each payee.
 */
fun buildPayeeHistory(transactions: List<TransactionDto>): List<PayeeHistoryEntry> =
    transactions
        .filter { it.type != "TRANSFER" && !it.isTransferCredit && !it.payee.isNullOrBlank() }
        .groupBy { it.payee!!.trim().lowercase() }
        .values
        .map { records ->
            val newestFirst = records.sortedWith(compareByDescending<TransactionDto> { it.bookingDate }.thenByDescending { it.createdAt })
            val lastCategoryByType = mutableMapOf<String, String>()
            newestFirst.forEach { tx ->
                if (tx.categoryId != null) lastCategoryByType.putIfAbsent(tx.type, tx.categoryId)
            }
            PayeeHistoryEntry(
                name = newestFirst.first().payee!!.trim(),
                count = records.size,
                lastCategoryByType = lastCategoryByType
            ) to newestFirst.first().bookingDate
        }
        .sortedWith(compareByDescending<Pair<PayeeHistoryEntry, String>> { it.first.count }.thenByDescending { it.second })
        .map { it.first }

/** Up to [limit] history entries matching [query] (prefix matches first) that were used for [recordType]. */
fun suggestPayees(
    history: List<PayeeHistoryEntry>,
    query: String,
    recordType: String,
    limit: Int = 5
): List<PayeeHistoryEntry> {
    val q = query.trim().lowercase()
    val forType = history.filter { it.lastCategoryByType.containsKey(recordType) || it.lastCategoryByType.isEmpty() }
    if (q.isEmpty()) return forType.take(limit)
    return forType
        .filter { it.name.lowercase().contains(q) && it.name.lowercase() != q }
        .sortedByDescending { it.name.lowercase().startsWith(q) }
        .take(limit)
}

/**
 * Payee text field with an inline suggestion list from [history], shown while the field is
 * focused. [onPick] fires when a suggestion is tapped.
 */
@Composable
fun PayeeAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    history: List<PayeeHistoryEntry>,
    recordType: String,
    categories: List<CategoryDto>,
    langCode: String,
    onPick: (PayeeHistoryEntry) -> Unit
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    var focused by remember { mutableStateOf(false) }
    val suggestions = remember(history, value, recordType) { suggestPayees(history, value, recordType) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(s.payeeLabel) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, tint = c.muted) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = c.surf2,
                focusedContainerColor = c.surf2,
                unfocusedBorderColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )

        if (focused && suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surf2)
            ) {
                suggestions.forEach { entry ->
                    val category = entry.lastCategoryByType[recordType]?.let { id -> categories.firstOrNull { it.id == id } }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (category != null) {
                            val visual = categoryVisual(category)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(visual.icon, contentDescription = null, tint = visual.color, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = category.localizedName(langCode),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact select box: a caption over the current value. Tapping it opens [sheet] in a
 * bottom sheet; the sheet content gets a callback that closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerField(
    caption: String,
    valueText: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sheet: @Composable (close: () -> Unit) -> Unit
) {
    val c = KakeiTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surf2)
            .clickable(enabled = enabled) { open = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (enabled) Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = c.muted)
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            containerColor = c.surf,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            sheet { open = false }
        }
    }
}

/** Sheet header: an optional back arrow and a title. */
@Composable
private fun PickerSheetHeader(title: String, onBack: (() -> Unit)? = null) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 8.dp else 20.dp, end = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = s.pick("Назад", "Back"), tint = c.text)
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One list row in a picker sheet; [hasChildren] shows a chevron instead of the check mark. */
@Composable
private fun PickerSheetRow(
    text: String,
    icon: ImageVector,
    iconTint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    hasChildren: Boolean = false
) {
    val c = KakeiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon = icon, color = iconTint, size = 40.dp, iconSize = 20.dp, cornerRadius = 12.dp, selected = selected)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
            }
        }
        when {
            hasChildren -> Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.muted)
            selected -> Icon(Icons.Outlined.Check, contentDescription = null, tint = c.mint)
        }
    }
}

/** Account select box; [transferTarget] switches to the transfer-target styling. */
@Composable
fun AccountPickerField(
    caption: String,
    accounts: List<AccountDto>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    transferTarget: Boolean = false,
    /** False shows the account read-only (an existing record cannot move between accounts). */
    enabled: Boolean = true
) {
    val c = KakeiTheme.colors
    val selected = accounts.firstOrNull { it.id == selectedId }
    val icon = if (transferTarget) Icons.Outlined.SwapHoriz else Icons.Outlined.AccountBalanceWallet
    val tint = if (transferTarget) c.cyan else c.mint
    PickerField(
        caption = caption,
        valueText = selected?.let { "${it.name} · ${it.currency}" } ?: "—",
        icon = icon,
        iconTint = tint,
        modifier = modifier,
        enabled = enabled
    ) { close ->
        PickerSheetHeader(caption)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(accounts, key = { it.id }) { acc ->
                PickerSheetRow(
                    text = acc.name,
                    subtitle = CurrencyFormatter.format(acc.currentBalance, acc.currency),
                    icon = icon,
                    iconTint = tint,
                    selected = acc.id == selectedId,
                    onClick = {
                        onSelect(acc.id)
                        close()
                    }
                )
            }
        }
    }
}

/**
 * Select box that opens a full page instead of a sheet (the record editor's category and
 * details): the same caption-over-value look as [PickerField], with a forward chevron.
 */
@Composable
fun NavigationField(
    caption: String,
    valueText: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp
) {
    val c = KakeiTheme.colors
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surf2)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = c.muted)
    }
}

/**
 * Booking date and time of a record, side by side: the date opens a calendar, the time a
 * clock dial. [value] is in the device time zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingDateTimeField(
    value: LocalDateTime,
    onChange: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateTimeBox(
            caption = s.pick("Дата", "Date"),
            valueText = dayLabel(value.toLocalDate(), s),
            icon = Icons.Outlined.CalendarToday,
            onClick = { showDate = true },
            modifier = Modifier.weight(1f)
        )
        DateTimeBox(
            caption = s.pick("Время", "Time"),
            valueText = value.format(DateTimeFormatter.ofPattern("HH:mm")),
            icon = Icons.Outlined.Schedule,
            onClick = { showTime = true },
            modifier = Modifier.weight(1f)
        )
    }

    if (showDate) {
        // The picker works on UTC midnights; only the calendar day is taken from it.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = value.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        val day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(value.with(day))
                    }
                    showDate = false
                }) { Text(s.pick("Готово", "Done")) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(s.cancelButton) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = value.hour,
            initialMinute = value.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            containerColor = c.surf,
            confirmButton = {
                TextButton(onClick = {
                    onChange(value.withHour(timeState.hour).withMinute(timeState.minute).withSecond(0).withNano(0))
                    showTime = false
                }) { Text(s.pick("Готово", "Done")) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text(s.cancelButton) }
            },
            text = { TimePicker(state = timeState) }
        )
    }
}

@Composable
private fun DateTimeBox(
    caption: String,
    valueText: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = KakeiTheme.colors
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surf2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
