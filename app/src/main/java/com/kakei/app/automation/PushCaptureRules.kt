package com.kakei.app.automation

import com.kakei.app.core.util.SystemCategories
import com.kakei.app.data.remote.dto.DecimalStr
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * A user-written rule turning a push notification from one app into a record.
 *
 * [pattern] is a regular expression (case-insensitive) searched in "title text" of the
 * notification. The named groups `amount`, `currency` and `merchant` are all optional.
 * An alert that states no amount (a bus ticket, a parking autopayment) is booked with
 * [fixedAmount] when the rule has one; otherwise it waits in the unprocessed list until the
 * user enters the amount. Without a `currency` group the rule's [currency] (else the profile
 * default) is used.
 * [accountId] / [categoryId] override the sender-account mapping and auto-categorization.
 * A CANCEL rule books nothing: it deletes the latest captured expense with the same amount
 * and currency (preferring one whose payee matches the `merchant` group).
 */
@Serializable
data class PushCaptureRule(
    val id: String,
    val packageName: String,
    val name: String,
    val pattern: String,
    /** "EXPENSE" | "INCOME" | "CANCEL" (voids the captured expense it refers to) | "REFUND" (income, Refunds category) */
    val operationType: String = "EXPENSE",
    val currency: String? = null,
    /** Amount of an alert whose pattern has no `amount` group; null asks the user later. */
    val fixedAmount: DecimalStr? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val enabled: Boolean = true
)

/** Outcome of running one rule against a notification text. */
sealed interface PushRuleResult {
    data class Matched(val parsed: ParsedBankNotification) : PushRuleResult
    data object NoMatch : PushRuleResult
    data class Invalid(val reason: Reason) : PushRuleResult

    enum class Reason { EMPTY, BAD_REGEX, NO_AMOUNT_GROUP, BAD_AMOUNT }
}

/**
 * Applies user push rules. Only rules of the notification's own app are consulted, in
 * list order; the first matching rule wins. A notification no rule matches is ignored.
 */
object PushRuleEngine {

    private const val AMOUNT_GROUP = "(?<amount>"
    val OPERATION_TYPES = listOf("EXPENSE", "INCOME", "CANCEL", "REFUND")
    private val compiled = HashMap<String, Result<Regex>>()

    /** Normalizes notification text the same way the rule preview does. */
    fun normalize(rawText: String): String = rawText.trim().replace(Regex("""\s*\n\s*"""), " ")

    /** Whether [pattern] reads the amount from the notification itself. */
    fun hasAmountGroup(pattern: String): Boolean = pattern.contains(AMOUNT_GROUP)

    /**
     * Null when [pattern] is usable, else why not. The `amount` group is optional, except
     * for a cancellation, which finds the expense to void by its amount.
     */
    fun validate(pattern: String, operationType: String = "EXPENSE"): PushRuleResult.Reason? {
        // An empty pattern would match every notification of the app.
        if (pattern.isBlank()) return PushRuleResult.Reason.EMPTY
        if (compile(pattern).isFailure) return PushRuleResult.Reason.BAD_REGEX
        if (operationType.uppercase(Locale.ROOT) == "CANCEL" && !hasAmountGroup(pattern)) {
            return PushRuleResult.Reason.NO_AMOUNT_GROUP
        }
        return null
    }

    fun apply(rule: PushCaptureRule, rawText: String, defaultCurrency: String): PushRuleResult {
        validate(rule.pattern, rule.operationType)?.let { return PushRuleResult.Invalid(it) }
        val regex = compile(rule.pattern).getOrThrow()
        val text = normalize(rawText)
        val match = regex.find(text) ?: return PushRuleResult.NoMatch

        val amount = if (hasAmountGroup(rule.pattern)) {
            match.group("amount")?.let(BankAlertText::parseNormalizedDecimal)
                ?.takeIf { it > BigDecimal.ZERO }
                ?: return PushRuleResult.Invalid(PushRuleResult.Reason.BAD_AMOUNT)
        } else {
            rule.fixedAmount?.takeIf { it > BigDecimal.ZERO }
        }
        val currency = match.group("currency")?.let(BankAlertText::normalizeCurrency)
            ?: rule.currency?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
            ?: defaultCurrency.uppercase(Locale.ROOT)
        val merchant = match.group("merchant")?.trim()?.trim('.', ',', ':', '-')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: rule.name.ifBlank { "Bank Operation" }

        return PushRuleResult.Matched(
            ParsedBankNotification(
                amount = amount?.setScale(2, RoundingMode.HALF_UP),
                currency = currency,
                operationType = rule.operationType.uppercase(Locale.ROOT).takeIf { it in OPERATION_TYPES } ?: "EXPENSE",
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

/** A ready-made rule for a known bank alert, offered in the rule editor. */
data class PushRuleTemplate(
    /** Chip label in the editor. */
    val label: String,
    /** Rule name; the payee when the pattern has no `merchant` group. */
    val ruleName: String,
    val pattern: String,
    val operationType: String = "EXPENSE",
    /** System category the rule files into, when the user still has it. */
    val categoryId: String? = null
)

/**
 * Rules for alerts as the banks word them (title and text joined by a space).
 * Amounts allow any Unicode space as the thousands separator: banks use no-break spaces.
 */
object PushRuleTemplates {
    private const val AMOUNT = """(?<amount>\d[\d\p{Zs}.,]*?)[\s\p{Zs}]*(?<currency>₸|\$|€|₽|[A-Z]{3})"""
    private const val BALANCE_TAIL = """(?:\s+Доступно.*)?$"""

    val ALL = listOf(
        // "Kaspi Gold Покупка: 1 360 ₸ ARAY MARKET Доступно: 104 906,23 ₸"
        PushRuleTemplate(
            label = "Kaspi · Покупка",
            ruleName = "Kaspi покупка",
            pattern = """Покупка:\s*$AMOUNT\s+(?<merchant>.+?)$BALANCE_TAIL"""
        ),
        // "Kaspi Gold Пополнение: 2 700 ₸ Нұрбол С."
        PushRuleTemplate(
            label = "Kaspi · Пополнение",
            ruleName = "Kaspi пополнение",
            pattern = """Пополнение:\s*$AMOUNT\s*(?<merchant>.*?)$BALANCE_TAIL""",
            operationType = "INCOME"
        ),
        // "Платежи Билет Avtobys. Оплата проезда": no amount in the alert.
        PushRuleTemplate(
            label = "Kaspi · Автобус",
            ruleName = "Автобус",
            pattern = """Билет\s+(?<merchant>.+?)\.\s*Оплата проезда""",
            categoryId = SystemCategories.TRANSIT
        ),
        // "Платежи Платная парковка ТЦ Алем будет оплачена автоплатежом": no amount in the alert.
        PushRuleTemplate(
            label = "Kaspi · Парковка",
            ruleName = "Парковка",
            pattern = """(?<merchant>Платная парковка.*?)\s+будет оплачен\p{L}*\s+автоплатежом""",
            categoryId = SystemCategories.PARKING
        ),
        // "ONLINEBANK_HALYK Списание: 2,500.00 KZT. За ведение счёта. Доступно на счете KZ26***5701: 744,375.61 KZT"
        PushRuleTemplate(
            label = "Halyk · Списание",
            ruleName = "Halyk списание",
            pattern = """Списание:\s*$AMOUNT\.?\s*(?<merchant>.*?)\.?$BALANCE_TAIL"""
        )
    )
}
