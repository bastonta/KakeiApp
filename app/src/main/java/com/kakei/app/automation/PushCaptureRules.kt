package com.kakei.app.automation

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * A user-written rule turning a push notification from one app into a record.
 *
 * [pattern] is a regular expression (case-insensitive) searched in "title text" of the
 * notification. It must define the named group `amount`; `currency` and `merchant` are
 * optional. Without a `currency` group the rule's [currency] (else the profile default) is used.
 * [accountId] / [categoryId] override the sender-account mapping and auto-categorization.
 */
@Serializable
data class PushCaptureRule(
    val id: String,
    val packageName: String,
    val name: String,
    val pattern: String,
    val operationType: String = "EXPENSE", // "EXPENSE" | "INCOME"
    val currency: String? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val enabled: Boolean = true
)

/** Outcome of running one rule against a notification text. */
sealed interface PushRuleResult {
    data class Matched(val parsed: ParsedBankNotification) : PushRuleResult
    data object NoMatch : PushRuleResult
    data class Invalid(val reason: Reason) : PushRuleResult

    enum class Reason { BAD_REGEX, NO_AMOUNT_GROUP, BAD_AMOUNT }
}

/**
 * Applies user push rules. Only rules of the notification's own app are consulted, in
 * list order; the first matching rule wins. A notification no rule matches is ignored.
 */
object PushRuleEngine {

    private const val AMOUNT_GROUP = "(?<amount>"
    private val compiled = HashMap<String, Result<Regex>>()

    /** Normalizes notification text the same way the rule preview does. */
    fun normalize(rawText: String): String = rawText.trim().replace(Regex("""\s*\n\s*"""), " ")

    /** Null when [pattern] is usable, else why not. */
    fun validate(pattern: String): PushRuleResult.Reason? {
        if (!pattern.contains(AMOUNT_GROUP)) return PushRuleResult.Reason.NO_AMOUNT_GROUP
        return if (compile(pattern).isFailure) PushRuleResult.Reason.BAD_REGEX else null
    }

    fun apply(rule: PushCaptureRule, rawText: String, defaultCurrency: String): PushRuleResult {
        validate(rule.pattern)?.let { return PushRuleResult.Invalid(it) }
        val regex = compile(rule.pattern).getOrThrow()
        val text = normalize(rawText)
        val match = regex.find(text) ?: return PushRuleResult.NoMatch

        val amount = match.group("amount")?.let(BankSmsPatterns::parseNormalizedDecimal)
            ?.takeIf { it > BigDecimal.ZERO }
            ?: return PushRuleResult.Invalid(PushRuleResult.Reason.BAD_AMOUNT)
        val currency = match.group("currency")?.let(BankSmsPatterns::normalizeCurrency)
            ?: rule.currency?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
            ?: defaultCurrency.uppercase(Locale.ROOT)
        val merchant = match.group("merchant")?.trim()?.trim('.', ',', ':', '-')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: rule.name.ifBlank { "Bank Operation" }

        return PushRuleResult.Matched(
            ParsedBankNotification(
                amount = amount.setScale(2, RoundingMode.HALF_UP),
                currency = currency,
                operationType = if (rule.operationType.equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE",
                merchant = merchant,
                rawMessage = text,
                sourceChannel = "PUSH"
            )
        )
    }

    /** First enabled rule of [packageName] matching [rawText], with its parse result. */
    fun match(
        rules: List<PushCaptureRule>,
        packageName: String,
        rawText: String,
        defaultCurrency: String
    ): Pair<PushCaptureRule, ParsedBankNotification>? {
        for (rule in rules) {
            if (!rule.enabled || rule.packageName != packageName) continue
            val result = apply(rule, rawText, defaultCurrency)
            if (result is PushRuleResult.Matched) return rule to result.parsed
        }
        return null
    }

    @Synchronized
    private fun compile(pattern: String): Result<Regex> {
        // The rule editor compiles on every keystroke; keep the cache from growing with drafts.
        if (compiled.size > 64 && pattern !in compiled) compiled.clear()
        return compiled.getOrPut(pattern) { runCatching { Regex(pattern, RegexOption.IGNORE_CASE) } }
    }

    /** A named group's value, or null when the pattern has no such group or it did not take part. */
    private fun MatchResult.group(name: String): String? =
        runCatching { groups[name]?.value }.getOrNull()?.takeIf { it.isNotBlank() }
}
