package com.kakei.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kakei.app.R
import com.kakei.app.automation.PushCaptureRule
import com.kakei.app.automation.PushRuleEngine
import com.kakei.app.automation.PushRuleResult
import com.kakei.app.automation.PushRuleTemplates
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.repository.AppSettingsState
import com.kakei.app.data.repository.KakeiRepository
import com.kakei.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private data class InstalledApp(val packageName: String, val label: String)

/** Label resource of a rule / alert operation type. */
internal fun pushOperationLabel(operationType: String): Int = when (operationType) {
    "INCOME" -> R.string.push_rule_income
    "CANCEL" -> R.string.push_rule_cancel_type
    "REFUND" -> R.string.push_rule_refund
    else -> R.string.push_rule_expense
}

private fun appLabel(context: Context, packageName: String): String =
    runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

private fun launcherApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return resolved
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { InstalledApp(it, appLabel(context, it)) }
        .sortedBy { it.label.lowercase() }
}

/**
 * Push capture set up by hand: the apps whose notifications are read and, per app, the
 * regex rules that turn a notification into a record. Unmatched notifications are ignored.
 */
@Composable
fun PushCaptureRulesCard(
    settings: AppSettingsState,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    repository: KakeiRepository
) {
    val s = LocalResources.current
    val context = LocalContext.current
    val samples by repository.recentPushSamples.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PushCaptureRule?>(null) }
    val apps = settings.pushCaptureApps.map { InstalledApp(it, appLabel(context, it)) }.sortedBy { it.label.lowercase() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .kakeiCard(cornerRadius = 24.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = s.getString(R.string.push_rules_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = s.getString(R.string.push_rules_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!settings.pushCaptureEnabled) {
            Text(
                text = s.getString(R.string.push_rules_capture_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        apps.forEach { app ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Text(app.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val appRules = settings.pushCaptureRules.filter { it.packageName == app.packageName }
            if (appRules.isEmpty()) {
                Text(
                    text = s.getString(R.string.push_rules_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            appRules.forEach { rule ->
                PushRuleRow(
                    rule = rule,
                    onToggle = { repository.upsertPushCaptureRule(rule.copy(enabled = it)) },
                    onEdit = { editing = rule },
                    onDelete = { repository.deletePushCaptureRule(rule.id) }
                )
            }
            val count = samples[app.packageName]?.size ?: 0
            if (count > 0) {
                Text(
                    text = s.getString(R.string.push_rules_samples_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                editing = PushCaptureRule(
                    id = UUID.randomUUID().toString(),
                    packageName = app.packageName,
                    name = "",
                    pattern = ""
                )
            }) { Text(s.getString(R.string.push_rules_add)) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        FilledTonalButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(s.getString(R.string.push_rules_pick_apps))
        }
    }

    if (showPicker) {
        AppPickerDialog(
            selected = settings.pushCaptureApps,
            onToggle = { pkg, on -> repository.setPushCaptureApp(pkg, on) },
            onDismiss = { showPicker = false }
        )
    }
    editing?.let { rule ->
        PushRuleEditorDialog(
            initial = rule,
            samples = samples[rule.packageName].orEmpty(),
            defaultCurrency = settings.profile.defaultCurrency,
            langCode = settings.language.code,
            accounts = accounts.filter { !it.isArchived && it.permissionLevel != "READ_ONLY" },
            categories = categories,
            onSave = {
                repository.upsertPushCaptureRule(it)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun PushRuleRow(
    rule: PushCaptureRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val s = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.name.ifBlank { s.getString(R.string.push_rule_unnamed) } + " · " +
                    s.getString(pushOperationLabel(rule.operationType)),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = rule.pattern,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = s.getString(R.string.push_rule_edit)) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = s.getString(R.string.push_rule_delete)) }
    }
}

@Composable
private fun AppPickerDialog(
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalResources.current
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launcherApps(context) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.getString(R.string.push_rules_done)) } },
        title = { Text(s.getString(R.string.push_rules_pick_apps)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(s.getString(R.string.push_rules_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val list = apps
                if (list == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    val q = query.trim()
                    val shown = list
                        .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
                        .sortedByDescending { it.packageName in selected }
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(app.packageName, !checked) }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { onToggle(app.packageName, it) })
                                Column {
                                    Text(app.label)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PushRuleEditorDialog(
    initial: PushCaptureRule,
    samples: List<String>,
    defaultCurrency: String,
    langCode: String,
    accounts: List<AccountDto>,
    categories: List<CategoryDto>,
    onSave: (PushCaptureRule) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalResources.current
    var name by remember { mutableStateOf(initial.name) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var type by remember { mutableStateOf(initial.operationType) }
    var currency by remember { mutableStateOf(initial.currency.orEmpty()) }
    var fixedAmount by remember {
        mutableStateOf(initial.fixedAmount?.stripTrailingZeros()?.toPlainString().orEmpty())
    }
    var accountId by remember { mutableStateOf(initial.accountId) }
    var categoryId by remember { mutableStateOf(initial.categoryId) }
    var testText by remember { mutableStateOf(samples.firstOrNull().orEmpty()) }

    val draft = initial.copy(
        name = name.trim(),
        pattern = pattern,
        operationType = type,
        currency = currency.trim().uppercase().takeIf { it.isNotEmpty() },
        // Only an alert that states no amount needs one from the rule.
        fixedAmount = fixedAmount.trim().replace(" ", "").replace(",", ".").toBigDecimalOrNull()
            ?.takeIf { it.signum() > 0 && !PushRuleEngine.hasAmountGroup(pattern) },
        accountId = accountId,
        categoryId = categoryId.takeIf { type != "CANCEL" }
    )
    val validation = PushRuleEngine.validate(pattern, type)
    val preview = if (validation == null && testText.isNotBlank()) {
        PushRuleEngine.apply(draft, testText, defaultCurrency)
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = validation == null) {
                Text(s.getString(R.string.push_rule_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.getString(R.string.push_rule_cancel)) } },
        title = { Text(s.getString(R.string.push_rule_editor_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Ready-made rules for known bank alerts; offered while the rule is still blank.
                if (initial.pattern.isEmpty()) {
                    Text(s.getString(R.string.push_rule_templates), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PushRuleTemplates.ALL.forEach { template ->
                            FilterChip(
                                selected = pattern == template.pattern,
                                onClick = {
                                    name = template.ruleName
                                    pattern = template.pattern
                                    type = template.operationType
                                    categoryId = template.categoryId?.takeIf { id -> categories.any { it.id == id } }
                                },
                                label = { Text(template.label) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s.getString(R.string.push_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(s.getString(R.string.push_rule_pattern)) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    isError = pattern.isNotEmpty() && validation != null,
                    supportingText = {
                        Text(
                            when {
                                pattern.isEmpty() -> s.getString(R.string.push_rule_pattern_help)
                                validation == PushRuleResult.Reason.NO_AMOUNT_GROUP -> s.getString(R.string.push_rule_err_no_amount)
                                validation == PushRuleResult.Reason.BAD_REGEX -> s.getString(R.string.push_rule_err_regex)
                                else -> s.getString(R.string.push_rule_pattern_help)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PushRuleEngine.OPERATION_TYPES.forEach { op ->
                        FilterChip(selected = type == op, onClick = { type = op }, label = { Text(s.getString(pushOperationLabel(op))) })
                    }
                }
                Text(
                    text = s.getString(
                        when (type) {
                            "CANCEL" -> R.string.push_rule_cancel_help
                            "REFUND" -> R.string.push_rule_refund_help
                            else -> R.string.push_rule_type_help
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.take(3) },
                    label = { Text(s.getString(R.string.push_rule_currency)) },
                    placeholder = { Text(defaultCurrency) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (validation == null && type != "CANCEL" && !PushRuleEngine.hasAmountGroup(pattern)) {
                    OutlinedTextField(
                        value = fixedAmount,
                        onValueChange = { fixedAmount = it },
                        label = { Text(s.getString(R.string.push_rule_fixed_amount)) },
                        supportingText = { Text(s.getString(R.string.push_rule_fixed_amount_help)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(s.getString(R.string.push_rule_account), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = accountId == null, onClick = { accountId = null }, label = { Text(s.getString(R.string.push_rule_auto)) })
                    accounts.forEach { acc ->
                        FilterChip(selected = accountId == acc.id, onClick = { accountId = acc.id }, label = { Text("${acc.name} (${acc.currency})") })
                    }
                }
                if (type != "CANCEL") {
                    Text(s.getString(R.string.push_rule_category), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text(s.getString(R.string.push_rule_auto)) })
                        categories.forEach { cat ->
                            FilterChip(selected = categoryId == cat.id, onClick = { categoryId = cat.id }, label = { Text(cat.localizedName(langCode)) })
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Text(s.getString(R.string.push_rule_test), style = MaterialTheme.typography.labelLarge)
                if (samples.isEmpty()) {
                    Text(
                        s.getString(R.string.push_rule_no_samples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    samples.forEach { sample ->
                        Text(
                            text = sample,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (sample == testText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { testText = sample }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    label = { Text(s.getString(R.string.push_rule_test_text)) },
                    modifier = Modifier.fillMaxWidth()
                )
                val (resultText, ok) = when (preview) {
                    null -> "" to true
                    is PushRuleResult.Matched -> {
                        val p = preview.parsed
                        val amountText = p.amount?.let { CurrencyFormatter.format(it, p.currency) }
                            ?: s.getString(R.string.push_rule_amount_later)
                        "${s.getString(pushOperationLabel(p.operationType))} · $amountText · ${p.merchant}" to true
                    }
                    PushRuleResult.NoMatch -> s.getString(R.string.push_rule_no_match) to false
                    is PushRuleResult.Invalid -> s.getString(R.string.push_rule_err_amount) to false
                }
                if (resultText.isNotEmpty()) {
                    Text(
                        resultText,
                        fontWeight = FontWeight.SemiBold,
                        color = if (ok) KakeiTheme.colors.mint else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
