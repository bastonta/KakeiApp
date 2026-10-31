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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.security.HapticHelper
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.core.util.SystemCategories
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.ui.components.IconTile
import com.kakei.app.ui.theme.KakeiTheme

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
    sheet: @Composable (close: () -> Unit) -> Unit
) {
    val c = KakeiTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surf2)
            .clickable { open = true }
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
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = c.muted)
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
    transferTarget: Boolean = false
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
        modifier = modifier
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
 * Category select box. The sheet lists the groups; tapping a group with subcategories opens
 * them (led by a "whole group" row), a group without subcategories is picked directly.
 * [recordType] moves the Income group to the front for income and to the back otherwise.
 */
@Composable
fun CategoryPickerField(
    categories: List<CategoryDto>,
    selectedId: String?,
    langCode: String,
    recordType: String,
    onSelect: (CategoryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    val c = KakeiTheme.colors
    val context = LocalContext.current
    val tree = remember(categories, langCode) { CategoryTree.build(categories, langCode) }
    val roots = remember(tree, recordType) {
        when (recordType) {
            "INCOME" -> tree.roots.sortedByDescending { it.id == SystemCategories.INCOME }
            else -> tree.roots.sortedBy { it.id == SystemCategories.INCOME }
        }
    }
    val selected = categories.firstOrNull { it.id == selectedId }
    val selectedVisual = selected?.let { categoryVisual(it) }
    val caption = s.pick("Категория", "Category")
    PickerField(
        caption = caption,
        valueText = selected?.localizedName(langCode) ?: s.pick("Не выбрана", "Not selected"),
        icon = selectedVisual?.icon ?: Icons.Outlined.Apps,
        iconTint = selectedVisual?.color ?: c.muted,
        modifier = modifier
    ) { close ->
        // Reopening the sheet starts inside the group of the current category.
        var openGroupId by remember {
            mutableStateOf(selectedId?.let { tree.rootOf(it) }?.takeIf { !tree.children[it].isNullOrEmpty() })
        }
        val openGroup = roots.firstOrNull { it.id == openGroupId }
        val pick: (CategoryDto) -> Unit = { category ->
            HapticHelper.tick(context)
            onSelect(category)
            close()
        }

        if (openGroup == null) {
            PickerSheetHeader(caption)
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(roots, key = { it.id }) { root ->
                    val visual = categoryVisual(root)
                    val kids = tree.children[root.id].orEmpty()
                    PickerSheetRow(
                        text = root.localizedName(langCode),
                        subtitle = if (kids.isNotEmpty()) "${s.pick("Подкатегорий", "Subcategories")}: ${kids.size}" else null,
                        icon = visual.icon,
                        iconTint = visual.color,
                        selected = root.id == selectedId || kids.any { it.id == selectedId },
                        hasChildren = kids.isNotEmpty(),
                        onClick = {
                            if (kids.isEmpty()) {
                                pick(root)
                            } else {
                                HapticHelper.tick(context)
                                openGroupId = root.id
                            }
                        }
                    )
                }
            }
        } else {
            val groupVisual = categoryVisual(openGroup)
            PickerSheetHeader(openGroup.localizedName(langCode), onBack = { openGroupId = null })
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "whole-group") {
                    PickerSheetRow(
                        text = s.pick("Вся группа", "Whole group"),
                        icon = Icons.Outlined.Apps,
                        iconTint = groupVisual.color,
                        selected = openGroup.id == selectedId,
                        onClick = { pick(openGroup) }
                    )
                }
                items(tree.children[openGroup.id].orEmpty(), key = { it.id }) { cat ->
                    val visual = categoryVisual(cat)
                    PickerSheetRow(
                        text = cat.localizedName(langCode),
                        icon = visual.icon,
                        iconTint = visual.color,
                        selected = cat.id == selectedId,
                        onClick = { pick(cat) }
                    )
                }
            }
        }
    }
}
