package com.kakei.app.automation

import com.kakei.app.core.util.SystemCategories
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.CounterpartyRuleDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class ParsedBankNotification(
    val amount: BigDecimal,
    val currency: String,
    /** "EXPENSE" | "INCOME" | "CANCEL" (voids an earlier captured expense) | "REFUND" (money back, booked as income) */
    val operationType: String,
    val merchant: String,
    val rawMessage: String,
    val sourceChannel: String = "PUSH"
)

/**
 * Text helpers for bank push alerts: amount / currency normalization for rule groups and
 * redaction of balances and card numbers before an alert is stored in a record note.
 */
object BankAlertText {

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

    private val balanceTailRegex = Regex(
        """(?<![\p{L}\p{N}])(?:Доступно|Баланс|Остаток|Avail\w*|Bal(?:ance)?)(?![\p{L}])[^.]*?(?:[0-9][0-9\s .,]*\s*(?:KZT|USD|EUR|RUB|GBP|JPY|₸|\$|€|₽|£|¥|тенге|тг|руб)\.?|$)""",
        RegexOption.IGNORE_CASE
    )

    private val cardNumberRegex = Regex("""(?:\*+\s?[0-9]{4}(?![0-9])|(?<![0-9])[0-9]{4}[\s*]+[0-9*]{2,4}[\s*]+[0-9*]{4}[\s*]+[0-9]{4}(?![0-9]))""")

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

    /** Maps a currency token ("₸", "тенге", "usd") to its ISO code, or null when unknown. */
    fun normalizeCurrency(raw: String): String? {
        val key = raw.trim().uppercase(Locale.ROOT).removeSuffix(".")
        return currencyMap[key] ?: key.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }
    }

    /** Parses "2 500,50", "1,234.56", "12.000" etc. into a decimal. */
    fun parseNormalizedDecimal(raw: String): BigDecimal? {
        val compact = raw.filterNot { it.isWhitespace() || it == '\u00a0' || it == '\u202f' || it == '\u2009' }
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
}

/**
 * 5-minute sliding window deduplicator for captured push alerts.
 * Drops a re-posted (updated) notification with the same key and text, and the second
 * alert for one payment when two picked apps both announce it (e.g. a bank app and a
 * wallet). Two separate same-amount payments from one app are both kept, and each
 * cross-app pair is consumed once, so repeated equal purchases still book once each.
 * The merchant is not part of the fingerprint: different apps name it differently.
 */
class NotificationDeduplicationHelper(
    private val windowMillis: Long = 5 * 60 * 1000L
) {
    private data class Seen(val sender: String, val eventKey: String?, val rawMessage: String, val atMillis: Long)

    private val recent = HashMap<String, MutableList<Seen>>()

    /**
     * @param sender package that posted the alert.
     * @param eventKey stable id of the source event (the push notification key).
     */
    @Synchronized
    fun shouldProcess(
        parsed: ParsedBankNotification,
        sender: String,
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
            it.sender == sender && eventKey != null &&
                it.eventKey == eventKey && it.rawMessage == parsed.rawMessage
        }
        if (repost) return false

        val pair = seen.firstOrNull { it.sender != sender }
        if (pair != null) {
            seen.remove(pair)
            return false
        }

        seen.add(Seen(sender, eventKey, parsed.rawMessage, nowMillis))
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
