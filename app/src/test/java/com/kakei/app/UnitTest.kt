package com.kakei.app

import com.kakei.app.automation.BankSmsPatterns
import com.kakei.app.automation.NotificationDeduplicationHelper
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
import com.kakei.app.ui.screens.CategoryTree
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

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
    fun notificationDeduplicationHelper_drops5MinuteDuplicatesAcrossSmsAndPush() {
        val dedup = NotificationDeduplicationHelper(windowMillis = 5 * 60 * 1000L)
        val smsAlert = BankSmsPatterns.parse("Kaspi Gold: Покупка 3 200 ₸ в Yandex Go")!!
        val pushAlert = BankSmsPatterns.parse("Оплата 3200 KZT в Yandex Go")!!

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
}
