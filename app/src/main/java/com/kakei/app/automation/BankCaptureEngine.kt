package com.kakei.app.automation

import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.CounterpartyRuleDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ParsedBankNotification(
    val amount: BigDecimal,
    val currency: String,
    val operationType: String, // "EXPENSE" | "INCOME" | "TRANSFER"
    val merchant: String,
    val rawMessage: String,
    val sourceChannel: String  // "SMS" | "PUSH"
)

/**
 * Dual-engine regex parser (`BankSmsPatterns`) for bank SMS and Push notifications
 * supporting major banks (Kaspi, Halyk, Freedom, Forte, Jusan, BCC, Sber, T-Bank, Revolut, Wise, GPay)
 * and multi-currency ISO 4217 symbols (`KZT`, `USD`, `EUR`, `RUB`, `GBP`, `JPY`).
 */
object BankSmsPatterns {

    // Ignored messaging packages to guarantee privacy (messaging apps are never read)
    val ignoredPackages = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.facebook.orca",
        "com.instagram.android",
        "com.discord",
        "org.thoughtcrime.securesms",
        "com.viber.voip"
    )

    private val currencyMap = mapOf(
        "₸" to "KZT",
        "ТЕНГЕ" to "KZT",
        "ТГ" to "KZT",
        "KZT" to "KZT",
        "$" to "USD",
        "USD" to "USD",
        "€" to "EUR",
        "EUR" to "EUR",
        "₽" to "RUB",
        "РУБ" to "RUB",
        "RUB" to "RUB",
        "£" to "GBP",
        "GBP" to "GBP",
        "¥" to "JPY",
        "JPY" to "JPY"
    )

    private val expenseKeywords = listOf(
        "покупка", "оплата", "списание", "снятие", "purchase", "payment",
        "spent", "debited", "paid", "charge", "withdrawal", "atm"
    )

    private val incomeKeywords = listOf(
        "пополнение", "зачисление", "перевод от", "возврат", "кэшбэк",
        "credited", "received", "deposit", "salary", "refund", "cashback"
    )

    private val amountCurrencyRegex = Regex(
        """(?:покупка|оплата|списание|пополнение|зачисление|сумма|spent|paid|debited|credited|purchase|amount)?[\s:=-]*([0-9]{1,3}(?:[ \u00A0.,][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)\s*(KZT|USD|EUR|RUB|GBP|JPY|₸|\$|€|₽|£|¥|тг|тенге|руб)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val prefixSymbolRegex = Regex(
        """([\$€£¥₸₽])\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val merchantPatterns = listOf(
        Regex("""(?:в|at|to|у|merchant|магазине)\s+([A-Za-zА-Яа-яЁё0-9_.*&\-\s]{2,32}?)(?:\.|,|\s+Доступно|\s+Баланс|\s+Avail|\s+Bal|$)""", RegexOption.IGNORE_CASE),
        Regex("""Kaspi\s*Gold:\s*(?:Покупка|Оплата|Перевод)\s+[0-9\s.,]+(?:₸|KZT)\s+([A-Za-zА-Яа-яЁё0-9_.*&\-\s]{2,32})""", RegexOption.IGNORE_CASE),
        Regex("""([A-Z0-9][A-Z0-9\s._*-]{2,24})\s+(?:покупка|оплата|purchase|payment)""", RegexOption.IGNORE_CASE)
    )

    fun parse(
        rawText: String,
        sourceChannel: String = "SMS",
        defaultCurrency: String = "KZT"
    ): ParsedBankNotification? {
        val text = rawText.trim().replace("\n", " ")
        if (text.length < 6) return null

        val lower = text.lowercase(Locale.ROOT)
        // Ignore OTP / verification messages
        if (lower.contains("код подтверждения") || lower.contains("otp") || lower.contains("verification code") || lower.contains("пароль")) {
            return null
        }

        var amount: BigDecimal? = null
        var currency: String = defaultCurrency

        val match1 = amountCurrencyRegex.find(text)
        if (match1 != null) {
            amount = parseNormalizedDecimal(match1.groupValues[1])
            val rawCurr = match1.groupValues[2].uppercase(Locale.ROOT).removeSuffix(".")
            currency = currencyMap[rawCurr] ?: defaultCurrency
        } else {
            val match2 = prefixSymbolRegex.find(text)
            if (match2 != null) {
                val sym = match2.groupValues[1].uppercase(Locale.ROOT)
                amount = parseNormalizedDecimal(match2.groupValues[2])
                currency = currencyMap[sym] ?: defaultCurrency
            }
        }

        if (amount == null || amount <= BigDecimal.ZERO) return null

        val isIncome = incomeKeywords.any { lower.contains(it) }
        val isExpense = expenseKeywords.any { lower.contains(it) }
        if (!isIncome && !isExpense) return null

        val opType = if (isIncome && !lower.contains("покупка") && !lower.contains("оплата")) {
            "INCOME"
        } else {
            "EXPENSE"
        }

        var merchant = "Bank Operation"
        for (pattern in merchantPatterns) {
            val m = pattern.find(text)
            if (m != null) {
                val candidate = m.groupValues[1].trim().trim('.', ',', ':', '-')
                if (candidate.length >= 2) {
                    merchant = candidate
                    break
                }
            }
        }

        return ParsedBankNotification(
            amount = amount.setScale(2, RoundingMode.HALF_UP),
            currency = currency,
            operationType = opType,
            merchant = merchant,
            rawMessage = text,
            sourceChannel = sourceChannel
        )
    }

    private fun parseNormalizedDecimal(raw: String): BigDecimal? {
        val compact = raw.replace(" ", "").replace("\u00A0", "")
        val normalized = when {
            compact.contains(",") && compact.contains(".") -> {
                if (compact.lastIndexOf(',') > compact.lastIndexOf('.')) {
                    compact.replace(".", "").replace(",", ".")
                } else {
                    compact.replace(",", "")
                }
            }
            compact.contains(",") -> {
                val parts = compact.split(",")
                if (parts.last().length <= 2) compact.replace(",", ".") else compact.replace(",", "")
            }
            else -> compact
        }
        return runCatching { BigDecimal(normalized) }.getOrNull()
    }
}

/**
 * Cross-channel 5-minute sliding window deduplicator.
 * Drops duplicate alerts when both SMS and Push notification fire for the same payment.
 */
class NotificationDeduplicationHelper(
    private val windowMillis: Long = 5 * 60 * 1000L
) {
    private val recentFingerprints = ConcurrentHashMap<String, Long>()

    fun shouldProcess(parsed: ParsedBankNotification, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val iterator = recentFingerprints.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis - entry.value > windowMillis) {
                iterator.remove()
            }
        }
        val key = "${parsed.operationType}|${parsed.amount.setScale(2, RoundingMode.HALF_UP)}|${parsed.currency}"
        val previous = recentFingerprints.putIfAbsent(key, nowMillis)
        return previous == null || (nowMillis - previous > windowMillis)
    }
}

data class CategorizationMatchResult(
    val categoryId: String?,
    val matchedRuleId: String?,
    val needsReview: Boolean,
    val confidenceLabel: String
)

/**
 * Dual-tier auto-categorization engine:
 * 1. Deterministic User Smart Rules (`EXACT`, `PREFIX`, `CONTAINS` ordered by longest pattern match).
 * 2. Embedded keyword ML heuristic classifier (flags `needsReview = true` for one-tap confirmation).
 */
object SmartMerchantMatcher {

    fun classify(
        payee: String?,
        note: String?,
        rules: List<CounterpartyRuleDto>,
        categories: List<CategoryDto>
    ): CategorizationMatchResult {
        val target = listOfNotNull(payee, note).joinToString(" ").trim()
        if (target.isEmpty()) {
            return CategorizationMatchResult(
                categoryId = categories.firstOrNull()?.id,
                matchedRuleId = null,
                needsReview = true,
                confidenceLabel = "Needs Review"
            )
        }

        // Tier 1: Longest-match deterministic user rules
        val sortedRules = rules.sortedByDescending { it.counterpartyPattern.length }
        for (rule in sortedRules) {
            val pat = rule.counterpartyPattern.trim()
            if (pat.isEmpty()) continue
            val matched = when (rule.matchType.uppercase(Locale.ROOT)) {
                "EXACT" -> target.equals(pat, ignoreCase = true)
                "PREFIX" -> target.startsWith(pat, ignoreCase = true)
                else -> target.contains(pat, ignoreCase = true)
            }
            if (matched) {
                return CategorizationMatchResult(
                    categoryId = rule.categoryId,
                    matchedRuleId = rule.id,
                    needsReview = false,
                    confidenceLabel = "Smart Rule (${rule.matchType})"
                )
            }
        }

        // Tier 2: Heuristic keyword classification
        val lower = target.lowercase(Locale.ROOT)
        val matchedCategory = categories.firstOrNull { cat ->
            val catNameLower = cat.name.lowercase(Locale.ROOT)
            when {
                lower.containsAny("magnum", "small", "galmart", "arbuz", "grocer", "продукт", "супермаркет", "chocolife") ->
                    catNameLower.contains("food") || catNameLower.contains("grocer") || catNameLower.contains("продукт") || catNameLower.contains("еда")
                lower.containsAny("starbucks", "coffee", "cafe", "kfc", "burger", "dodo", "wolt", "glovo", "ресторан", "кафе") ->
                    catNameLower.contains("dining") || catNameLower.contains("food") || catNameLower.contains("кафе") || catNameLower.contains("еда")
                lower.containsAny("yandex", "uber", "indriver", "onay", "metro", "gas", "азс", "такси", "транспорт") ->
                    catNameLower.contains("transport") || catNameLower.contains("транспорт")
                lower.containsAny("netflix", "spotify", "youtube", "icloud", "chatgpt", "steam", "подписк") ->
                    catNameLower.contains("entertain") || catNameLower.contains("subscr") || catNameLower.contains("развлеч") || catNameLower.contains("подписк")
                lower.containsAny("salary", "payroll", "зарплат", "аванс", "dividend") ->
                    catNameLower.contains("salary") || catNameLower.contains("income") || catNameLower.contains("зарплат")
                else -> false
            }
        }

        return CategorizationMatchResult(
            categoryId = matchedCategory?.id ?: categories.firstOrNull()?.id,
            matchedRuleId = null,
            needsReview = true,
            confidenceLabel = if (matchedCategory != null) "Heuristic Match (Review)" else "Unclassified (Review)"
        )
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }
}
