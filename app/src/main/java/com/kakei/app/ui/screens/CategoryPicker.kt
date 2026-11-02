package com.kakei.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.security.HapticHelper
import com.kakei.app.core.util.SystemCategories
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.ui.components.GroupCaption
import com.kakei.app.ui.components.IconTile
import com.kakei.app.ui.components.KakeiChip
import com.kakei.app.ui.components.PageTopBar
import com.kakei.app.ui.components.RowDivider
import com.kakei.app.ui.theme.KakeiTheme
import com.kakei.app.ui.theme.kakeiCard

/**
 * Two-level view of the category list: top-level groups (and subcategories whose parent is
 * not loaded) plus the subcategories of each group, both sorted by localized name.
 */
data class CategoryTree(
    val roots: List<CategoryDto>,
    val children: Map<String, List<CategoryDto>>
) {
    private val parentById: Map<String, String> =
        children.flatMap { (parentId, kids) -> kids.map { it.id to parentId } }.toMap()

    /** The group a category belongs to: its parent, or the category itself for a root. */
    fun rootOf(categoryId: String): String = parentById[categoryId] ?: categoryId

    companion object {
        fun build(categories: List<CategoryDto>, langCode: String): CategoryTree {
            val ids = categories.mapTo(HashSet()) { it.id }
            val sorted = categories.sortedBy { it.localizedName(langCode).lowercase() }
            val (nested, roots) = sorted.partition { it.parentId != null && it.parentId in ids && it.parentId != it.id }
            return CategoryTree(roots, nested.groupBy { it.parentId!! })
        }
    }
}

/**
 * Category picker: a row of top-level groups; tapping a group that has subcategories opens
 * them in a second row, led by a chip that picks the whole group. [isSelected] supports
 * multi-select pickers (budgets); [recordType] moves the Income group to the front for income
 * and to the back otherwise; [tiles] draws the group row as large icon tiles.
 */
@Composable
fun CategoryPickerRow(
    categories: List<CategoryDto>,
    selectedId: String?,
    langCode: String,
    onSelect: (CategoryDto) -> Unit,
    isSelected: (String) -> Boolean = { it == selectedId },
    recordType: String? = null,
    tiles: Boolean = false
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    val tree = remember(categories, langCode) { CategoryTree.build(categories, langCode) }
    val roots = remember(tree, recordType) {
        when (recordType) {
            "INCOME" -> tree.roots.sortedByDescending { it.id == SystemCategories.INCOME }
            "EXPENSE" -> tree.roots.sortedBy { it.id == SystemCategories.INCOME }
            else -> tree.roots
        }
    }
    var expandedId by remember { mutableStateOf(selectedId?.let { tree.rootOf(it) }?.takeIf { tree.children.containsKey(it) }) }

    fun groupActive(root: CategoryDto): Boolean =
        isSelected(root.id) || tree.children[root.id].orEmpty().any { isSelected(it.id) }

    fun onRootClick(root: CategoryDto) {
        HapticHelper.tick(context)
        if (tree.children[root.id].isNullOrEmpty()) {
            expandedId = null
            onSelect(root)
        } else {
            expandedId = if (expandedId == root.id) null else root.id
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            roots.forEach { root ->
                if (tiles) {
                    CategoryTile(
                        category = root,
                        langCode = langCode,
                        active = groupActive(root) || expandedId == root.id,
                        onClick = { onRootClick(root) }
                    )
                } else {
                    val visual = categoryVisual(root)
                    KakeiChip(
                        text = root.localizedName(langCode),
                        icon = visual.icon,
                        iconTint = visual.color,
                        color = visual.color,
                        selected = groupActive(root),
                        onClick = { onRootClick(root) }
                    )
                }
            }
        }

        val expanded = roots.firstOrNull { it.id == expandedId }
        val kids = expanded?.let { tree.children[it.id] }.orEmpty()
        if (expanded != null && kids.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val groupColor = categoryVisual(expanded).color
                KakeiChip(
                    text = s.pick("Вся группа", "Whole group"),
                    icon = Icons.Outlined.Apps,
                    iconTint = groupColor,
                    color = groupColor,
                    selected = isSelected(expanded.id),
                    onClick = {
                        HapticHelper.tick(context)
                        onSelect(expanded)
                    }
                )
                kids.forEach { cat ->
                    val visual = categoryVisual(cat)
                    KakeiChip(
                        text = cat.localizedName(langCode),
                        icon = visual.icon,
                        iconTint = visual.color,
                        color = visual.color,
                        selected = isSelected(cat.id),
                        onClick = {
                            HapticHelper.tick(context)
                            onSelect(cat)
                        }
                    )
                }
            }
        }
    }
}

/** Large icon tile with a caption: the "frequent" row of the category page and the tile picker row. */
@Composable
private fun CategoryTile(
    category: CategoryDto,
    langCode: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val c = KakeiTheme.colors
    val visual = categoryVisual(category)
    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconTile(
            icon = visual.icon,
            color = visual.color,
            size = 58.dp,
            iconSize = 26.dp,
            cornerRadius = 18.dp,
            selected = active
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = category.localizedName(langCode),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) c.text else c.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        )
    }
}

/**
 * Categories booked most often on past [recordType] records, most used first; at most [limit],
 * and only ones still in [categories].
 */
fun frequentCategoryIds(
    transactions: List<TransactionDto>,
    categories: List<CategoryDto>,
    recordType: String,
    limit: Int = 5
): List<String> {
    val known = categories.mapTo(HashSet()) { it.id }
    return transactions
        .filter { it.type == recordType && !it.isTransferCredit && it.categoryId != null && it.categoryId in known }
        .groupingBy { it.categoryId!! }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }
}

/**
 * Categories whose localized or base name contains [query], ignoring case; names starting
 * with the query come first, then alphabetical. A blank query finds nothing.
 */
fun searchCategories(categories: List<CategoryDto>, query: String, langCode: String): List<CategoryDto> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return categories
        .filter { it.localizedName(langCode).lowercase().contains(q) || it.name.lowercase().contains(q) }
        .sortedWith(
            compareByDescending<CategoryDto> { it.localizedName(langCode).lowercase().startsWith(q) }
                .thenBy { it.localizedName(langCode).lowercase() }
        )
}

/**
 * Full-screen category picker of the record editor: a search field, the [frequentIds] as
 * tiles, then every group. A group with subcategories opens in place (led by "whole group"),
 * any other row picks. Back closes an open group first, then the page.
 */
@Composable
fun CategoryPickerPage(
    categories: List<CategoryDto>,
    selectedId: String?,
    langCode: String,
    recordType: String,
    frequentIds: List<String>,
    onPick: (CategoryDto) -> Unit,
    onBack: () -> Unit
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
    val byId = remember(categories) { categories.associateBy { it.id } }
    var query by remember { mutableStateOf("") }
    var openGroupId by remember { mutableStateOf<String?>(null) }
    val openGroup = openGroupId?.let { byId[it] }
    val results = remember(categories, query, langCode) { searchCategories(categories, query, langCode) }
    val scrollState = rememberScrollState()

    val pick: (CategoryDto) -> Unit = { category ->
        HapticHelper.tick(context)
        onPick(category)
    }
    val back = { if (openGroupId != null) openGroupId = null else onBack() }
    BackHandler(onBack = back)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        PageTopBar(
            title = openGroup?.localizedName(langCode) ?: s.pick("Категория", "Category"),
            backContentDescription = s.pick("Назад", "Back"),
            onBack = back,
            scrolled = scrollState.canScrollBackward
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(s.pick("Поиск категории", "Search categories")) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = c.muted) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = s.pick("Очистить", "Clear"), tint = c.muted)
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = c.surf2,
                    focusedContainerColor = c.surf2,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            when {
                query.isNotBlank() -> {
                    GroupCaption(s.pick("Найдено: ${results.size}", "Found: ${results.size}"))
                    if (results.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Outlined.Search,
                            title = s.pick("Ничего не найдено", "Nothing found"),
                            hint = s.pick("Попробуйте другое название.", "Try another name.")
                        )
                    } else {
                        CategoryListCard(results) { cat ->
                            val parent = cat.parentId?.let { byId[it] }?.takeIf { it.id != cat.id }
                            CategoryListRow(
                                category = cat,
                                langCode = langCode,
                                subtitle = parent?.localizedName(langCode),
                                selected = cat.id == selectedId,
                                onClick = { pick(cat) }
                            )
                        }
                    }
                }
                openGroup != null -> {
                    val groupColor = categoryVisual(openGroup).color
                    CategoryListCard(listOf(openGroup) + tree.children[openGroup.id].orEmpty()) { cat ->
                        if (cat.id == openGroup.id) {
                            CategoryListRow(
                                category = cat,
                                langCode = langCode,
                                title = s.pick("Вся группа", "Whole group"),
                                icon = Icons.Outlined.Apps,
                                color = groupColor,
                                selected = cat.id == selectedId,
                                onClick = { pick(cat) }
                            )
                        } else {
                            CategoryListRow(
                                category = cat,
                                langCode = langCode,
                                selected = cat.id == selectedId,
                                onClick = { pick(cat) }
                            )
                        }
                    }
                }
                else -> {
                    val frequent = frequentIds.mapNotNull { byId[it] }
                    if (frequent.isNotEmpty()) {
                        GroupCaption(s.pick("Частые", "Frequent"))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            frequent.forEach { cat ->
                                CategoryTile(
                                    category = cat,
                                    langCode = langCode,
                                    active = cat.id == selectedId,
                                    onClick = { pick(cat) }
                                )
                            }
                        }
                    }
                    GroupCaption(s.pick("Все категории", "All categories"))
                    CategoryListCard(roots) { root ->
                        val kids = tree.children[root.id].orEmpty()
                        CategoryListRow(
                            category = root,
                            langCode = langCode,
                            subtitle = if (kids.isNotEmpty()) "${s.pick("Подкатегорий", "Subcategories")}: ${kids.size}" else null,
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
            }
        }
    }
}

/** Card of category rows separated by hairlines. */
@Composable
private fun CategoryListCard(items: List<CategoryDto>, row: @Composable (CategoryDto) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard()
    ) {
        items.forEachIndexed { index, cat ->
            if (index > 0) RowDivider(startIndent = 74.dp)
            key(cat.id) { row(cat) }
        }
    }
}

@Composable
private fun CategoryListRow(
    category: CategoryDto,
    langCode: String,
    selected: Boolean,
    onClick: () -> Unit,
    title: String = category.localizedName(langCode),
    subtitle: String? = null,
    icon: ImageVector? = null,
    color: Color? = null,
    hasChildren: Boolean = false
) {
    val c = KakeiTheme.colors
    val visual = categoryVisual(category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            icon = icon ?: visual.icon,
            color = color ?: visual.color,
            size = 44.dp,
            iconSize = 22.dp,
            cornerRadius = 22.dp,
            selected = selected
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
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
