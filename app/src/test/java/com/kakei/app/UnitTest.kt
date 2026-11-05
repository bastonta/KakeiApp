package com.kakei.app

import com.kakei.app.automation.BankSmsPatterns
import com.kakei.app.automation.ExpenseReminderScheduler
import com.kakei.app.automation.NotificationDeduplicationHelper
import com.kakei.app.automation.PushCaptureRule
import com.kakei.app.automation.PushRuleEngine
import com.kakei.app.automation.PushRuleResult
import com.kakei.app.automation.SmartMerchantMatcher
import com.kakei.app.core.security.BackupCryptoHelper
import com.kakei.app.core.security.PasskeyHelper
import com.kakei.app.core.util.ConvertedTotal
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.core.util.ExchangeRateBook
import com.kakei.app.core.util.HashDedupCalculator
import com.kakei.app.core.util.SystemCategories
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.BudgetEnvelopeDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.CounterpartyRuleDto
import com.kakei.app.data.remote.dto.ImportStatementRequest
import com.kakei.app.data.remote.dto.PasskeyCeremonyResponse
import com.kakei.app.data.remote.dto.PlannedObligationDto
import com.kakei.app.data.remote.dto.SplitDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.domain.calculators.FinancialCalculators
import com.kakei.app.domain.calculators.ObligationSchedule
import com.kakei.app.domain.calculators.PeriodStatistics
import com.kakei.app.domain.calculators.StatsBucketSize
import com.kakei.app.domain.calculators.StatsPeriod
import com.kakei.app.ui.screens.CategoryTree
import com.kakei.app.ui.screens.detailsSummary
import com.kakei.app.ui.screens.frequentCategoryIds
import com.kakei.app.ui.screens.searchCategories
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class UnitTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun hashDedupCalculator_matchesServerCanonicalV2Vector() {
        val input = HashDedupCalculator.canonicalInput(
            accountId = "00000000-0000-0000-0000-000000000000",
            amount = BigDecimal("12.50"),
            currency = "usd",
            bookingDate = Instant.ofEpochSecond(1_700_000_000L),
            payee = " Starbucks "
        )
        assertEquals("00000000-0000-0000-0000-000000000000|12.5|USD|2023-11-14T22:13:20Z|starbucks", input)
        // Fixed digest computed independently: printf '%s' '<input>' | sha256sum
        val hash = HashDedupCalculator.computeTransactionHash(
            accountId = "00000000-0000-0000-0000-000000000000",
            amount = BigDecimal("12.50"),
            currency = "usd",
            bookingDateIso = "2023-11-14T22:13:20.987654Z", // sub-second part is truncated
            payee = " Starbucks "
        )
        assertEquals("2c666f7bcb2b0ddb04911a3fbc80f483611429af8537fe14cb93dddf783182c6", hash)
    }

    @Test
    fun hashDedupCalculator_normalizesAmountsLikeRustDecimal() {
        fun amountPart(amount: String) = HashDedupCalculator.canonicalInput(
            "0191C1E2-0000-7000-8000-000000000001", BigDecimal(amount), "kzt", Instant.EPOCH, null
        ).split("|")
        assertEquals("0191c1e2-0000-7000-8000-000000000001", amountPart("1")[0])
        assertEquals("0", amountPart("0.0000")[1])
        assertEquals("1500", amountPart("-1500.0000")[1])
        assertEquals("0.1234", amountPart("0.12345")[1]) // banker's rounding: ...45 -> ...4
        assertEquals("0.1236", amountPart("0.12355")[1])
        assertEquals("1970-01-01T00:00:00Z", amountPart("1")[3])
        assertEquals("", amountPart("1")[4])
    }

    @Test
    fun exchangeRateBook_neverInventsRatesAndAppliesOverrides() {
        val book = ExchangeRateBook(
            unitsPerUsd = mapOf("USD" to BigDecimal("1"), "KZT" to BigDecimal("500"), "EUR" to BigDecimal("0.8")),
            overrides = mapOf(("USD" to "KZT") to BigDecimal("505.5"))
        )
        assertEquals(0, BigDecimal("505.5").compareTo(book.rate("USD", "KZT")))
        // Inverse override is used inverted.
        assertEquals(0, BigDecimal("10.1100").compareTo(book.convert(BigDecimal("5110.605"), "KZT", "USD")))
        // Cross rate from market rates: KZT per EUR = 500 / 0.8.
        assertEquals(0, BigDecimal("625").compareTo(book.rate("EUR", "KZT")))
        assertNull(book.rate("GBP", "KZT"))
        assertNull(book.convert(BigDecimal.TEN, "KZT", "JPY"))

        val total = ConvertedTotal.of(
            listOf(BigDecimal("1000") to "KZT", BigDecimal("2") to "USD", BigDecimal("7") to "GBP"),
            "KZT",
            book
        )
        assertEquals(0, BigDecimal("2011").compareTo(total.total))
        assertEquals(mapOf("GBP" to BigDecimal("7")), total.unconverted)
    }

    @Test
    fun bankSmsPatterns_parsesKaspiHalykAndIgnoresOtp() {
        val kaspi = BankSmsPatterns.parse(
            "Kaspi Gold: Покупка 4 850 ₸ в Magnum Cash & Carry. Доступно: 607 550 ₸",
            sourceChannel = "SMS"
        )
        assertNotNull(kaspi)
        assertEquals(BigDecimal("4850.00"), kaspi!!.amount)
        assertEquals("KZT", kaspi.currency)
        assertEquals("EXPENSE", kaspi.operationType)
        assertTrue(kaspi.merchant.contains("Magnum", ignoreCase = true))

        val income = BankSmsPatterns.parse(
            "Freedom Bank: Пополнение 250 USD зарплатный перевод",
            sourceChannel = "PUSH"
        )
        assertNotNull(income)
        assertEquals(BigDecimal("250.00"), income!!.amount)
        assertEquals("USD", income.currency)
        assertEquals("INCOME", income.operationType)

        val otp = BankSmsPatterns.parse("Никому не сообщайте код подтверждения 4821 для оплаты 5000 KZT")
        assertNull(otp)
    }

    @Test
    fun bankSmsPatterns_parsesKaspiPushFormats() {
        val purchase = BankSmsPatterns.parse(
            "Kaspi.kz Покупка: 2 500,00 ₸ MAGNUM AL-FARABI. Доступно: 100 000 ₸",
            sourceChannel = "PUSH"
        )!!
        assertEquals(BigDecimal("2500.00"), purchase.amount)
        assertEquals("EXPENSE", purchase.operationType)
        assertEquals("MAGNUM AL-FARABI", purchase.merchant)

        val outgoing = BankSmsPatterns.parse("Kaspi.kz Перевод 5 000 ₸ Айдар Н.", sourceChannel = "PUSH")!!
        assertEquals("EXPENSE", outgoing.operationType)
        assertEquals(BigDecimal("5000.00"), outgoing.amount)
        assertEquals("Айдар Н", outgoing.merchant)

        val incoming = BankSmsPatterns.parse("Kaspi.kz Перевод от Айдар Н. 5 000 ₸", sourceChannel = "PUSH")!!
        assertEquals("INCOME", incoming.operationType)

        val received = BankSmsPatterns.parse("Вам поступил перевод 12 000 ₸", sourceChannel = "PUSH")!!
        assertEquals("INCOME", received.operationType)

        val cashbackOnPurchase = BankSmsPatterns.parse("Покупка 1 000 ₸ в Wolt, кэшбэк 50 ₸")!!
        assertEquals("EXPENSE", cashbackOnPurchase.operationType)
        assertEquals(BigDecimal("1000.00"), cashbackOnPurchase.amount)
    }

    @Test
    fun bankSmsPatterns_matchesKeywordsAtWordStartOnly() {
        // "format" / "photp" must not be read as "atm" / "otp"
        assertNull(BankSmsPatterns.parse("Новый format файла, размер 5000 KZT"))
        assertNotNull(BankSmsPatterns.parse("Снятие наличных ATM 20 000 ₸ Halyk"))
        // the card tail "*1234" is not part of the amount, and "тг" needs a word end
        val card = BankSmsPatterns.parse("Карта *1234 оплата 990 тг. в Small")!!
        assertEquals(BigDecimal("990.00"), card.amount)
        assertEquals("KZT", card.currency)
        assertEquals("Small", card.merchant)
    }

    @Test
    fun pushRuleEngine_extractsNamedGroupsAndIgnoresUnmatched() {
        val kaspi = PushCaptureRule(
            id = "r1",
            packageName = "kz.kaspi.mobile",
            name = "Kaspi purchase",
            pattern = """Покупка:?\s*(?<amount>[0-9][0-9 .,]*)\s*(?<currency>₸|KZT)\s+(?<merchant>[^.]+)"""
        )
        val text = "Kaspi.kz\nПокупка: 2 500,00 ₸ MAGNUM AL-FARABI. Доступно: 100 000 ₸"
        val hit = PushRuleEngine.match(listOf(kaspi), "kz.kaspi.mobile", text, "USD")
        assertNotNull(hit)
        val parsed = hit!!.second
        assertEquals(BigDecimal("2500.00"), parsed.amount)
        assertEquals("KZT", parsed.currency)
        assertEquals("EXPENSE", parsed.operationType)
        assertEquals("MAGNUM AL-FARABI", parsed.merchant)
        assertEquals("PUSH", parsed.sourceChannel)

        // Rules belong to their own app; disabled rules and non-matching text are ignored.
        assertNull(PushRuleEngine.match(listOf(kaspi), "com.other.bank", text, "USD"))
        assertNull(PushRuleEngine.match(listOf(kaspi.copy(enabled = false)), "kz.kaspi.mobile", text, "USD"))
        assertNull(PushRuleEngine.match(listOf(kaspi), "kz.kaspi.mobile", "Ваш код 1234", "USD"))
    }

    @Test
    fun pushRuleEngine_fallsBackToRuleCurrencyAndName() {
        val rule = PushCaptureRule(
            id = "r2",
            packageName = "app",
            name = "Salary",
            pattern = """зачислено (?<amount>[0-9 ]+)""",
            operationType = "INCOME",
            currency = "eur"
        )
        val result = PushRuleEngine.apply(rule, "Вам зачислено 1 200", "KZT")
        assertTrue(result is PushRuleResult.Matched)
        val parsed = (result as PushRuleResult.Matched).parsed
        assertEquals(BigDecimal("1200.00"), parsed.amount)
        assertEquals("EUR", parsed.currency)
        assertEquals("INCOME", parsed.operationType)
        assertEquals("Salary", parsed.merchant)

        assertEquals("KZT", (PushRuleEngine.apply(rule.copy(currency = null), "зачислено 5", "KZT") as PushRuleResult.Matched).parsed.currency)
    }

    @Test
    fun pushRuleEngine_validatesPatterns() {
        assertEquals(PushRuleResult.Reason.NO_AMOUNT_GROUP, PushRuleEngine.validate("Покупка ([0-9]+)"))
        assertEquals(PushRuleResult.Reason.BAD_REGEX, PushRuleEngine.validate("(?<amount>[0-9+"))
        assertNull(PushRuleEngine.validate("(?<amount>[0-9]+)"))
        val bad = PushCaptureRule("r3", "app", "", "(?<amount>[a-z]+)")
        assertEquals(
            PushRuleResult.Invalid(PushRuleResult.Reason.BAD_AMOUNT),
            PushRuleEngine.apply(bad, "abc", "KZT")
        )
    }

    @Test
    fun bankSmsPatterns_redactsBalanceAndCardFromNote() {
        assertEquals(
            "Kaspi Gold: Покупка 4 850 ₸ в Magnum.",
            BankSmsPatterns.redactForNote("Kaspi Gold: Покупка 4 850 ₸ в Magnum. Доступно: 607 550 ₸")
        )
        val redacted = BankSmsPatterns.redactForNote("Halyk *4321 оплата 1 200 KZT в KFC. Баланс 50 000 KZT")
        assertFalse(redacted.contains("4321"))
        assertFalse(redacted.contains("50 000"))
        assertTrue(redacted.contains("1 200 KZT"))
    }

    @Test
    fun notificationDeduplicationHelper_keepsRepeatedPurchasesOnOneChannel() {
        val dedup = NotificationDeduplicationHelper()
        val coffee = BankSmsPatterns.parse("Покупка 1 500 ₸ в Starbucks", sourceChannel = "PUSH")!!
        val t0 = 1_700_000_000_000L
        assertTrue(dedup.shouldProcess(coffee, t0, eventKey = "kaspi|1"))
        // a second coffee a minute later is a separate payment
        assertTrue(dedup.shouldProcess(coffee, t0 + 60_000L, eventKey = "kaspi|2"))
        // the first notification re-posted (updated) is not
        assertFalse(dedup.shouldProcess(coffee, t0 + 90_000L, eventKey = "kaspi|1"))

        // each SMS pairs with one push only
        val sms = BankSmsPatterns.parse("Покупка 1 500 ₸ в Starbucks", sourceChannel = "SMS")!!
        assertFalse(dedup.shouldProcess(sms, t0 + 100_000L))
        assertFalse(dedup.shouldProcess(sms, t0 + 110_000L))
        assertTrue(dedup.shouldProcess(sms, t0 + 120_000L))
    }

    @Test
    fun notificationDeduplicationHelper_drops5MinuteDuplicatesAcrossSmsAndPush() {
        val dedup = NotificationDeduplicationHelper(windowMillis = 5 * 60 * 1000L)
        val smsAlert = BankSmsPatterns.parse("Kaspi Gold: Покупка 3 200 ₸ в Yandex Go")!!
        val pushAlert = BankSmsPatterns.parse("Оплата 3200 KZT в Yandex Go", sourceChannel = "PUSH")!!

        val t0 = 1_700_000_000_000L
        assertTrue(dedup.shouldProcess(smsAlert, t0))
        // 30 seconds later via PUSH -> duplicate dropped
        assertFalse(dedup.shouldProcess(pushAlert, t0 + 30_000L))
        // 6 minutes later -> allowed again
        assertTrue(dedup.shouldProcess(pushAlert, t0 + 6 * 60 * 1000L))
    }

    @Test
    fun smartMerchantMatcher_prioritizesLongestDeterministicRule() {
        val categories = listOf(
            CategoryDto(id = "cat-food", name = "Groceries"),
            CategoryDto(id = "cat-coffee", name = "Coffee")
        )
        val rules = listOf(
            CounterpartyRuleDto(id = "r1", counterpartyPattern = "Star", matchType = "PREFIX", categoryId = "cat-food"),
            CounterpartyRuleDto(id = "r2", counterpartyPattern = "Starbucks", matchType = "PREFIX", categoryId = "cat-coffee")
        )

        val match = SmartMerchantMatcher.classify("Starbucks Reserve Almaty", null, rules, categories)
        assertEquals("cat-coffee", match.categoryId)
        assertEquals("r2", match.matchedRuleId)
        assertFalse(match.needsReview)
    }

    @Test
    fun financialCalculators_evaluatesBudgetsHealthScoreForecastAndImports() {
        val today = LocalDate.of(2026, 10, 18)
        val accounts = listOf(
            AccountDto(
                id = "acc-1",
                name = "Main KZT",
                accountType = "CHECKING",
                currency = "KZT",
                initialBalance = BigDecimal("500000"),
                currentBalance = BigDecimal("500000")
            )
        )
        val transactions = listOf(
            TransactionDto(
                id = "tx-1",
                accountId = "acc-1",
                categoryId = "cat-food",
                type = "INCOME",
                amount = BigDecimal("600000"),
                currency = "KZT",
                bookingDate = "2026-10-05T10:00:00Z",
                payee = "Payroll"
            ),
            TransactionDto(
                id = "tx-2",
                accountId = "acc-1",
                categoryId = "cat-food",
                type = "EXPENSE",
                amount = BigDecimal("90000"),
                currency = "KZT",
                bookingDate = "2026-10-10T12:00:00Z",
                payee = "Magnum"
            )
        )
        val budgets = listOf(
            BudgetEnvelopeDto(
                id = "b-1",
                name = "Food Envelope",
                limitAmount = BigDecimal("100000"),
                currency = "KZT",
                categoryIds = listOf("cat-food")
            )
        )

        val evaluatedBudgets = FinancialCalculators.evaluateBudgets(budgets, transactions, today)
        assertEquals(BigDecimal("90000.00"), evaluatedBudgets.first().spentAmount)
        assertEquals("WARNING_90", evaluatedBudgets.first().alertLevel)
        assertTrue(evaluatedBudgets.first().dailyBurnRateLimit > BigDecimal.ZERO)

        val health = FinancialCalculators.calculateHealthScore(
            transactions = transactions,
            budgets = evaluatedBudgets,
            lendingRecords = emptyList(),
            baseCurrency = "KZT",
            today = today
        )
        assertTrue(health.overallScore in 70..100)

        val obligations = listOf(
            PlannedObligationDto(
                id = "ob-1",
                accountId = "acc-1",
                title = "Rent",
                type = "EXPENSE",
                amount = BigDecimal("150000"),
                currency = "KZT",
                frequency = "MONTHLY",
                nextDueDate = "2026-10-25"
            )
        )
        val forecast = FinancialCalculators.calculateCashFlowForecast(accounts, obligations, "KZT", 30, today)
        assertEquals(BigDecimal("350000.00"), forecast.forecastedEndBalance)
        assertFalse(forecast.liquidityDeficitAlert)

        // CSV & OFX Import Deduplication test
        val csvContent = """
            Date,Amount,Payee,Note
            2026-10-15,-4500,Starbucks,Latte
            2026-10-15,-4500,Starbucks,Duplicate row
        """.trimIndent()
        val (importResp, createdItems) = FinancialCalculators.parseStatementImport(
            account = accounts.first(),
            existingHashes = emptySet(),
            request = ImportStatementRequest(accountId = "acc-1", format = "csv", content = csvContent)
        )
        assertEquals(2, importResp.totalParsed)
        assertEquals(1, importResp.importedCount)
        assertEquals(1, importResp.duplicateSkippedCount)
        assertEquals(1, createdItems.size)
    }

    @Test
    fun dtoSerialization_andBackupCryptoRoundTrip() {
        val tx = TransactionDto(
            id = "tx-100",
            accountId = "acc-1",
            type = "EXPENSE",
            amount = BigDecimal("12345.6700"),
            currency = "KZT",
            bookingDate = "2026-10-18T12:00:00Z",
            payee = "Merchant",
            splits = listOf(SplitDto(id = "s1", categoryId = "c1", amount = BigDecimal("12345.6700")))
        )
        val encoded = json.encodeToString(tx)
        assertTrue(encoded.contains("\"12345.6700\""))
        val decoded = json.decodeFromString<TransactionDto>(encoded)
        assertEquals(tx.amount, decoded.amount)

        val encrypted = BackupCryptoHelper.encryptBackup(encoded, "secret-passphrase")
        val decrypted = BackupCryptoHelper.decryptBackup(encrypted, "secret-passphrase")
        assertEquals(encoded, decrypted)
    }

    @Test
    fun splitEvenly_keepsTheRemainder() {
        val shares = FinancialCalculators.splitEvenly(BigDecimal("100.00"), 3, 2)
        assertEquals(listOf(BigDecimal("33.34"), BigDecimal("33.33"), BigDecimal("33.33")), shares)
        assertEquals(BigDecimal("100.00"), shares.fold(BigDecimal.ZERO, BigDecimal::add))

        val yen = FinancialCalculators.splitEvenly(BigDecimal("1000"), 6, FinancialCalculators.currencyScale("JPY"))
        assertEquals(BigDecimal("1000"), yen.fold(BigDecimal.ZERO, BigDecimal::add))
        assertTrue(yen.all { it.scale() == 0 })
    }

    @Test
    fun formatRate_usesAmountSeparatorsAndShortPrecision() {
        assertEquals("521.74", CurrencyFormatter.formatRate(BigDecimal("521.7391")))
        assertEquals("480", CurrencyFormatter.formatRate(BigDecimal("480.0000")))
        assertEquals("1 234.5", CurrencyFormatter.formatRate(BigDecimal("1234.5")))
        assertEquals("0.0021", CurrencyFormatter.formatRate(BigDecimal("0.00208333")))
    }

    private val systemCategoryTree = listOf(
        CategoryDto(id = "01a19376-6e46-7453-9394-6fec35072a36", name = "Food & Dining"),
        CategoryDto(id = SystemCategories.GROCERIES, parentId = "01a19376-6e46-7453-9394-6fec35072a36", name = "Groceries"),
        CategoryDto(id = SystemCategories.FOOD_DELIVERY, parentId = "01a19376-6e46-7453-9394-6fec35072a36", name = "Food Delivery"),
        CategoryDto(id = "01a19376-6e5d-78d5-b34c-3c7b4fb284ad", name = "Transportation"),
        CategoryDto(id = SystemCategories.TAXI, parentId = "01a19376-6e5d-78d5-b34c-3c7b4fb284ad", name = "Taxi & Rideshare"),
        CategoryDto(id = "01a19376-6e69-77c7-adcc-3d2ed4989165", name = "Car"),
        CategoryDto(id = SystemCategories.FUEL, parentId = "01a19376-6e69-77c7-adcc-3d2ed4989165", name = "Fuel & Charging"),
        CategoryDto(id = SystemCategories.PARKING, parentId = "01a19376-6e69-77c7-adcc-3d2ed4989165", name = "Parking"),
        CategoryDto(id = "01a19376-6ee1-7ca8-a428-dac83e2adf60", name = "Digital Services"),
        CategoryDto(id = SystemCategories.STREAMING, parentId = "01a19376-6ee1-7ca8-a428-dac83e2adf60", name = "Streaming & Media"),
        CategoryDto(id = SystemCategories.AI_CLOUD, parentId = "01a19376-6ee1-7ca8-a428-dac83e2adf60", name = "AI & Cloud"),
        CategoryDto(id = SystemCategories.GAMES, parentId = "01a19376-6ee1-7ca8-a428-dac83e2adf60", name = "Games"),
        CategoryDto(id = "01a19376-6f1e-720e-8260-71fb47fda07e", name = "Finance"),
        CategoryDto(id = SystemCategories.TAXES, parentId = "01a19376-6f1e-720e-8260-71fb47fda07e", name = "Taxes"),
        CategoryDto(id = SystemCategories.INCOME, name = "Income"),
        CategoryDto(id = SystemCategories.SALARY, parentId = SystemCategories.INCOME, name = "Salary & Payroll"),
        CategoryDto(id = SystemCategories.INTEREST, parentId = SystemCategories.INCOME, name = "Interest & Dividends")
    )

    @Test
    fun evaluateBudgets_parentCategoryIncludesSubcategories() {
        val today = LocalDate.of(2026, 10, 18)
        fun expense(id: String, categoryId: String, amount: String) = TransactionDto(
            id = id, accountId = "acc-1", categoryId = categoryId, type = "EXPENSE",
            amount = BigDecimal(amount), currency = "KZT", bookingDate = "2026-10-10T12:00:00Z"
        )
        val transactions = listOf(
            expense("tx-fuel", SystemCategories.FUEL, "20000"),
            expense("tx-parking", SystemCategories.PARKING, "1500"),
            expense("tx-taxi", SystemCategories.TAXI, "3000")
        )
        val budgets = listOf(
            BudgetEnvelopeDto(
                id = "b-car",
                name = "Car",
                limitAmount = BigDecimal("50000"),
                currency = "KZT",
                categoryIds = listOf("01a19376-6e69-77c7-adcc-3d2ed4989165")
            )
        )

        val withTree = FinancialCalculators.evaluateBudgets(budgets, transactions, today, categories = systemCategoryTree)
        assertEquals(BigDecimal("21500.00"), withTree.first().spentAmount)

        // Without the tree only exact category ids count (no subcategory is known).
        val withoutTree = FinancialCalculators.evaluateBudgets(budgets, transactions, today)
        assertEquals(BigDecimal("0.00"), withoutTree.first().spentAmount)
    }

    @Test
    fun smartMerchantMatcher_routesKeywordsToSystemSubcategories() {
        fun cat(payee: String, type: String = "EXPENSE") =
            SmartMerchantMatcher.classify(payee, null, emptyList(), systemCategoryTree, type).categoryId

        assertEquals(SystemCategories.FOOD_DELIVERY, cat("Wolt"))
        assertEquals(SystemCategories.TAXI, cat("Яндекс такси"))
        assertEquals(SystemCategories.AI_CLOUD, cat("Claude.ai subscription"))
        assertEquals(SystemCategories.GAMES, cat("STEAM PURCHASE"))
        assertEquals(SystemCategories.STREAMING, cat("YouTube Premium"))
        assertEquals(SystemCategories.TAXES, cat("Налоги ИП"))
        assertEquals(SystemCategories.PARKING, cat("Almaty Parking"))
        assertEquals(SystemCategories.INTEREST, cat("Deposit interest", "INCOME"))
        assertEquals(SystemCategories.SALARY, cat("Salary October", "INCOME"))
        // "rent" must not fire inside "current"
        assertNull(cat("Current balance 1000 KZT"))
    }

    @Test
    fun smartMerchantMatcher_leavesUnknownMerchantUncategorized() {
        val match = SmartMerchantMatcher.classify("ТОО Ромашка", null, emptyList(), systemCategoryTree, "EXPENSE")
        assertNull(match.categoryId)
        assertTrue(match.needsReview)
        // A system id that is not loaded locally is not returned.
        val noDelivery = systemCategoryTree.filter { it.id != SystemCategories.FOOD_DELIVERY }
        val fallback = SmartMerchantMatcher.classify("Wolt", null, emptyList(), noDelivery, "EXPENSE")
        assertTrue(fallback.categoryId != SystemCategories.FOOD_DELIVERY)
    }

    @Test
    fun categoryTree_groupsSubcategoriesUnderParents() {
        val orphan = CategoryDto(id = "orphan", parentId = "missing-parent", name = "Orphan")
        val tree = CategoryTree.build(systemCategoryTree + orphan, "en")
        assertTrue(tree.roots.none { it.parentId != null && it.id != "orphan" })
        assertTrue(tree.roots.any { it.id == "orphan" })
        assertEquals(
            listOf("AI & Cloud", "Games", "Streaming & Media"),
            tree.children["01a19376-6ee1-7ca8-a428-dac83e2adf60"]!!.map { it.name }
        )
        assertEquals("01a19376-6e69-77c7-adcc-3d2ed4989165", tree.rootOf(SystemCategories.FUEL))
        assertEquals("01a19376-6e69-77c7-adcc-3d2ed4989165", tree.rootOf("01a19376-6e69-77c7-adcc-3d2ed4989165"))
    }

    @Test
    fun passkeyChallengeUnwrapsWebauthnRsPublicKeyOptions() {
        val ceremony = Json.decodeFromString<PasskeyCeremonyResponse>(
            """{"ceremonyId":"0190a7c2-0000-7000-8000-000000000001","challenge":{"publicKey":{"challenge":"WaVSMQD-YQrg6LpC6IHxahSXltu1Osc5N4ubLNZTRCI","rpId":"kakei.app","userVerification":"preferred"},"mediation":"conditional"}}"""
        )
        val options = Json.parseToJsonElement(PasskeyHelper.extractPublicKeyOptions(ceremony.challenge))
        assertEquals(
            Json.parseToJsonElement("""{"challenge":"WaVSMQD-YQrg6LpC6IHxahSXltu1Osc5N4ubLNZTRCI","rpId":"kakei.app","userVerification":"preferred"}"""),
            options
        )

        // Bare options (no publicKey wrapper) pass through unchanged
        val bare = """{"challenge":"abc","rpId":"kakei.app"}"""
        assertEquals(Json.parseToJsonElement(bare), Json.parseToJsonElement(PasskeyHelper.extractPublicKeyOptions(Json.parseToJsonElement(bare))))
    }

    @Test
    fun monthlyScheduleOn31stClampsToMonthEndWithoutDrift() {
        val sched = ObligationSchedule("MONTHLY", LocalDate.of(2027, 1, 10), dayOfMonth = 31)
        val jan = sched.firstOnOrAfter(sched.startDate)!!
        assertEquals(LocalDate.of(2027, 1, 31), jan)
        val feb = sched.nextAfter(jan)!!
        assertEquals(LocalDate.of(2027, 2, 28), feb)
        assertEquals(LocalDate.of(2027, 3, 31), sched.nextAfter(feb))
    }

    @Test
    fun everyNDaysScheduleIgnoresMonthLength() {
        val sched = ObligationSchedule("EVERY_N_DAYS", LocalDate.of(2027, 1, 31), intervalDays = 30)
        assertEquals(LocalDate.of(2027, 1, 31), sched.firstOnOrAfter(sched.startDate))
        assertEquals(LocalDate.of(2027, 3, 2), sched.nextAfter(LocalDate.of(2027, 1, 31)))
        assertNull(ObligationSchedule("ONE_OFF", LocalDate.of(2027, 1, 31)).nextAfter(LocalDate.of(2027, 1, 31)))
    }

    @Test
    fun testFrequentCategoriesCountsOnlyMatchingTypeAndKnownCategories() {
        val categories = listOf(
            CategoryDto(id = "food", name = "Food"),
            CategoryDto(id = "taxi", name = "Taxi"),
            CategoryDto(id = "salary", name = "Salary")
        )
        fun tx(id: String, type: String, categoryId: String?) = TransactionDto(
            id = id, accountId = "a", categoryId = categoryId, type = type,
            amount = BigDecimal.TEN, currency = "KZT", bookingDate = "2026-11-01T10:00:00Z"
        )
        val transactions = listOf(
            tx("1", "EXPENSE", "taxi"), tx("2", "EXPENSE", "food"), tx("3", "EXPENSE", "food"),
            tx("4", "INCOME", "salary"), tx("5", "EXPENSE", "deleted"), tx("6", "EXPENSE", null)
        )
        assertEquals(listOf("food", "taxi"), frequentCategoryIds(transactions, categories, "EXPENSE"))
        assertEquals(listOf("salary"), frequentCategoryIds(transactions, categories, "INCOME"))
        assertEquals(listOf("food"), frequentCategoryIds(transactions, categories, "EXPENSE", limit = 1))
    }

    @Test
    fun testSearchCategoriesMatchesLocalizedAndBaseNamesPrefixFirst() {
        val categories = listOf(
            CategoryDto(id = "cafe", name = "Coffee shops", translations = mapOf("ru" to "Кофейни")),
            CategoryDto(id = "grocery-coffee", name = "Tea & coffee", translations = mapOf("ru" to "Кофе и чай")),
            CategoryDto(id = "machine", name = "Coffee machine", translations = mapOf("ru" to "Кофемашина")),
            CategoryDto(id = "taxi", name = "Taxi", translations = mapOf("ru" to "Такси"))
        )
        assertEquals(listOf("grocery-coffee", "cafe", "machine"), searchCategories(categories, " КОФЕ", "ru").map { it.id })
        assertEquals(listOf("machine", "cafe", "grocery-coffee"), searchCategories(categories, "coffee", "en").map { it.id })
        assertEquals(listOf("taxi"), searchCategories(categories, "taxi", "ru").map { it.id })
        assertTrue(searchCategories(categories, "  ", "ru").isEmpty())
    }

    @Test
    fun testDetailsSummaryJoinsOnlyFilledParts() {
        assertEquals(
            "Magnum · Сегодня, 19:42 · #семья · Частей: 2 · на неделю",
            detailsSummary("  Magnum ", "Сегодня, 19:42", listOf("семья"), "на неделю", 2, "Частей")
        )
        assertEquals("Вчера, 08:00", detailsSummary("", "Вчера, 08:00", emptyList(), " ", 0, "Частей"))
    }

    @Test
    fun expenseReminderDelayTargetsNextLocalOccurrence() {
        val zone = ZoneId.of("Asia/Almaty")
        val evening = ZonedDateTime.of(2026, 11, 3, 18, 30, 0, 0, zone)
        assertEquals(Duration.ofMinutes(150), ExpenseReminderScheduler.delayUntilNext(evening, 21 * 60))

        // At or after the reminder time the next run is tomorrow.
        val atTime = ZonedDateTime.of(2026, 11, 3, 21, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(24), ExpenseReminderScheduler.delayUntilNext(atTime, 21 * 60))
        val late = ZonedDateTime.of(2026, 11, 3, 23, 15, 30, 0, zone)
        assertEquals(
            Duration.ofHours(21).plusMinutes(44).plusSeconds(30),
            ExpenseReminderScheduler.delayUntilNext(late, 21 * 60)
        )
    }

    @Test
    fun periodStatistics_comparesSameSliceAndRollsUpCategories() {
        val today = LocalDate.of(2026, 11, 4)
        val period = StatsPeriod.month(java.time.YearMonth.of(2026, 11), today)
        assertEquals(LocalDate.of(2026, 11, 4), period.elapsedEnd)
        assertEquals(LocalDate.of(2026, 10, 1), period.previousStart)
        assertEquals(LocalDate.of(2026, 10, 4), period.previousEnd)
        assertEquals(StatsBucketSize.DAY, period.bucketSize)
        assertEquals(4L, period.elapsedDays)

        val categories = listOf(
            CategoryDto(id = "food", name = "Food"),
            CategoryDto(id = "cafe", parentId = "food", name = "Cafe"),
            CategoryDto(id = "car", name = "Car")
        )
        val accounts = listOf(
            AccountDto(id = "a", name = "Main", accountType = "CHECKING", currency = "KZT"),
            AccountDto(id = "x", name = "Hidden", accountType = "CASH", currency = "KZT", isExcludedFromStats = true)
        )
        fun tx(id: String, type: String, amount: String, date: String, cat: String? = null, account: String = "a",
               splits: List<SplitDto> = emptyList(), pair: String? = null) = TransactionDto(
            id = id, accountId = account, categoryId = cat, type = type, amount = BigDecimal(amount),
            currency = "KZT", bookingDate = date, splits = splits, transferPairId = pair, payee = "Shop"
        )
        val txs = listOf(
            tx("1", "EXPENSE", "1000", "2026-11-02", "cafe"),
            tx("2", "EXPENSE", "3000", "2026-11-03", null, splits = listOf(
                SplitDto(categoryId = "food", amount = BigDecimal("1000")),
                SplitDto(categoryId = "car", amount = BigDecimal("2000"))
            )),
            tx("3", "INCOME", "10000", "2026-11-01"),
            tx("4", "EXPENSE", "500", "2026-11-02", "car", account = "x"),
            tx("5", "EXPENSE", "700", "2026-11-02", pair = "p1"),
            tx("6", "EXPENSE", "2000", "2026-10-03", "food"),
            tx("7", "EXPENSE", "9000", "2026-10-20", "food")
        )
        val stats = PeriodStatistics.calculate(txs, categories, accounts, period, "KZT", zone = ZoneId.of("UTC"))

        assertEquals(BigDecimal("4000.00"), stats.expense)
        assertEquals(BigDecimal("10000.00"), stats.income)
        // Only Oct 1–4 counts as the previous slice, not the whole October.
        assertEquals(BigDecimal("2000.00"), stats.previousExpense)
        assertEquals(BigDecimal("100"), PeriodStatistics.deltaPercent(stats.expense, stats.previousExpense))
        assertEquals(BigDecimal("60.0"), stats.savingsRate)
        assertEquals(30, stats.buckets.size)

        val food = stats.expenseByCategory.first { it.categoryId == "food" }
        assertEquals(BigDecimal("2000.00"), food.amount)
        assertEquals(BigDecimal("2000.00"), food.previousAmount)
        assertEquals(2, food.children.size)
        assertEquals(BigDecimal("2000.00"), stats.expenseByCategory.first { it.categoryId == "car" }.amount)
        assertEquals(BigDecimal("3000.00"), stats.largestExpense?.amount)
        assertEquals(2, stats.noSpendDays)
    }
}
