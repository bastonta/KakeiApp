package com.kakei.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.core.i18n.LocalAppStrings
import com.kakei.app.core.security.HapticHelper
import com.kakei.app.core.util.SystemCategories
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.ui.components.IconTile
import com.kakei.app.ui.components.KakeiChip
import com.kakei.app.ui.theme.KakeiTheme

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

/** Large icon tile with a caption, used by the record editor's group row. */
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        )
    }
}
