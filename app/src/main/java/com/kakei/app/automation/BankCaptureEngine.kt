package com.kakei.app.automation

import com.kakei.app.core.util.SystemCategories
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.CounterpartyRuleDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ParsedBankNotification(
    val amount: BigDecimal,
    val currency: String,
    val operationType: String, // "EXPENSE" | "INCOME" (outgoing transfers to people are expenses)
    val merchant: String,
    val rawMessage: String,
    val sourceChannel: String  // "SMS" | "PUSH"
)

/**
 * Dual-engine regex parser (`BankSmsPatterns`) for bank SMS and Push notifications
 * supporting major banks (Kaspi, Halyk, Freedom, Forte, Jusan, BCC, Sber, T-Bank, Revolut, Wise, GPay)
 * and multi-currency ISO 4217 symbols (`KZT`, `USD`, `EUR`, `RUB`, `GBP`, `JPY`).
 * Keywords match at word starts only, so stems ("покупк") cover word forms
 * without firing inside unrelated words ("atm" in "format").
 */
object BankSmsPatterns {

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

    private val otpKeywords = listOf("код подтверждения", "одноразовый код", "otp", "verification code", "пароль")

    // Card purchases: these force EXPENSE even when an income word ("кэшбэк") is also present.
    private val purchaseKeywords = listOf("покупк", "оплат", "purchase", "payment", "paid")

    private val expenseKeywords = purchaseKeywords + listOf(
        "списан", "снят", "spent", "debited", "charge", "withdrawal", "atm"
    )

    private val incomeKeywords = listOf(
        "пополнен", "зачислен", "поступ", "перевод от", "вам перевел", "возврат", "кэшбэк",
        "credited", "received", "deposit", "salary", "refund", "cashback"
    )

    // Outgoing transfers to people/other banks have no own target account, so they book as expenses.
    private val outgoingTransferKeywords = listOf("перевод", "transfer", "sent")

    private val amountCurrencyRegex = Regex(
        """(?<![0-9])([0-9]{1,3}(?:[  .,][0-9]{3})+(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)\s*(KZT|USD|EUR|RUB|GBP|JPY|₸|\$|€|₽|£|¥|тенге|тг|руб)(?![\p{L}\p{N}])""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val prefixSymbolRegex = Regex(
        """([\$€£¥₸₽])\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?![0-9])""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private const val MERCHANT_CHARS = """A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі0-9_.*&'"«»\-\s"""

    // Where the merchant name ends: sentence break or the balance / card tail of the alert.
    private const val MERCHANT_END =
        """(?:\.\s|,|\s+(?:Доступно|Баланс|Остаток|Avail|Bal|Карта|Card)|\s+\*?[0-9]{4}(?![0-9])|\.?$)"""

    private val prepositionMerchantRegex = Regex(
        """(?<![\p{L}\p{N}])(?:в|at|to|у|merchant|магазине)\s+([$MERCHANT_CHARS]{2,40}?)$MERCHANT_END""",
        RegexOption.IGNORE_CASE
    )

    /** "MAGNUM покупка ..." — tried last, since it also catches the bank name in "Kaspi.kz Покупка". */
    private val leadingMerchantRegex = Regex(
        """([A-Z0-9][A-Z0-9\s._*-]{2,24})\s+(?:покупка|оплата|purchase|payment)""",
        RegexOption.IGNORE_CASE
    )

    /** Text right after the amount ("Покупка: 2 500 ₸ MAGNUM ALMATY. Доступно ..."). */
    private val afterAmountMerchantRegex = Regex(
        """^[\s:.\-–—]*(?:(?:в|на|у|at|to)\s+)?([$MERCHANT_CHARS]{2,40}?)$MERCHANT_END""",
        RegexOption.IGNORE_CASE
    )

    private val balanceTailRegex = Regex(
        """(?<![\p{L}\p{N}])(?:Доступно|Баланс|Остаток|Avail\w*|Bal(?:ance)?)(?![\p{L}])[^.]*?(?:[0-9][0-9\s .,]*\s*(?:KZT|USD|EUR|RUB|GBP|JPY|₸|\$|€|₽|£|¥|тенге|тг|руб)\.?|$)""",
        RegexOption.IGNORE_CASE
    )

    private val cardNumberRegex = Regex("""(?:\*+\s?[0-9]{4}(?![0-9])|(?<![0-9])[0-9]{4}[\s*]+[0-9*]{2,4}[\s*]+[0-9*]{4}[\s*]+[0-9]{4}(?![0-9]))""")

    fun parse(
        rawText: String,
        sourceChannel: String = "SMS",
        defaultCurrency: String = "KZT"
    ): ParsedBankNotification? {
        val text = rawText.trim().replace(Regex("""\s*\n\s*"""), " ")
        if (text.length < 6) return null

        val lower = text.lowercase(Locale.ROOT)
        if (otpKeywords.any { lower.hasWord(it) }) return null

        var amount: BigDecimal? = null
        var currency: String = defaultCurrency
        var amountEnd = -1

        val match1 = amountCurrencyRegex.find(text)
        if (match1 != null) {
            amount = parseNormalizedDecimal(match1.groupValues[1])
            val rawCurr = match1.groupValues[2].uppercase(Locale.ROOT).removeSuffix(".")
            currency = currencyMap[rawCurr] ?: defaultCurrency
            amountEnd = match1.range.last + 1
        } else {
            val match2 = prefixSymbolRegex.find(text)
            if (match2 != null) {
                val sym = match2.groupValues[1].uppercase(Locale.ROOT)
                amount = parseNormalizedDecimal(match2.groupValues[2])
                currency = currencyMap[sym] ?: defaultCurrency
                amountEnd = match2.range.last + 1
            }
        }

        if (amount == null || amount <= BigDecimal.ZERO) return null

        val isPurchase = purchaseKeywords.any { lower.hasWord(it) }
        val isIncome = incomeKeywords.any { lower.hasWord(it) }
        val isExpense = isPurchase || expenseKeywords.any { lower.hasWord(it) } ||
            outgoingTransferKeywords.any { lower.hasWord(it) }
        if (!isIncome && !isExpense) return null

        val opType = if (isIncome && !isPurchase) "INCOME" else "EXPENSE"

        return ParsedBankNotification(
            amount = amount.setScale(2, RoundingMode.HALF_UP),
            currency = currency,
            operationType = opType,
            merchant = extractMerchant(text, amountEnd),
            rawMessage = text,
            sourceChannel = sourceChannel
        )
    }

    /**
     * Strips the account balance and card numbers from an alert before it is stored
     * in a record note that syncs to the server.
     */
    fun redactForNote(rawText: String): String =
        rawText
            .replace(balanceTailRegex, "")
            .replace(cardNumberRegex, "*")
            .replace(Regex("""\s{2,}"""), " ")
            .replace(Regex("""\s+([.,])"""), "$1")
            .trim()
            .trim(',', ':', '-')
            .trim()

    private fun extractMerchant(text: String, amountEnd: Int): String {
        val candidates = sequence {
            prepositionMerchantRegex.find(text)?.let { yield(it.groupValues[1]) }
            if (amountEnd in 0 until text.length) {
                afterAmountMerchantRegex.find(text.substring(amountEnd))?.let { yield(it.groupValues[1]) }
            }
            leadingMerchantRegex.find(text)?.let { yield(it.groupValues[1]) }
        }
        return candidates
            .map { it.trim().trim('.', ',', ':', '-', ' ') }
            .firstOrNull { it.length >= 2 && it.any(Char::isLetter) && !isKeywordOnly(it) }
            ?: "Bank Operation"
    }

    private fun isKeywordOnly(candidate: String): Boolean {
        val lower = candidate.lowercase(Locale.ROOT)
        return (expenseKeywords + incomeKeywords + outgoingTransferKeywords).any { lower == it || lower.startsWith(it) && lower.length <= it.length + 2 }
    }

    /** Maps a currency token ("₸", "тенге", "usd") to its ISO code, or null when unknown. */
    fun normalizeCurrency(raw: String): String? {
        val key = raw.trim().uppercase(Locale.ROOT).removeSuffix(".")
        return currencyMap[key] ?: key.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }
    }

    /** Parses "2 500,50", "1,234.56", "12.000" etc. into a decimal. */
    fun parseNormalizedDecimal(raw: String): BigDecimal? {
        val compact = raw.replace(" ", "").replace(" ", "")
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
            compact.count { it == '.' } > 1 -> compact.replace(".", "")
            compact.contains(".") && compact.substringAfterLast('.').length == 3 -> compact.replace(".", "")
            else -> compact
        }
        return runCatching { BigDecimal(normalized) }.getOrNull()
    }

    /** Keyword match anchored at a word start ("покупк" matches "Покупка", not "запокупкой"). */
    private fun String.hasWord(keyword: String): Boolean {
        var from = 0
        while (true) {
            val idx = indexOf(keyword, from)
            if (idx < 0) return false
            if (idx == 0 || !this[idx - 1].isLetterOrDigit()) return true
            from = idx + 1
        }
    }
}

/**
 * Cross-channel 5-minute sliding window deduplicator.
 * Drops the second alert for one payment when both SMS and Push fire for it, and drops
 * a re-posted (updated) push with the same notification key and text. Two separate
 * same-amount payments on one channel are both kept, and each SMS/Push pair is
 * consumed once, so repeated equal purchases still book once each.
 * The merchant is not part of the fingerprint: SMS and Push of one payment name it differently.
 */
class NotificationDeduplicationHelper(
    private val windowMillis: Long = 5 * 60 * 1000L
) {
    private data class Seen(val channel: String, val eventKey: String?, val rawMessage: String, val atMillis: Long)

    private val recent = HashMap<String, MutableList<Seen>>()

    /**
     * @param eventKey stable id of the source event (the push notification key), or null for SMS.
     */
    @Synchronized
    fun shouldProcess(
        parsed: ParsedBankNotification,
        nowMillis: Long = System.currentTimeMillis(),
        eventKey: String? = null
    ): Boolean {
        val iterator = recent.values.iterator()
        while (iterator.hasNext()) {
            val list = iterator.next()
            list.removeAll { nowMillis - it.atMillis > windowMillis }
            if (list.isEmpty()) iterator.remove()
        }
        val key = "${parsed.operationType}|${parsed.amount.setScale(2, RoundingMode.HALF_UP)}|${parsed.currency}"
        val seen = recent.getOrPut(key) { mutableListOf() }

        val repost = seen.any {
            it.channel == parsed.sourceChannel && eventKey != null &&
                it.eventKey == eventKey && it.rawMessage == parsed.rawMessage
        }
        if (repost) return false

        val pair = seen.firstOrNull { it.channel != parsed.sourceChannel }
        if (pair != null) {
            seen.remove(pair)
            return false
        }

        seen.add(Seen(parsed.sourceChannel, eventKey, parsed.rawMessage, nowMillis))
        return true
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
 *    Keywords route to the backend's system category ids (mirroring `services/categorization`),
 *    falling back to name heuristics for local categories. Nothing matched leaves the category empty.
 */
object SmartMerchantMatcher {

    /** Server keyword table: first match wins, so specific merchants precede generic words. */
    private val expenseKeywordTable: List<Pair<List<String>, String>> = listOf(
        listOf("starbucks", "coffee", "espresso", "costa", "roastery") to SystemCategories.COFFEE,
        listOf("doordash", "ubereats", "deliveroo", "wolt", "glovo", "yandex eda", "яндекс еда") to SystemCategories.FOOD_DELIVERY,
        listOf("atm withdrawal", "cash withdrawal", "банкомат", "снятие наличных") to SystemCategories.CASH_WITHDRAWAL,
        listOf(
            "walmart", "whole foods", "carrefour", "tesco", "aldi", "lidl", "grocery", "supermarket",
            "magnum", "small", "galmart", "arbuz", "продукт", "супермаркет"
        ) to SystemCategories.GROCERIES,
        listOf("mcdonald", "burger", "pizza", "restaurant", "bistro", "sushi", "kfc", "dodo", "ресторан", "кафе") to SystemCategories.RESTAURANTS,
        listOf("uber", "lyft", "bolt", "yandex go", "indriver", "taxi", "cab", "такси") to SystemCategories.TAXI,
        listOf("parking", "парковка") to SystemCategories.PARKING,
        listOf("car wash", "auto service", "tire service", "мойка", "шиномонтаж") to SystemCategories.CAR_MAINTENANCE,
        listOf("shell", "bp", "chevron", "exxon", "fuel", "petrol", "supercharger", "азс") to SystemCategories.FUEL,
        listOf("metro", "subway", "transit", "train", "bus", "railway", "onay") to SystemCategories.TRANSIT,
        listOf("openai", "chatgpt", "claude", "anthropic", "icloud", "google one", "dropbox") to SystemCategories.AI_CLOUD,
        listOf("steam", "gog.com", "epic games", "playstation", "xbox", "nintendo", "blizzard") to SystemCategories.GAMES,
        listOf("netflix", "spotify", "youtube", "apple music", "кинопоиск") to SystemCategories.STREAMING,
        listOf("kindle", "audible", "webtoon", "remanga", "литрес") to SystemCategories.BOOKS,
        listOf("google play", "app store") to SystemCategories.APPS,
        listOf("rent", "mortgage", "landlord", "hoa") to SystemCategories.RENT,
        listOf("internet", "telecom", "mobile", "beeline", "kcell", "tele2") to SystemCategories.PHONE_INTERNET,
        listOf("electric", "water", "utility", "gas bill") to SystemCategories.UTILITIES,
        listOf("pharmacy", "cvs", "walgreens", "clinic", "hospital", "dental", "doctor", "аптека") to SystemCategories.PHARMACY,
        listOf("gym", "fitness", "yoga", "crossfit") to SystemCategories.FITNESS,
        listOf("apple store", "best buy", "amazon", "electronics") to SystemCategories.ELECTRONICS,
        listOf("zara", "uniqlo", "h&m", "nike", "adidas", "apparel") to SystemCategories.CLOTHING,
        listOf("booking.com", "airbnb", "hotel", "hostel", "отель", "гостиница") to SystemCategories.ACCOMMODATION,
        listOf("airlines", "airways", "aviasales", "air astana", "аэрофлот", "авиабилет") to SystemCategories.TRAVEL_TICKETS,
        listOf("university", "tuition", "университет", "школа") to SystemCategories.TUITION,
        listOf("insurance", "страхов") to SystemCategories.INSURANCE,
        listOf("bank fee", "commission", "комиссия") to SystemCategories.BANK_FEES,
        listOf("tax", "налог") to SystemCategories.TAXES
    )

    private val incomeKeywordTable: List<Pair<List<String>, String>> = listOf(
        listOf("refund", "cashback", "chargeback", "возврат", "кэшбэк") to SystemCategories.REFUNDS,
        listOf("dividend", "interest", "дивиденд") to SystemCategories.INTEREST,
        listOf("salary", "payroll", "wage", "зарплат", "аванс") to SystemCategories.SALARY
    )

    fun classify(
        payee: String?,
        note: String?,
        rules: List<CounterpartyRuleDto>,
        categories: List<CategoryDto>,
        recordType: String? = null
    ): CategorizationMatchResult {
        val target = listOfNotNull(payee, note).joinToString(" ").trim()
        if (target.isEmpty()) {
            return CategorizationMatchResult(
                categoryId = null,
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

        // Tier 2: Heuristic keyword classification, system categories first
        val lower = target.lowercase(Locale.ROOT)
        val knownIds = categories.mapTo(HashSet()) { it.id }
        val tables = when (recordType?.uppercase(Locale.ROOT)) {
            "INCOME" -> listOf(incomeKeywordTable)
            "EXPENSE" -> listOf(expenseKeywordTable)
            else -> listOf(incomeKeywordTable, expenseKeywordTable)
        }
        val systemMatch = tables.asSequence()
            .flatMap { it.asSequence() }
            .firstOrNull { (keywords, _) -> keywords.any { lower.hasWord(it) } }
            ?.second
            ?.takeIf { it in knownIds }

        val matchedCategoryId = systemMatch ?: categories.firstOrNull { cat ->
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
        }?.id

        return CategorizationMatchResult(
            categoryId = matchedCategoryId,
            matchedRuleId = null,
            needsReview = true,
            confidenceLabel = if (matchedCategoryId != null) "Heuristic Match (Review)" else "Unclassified (Review)"
        )
    }

    /**
     * Keyword match anchored at a word start, so short keywords do not fire inside other
     * words of a raw bank message ("rent" in "current", "bp" in "abp").
     */
    private fun String.hasWord(keyword: String): Boolean {
        var from = 0
        while (true) {
            val idx = indexOf(keyword, from)
            if (idx < 0) return false
            if (idx == 0 || !this[idx - 1].isLetterOrDigit()) return true
            from = idx + 1
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }
}
