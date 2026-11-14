package com.kakei.app

import com.kakei.app.automation.BankAlertText
import com.kakei.app.core.util.ExchangeRateBook
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.BudgetEnvelopeDto
import com.kakei.app.data.remote.dto.PlannedObligationDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.domain.calculators.FinancialCalculators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class CalculatorFixesTest {

    private val almaty = ZoneId.of("Asia/Almaty") // UTC+5
    private val utc = ZoneId.of("UTC")

    private fun expense(id: String, amount: String, bookingDate: String, currency: String = "KZT") = TransactionDto(
        id = id, accountId = "acc-1", type = "EXPENSE", amount = BigDecimal(amount),
        currency = currency, bookingDate = bookingDate
    )

    private fun budget(periodType: String, startDate: String = "", endDate: String? = null) = BudgetEnvelopeDto(
        id = "b-1", name = "Envelope", limitAmount = BigDecimal("70000"), currency = "KZT",
        periodType = periodType, startDate = startDate, endDate = endDate,
        // A window cached from the server a month ago: it must not be reused.
        periodStart = "2026-09-01", periodEnd = "2026-09-30"
    )

    @Test
    fun weeklyBudget_usesTheIsoWeekAndItsRemainingDays() {
        val today = LocalDate.of(2026, 10, 14) // Wednesday
        val transactions = listOf(
            expense("in-week", "10000", "2026-10-12T08:00:00Z"),
            expense("last-week", "5000", "2026-10-11T08:00:00Z"),
            expense("cached-window", "9000", "2026-09-15T08:00:00Z")
        )

        val result = FinancialCalculators.evaluateBudgets(listOf(budget("WEEKLY")), transactions, today, zone = utc).single()

        assertEquals("2026-10-12", result.periodStart)
        assertEquals("2026-10-18", result.periodEnd)
        assertEquals(5L, result.remainingDaysInPeriod) // Wed..Sun
        assertEquals(BigDecimal("10000.00"), result.spentAmount)
        assertEquals(BigDecimal("12000.00"), result.dailyBurnRateLimit) // 60000 / 5
    }

    @Test
    fun budgetWindows_followThePeriodType() {
        val today = LocalDate.of(2026, 10, 14)
        assertEquals(
            LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31),
            FinancialCalculators.budgetPeriodWindow(budget("YEARLY"), today)
        )
        assertEquals(
            LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31),
            FinancialCalculators.budgetPeriodWindow(budget("MONTHLY"), today)
        )
        assertEquals(
            LocalDate.of(2026, 10, 5) to LocalDate.of(2026, 11, 20),
            FinancialCalculators.budgetPeriodWindow(budget("ONE_OFF", "2026-10-05", "2026-11-20"), today)
        )
    }

    @Test
    fun budget_ignoresExpensesBeforeItsStartDate() {
        val today = LocalDate.of(2026, 10, 20)
        val transactions = listOf(
            expense("before-start", "4000", "2026-10-05T08:00:00Z"),
            expense("after-start", "6000", "2026-10-12T08:00:00Z")
        )

        val result = FinancialCalculators
            .evaluateBudgets(listOf(budget("MONTHLY", startDate = "2026-10-10")), transactions, today, zone = utc)
            .single()

        assertEquals(BigDecimal("6000.00"), result.spentAmount)
    }

    @Test
    fun recordDay_isTheLocalDayNotTheUtcDay() {
        // 02:00 on 1 November in Almaty is still 31 October in UTC.
        val lateNight = expense("night", "5000", "2026-10-31T21:00:00Z")
        val today = LocalDate.of(2026, 11, 1)

        val local = FinancialCalculators.evaluateBudgets(listOf(budget("MONTHLY")), listOf(lateNight), today, zone = almaty)
        assertEquals(BigDecimal("5000.00"), local.single().spentAmount)
        val asUtc = FinancialCalculators.evaluateBudgets(listOf(budget("MONTHLY")), listOf(lateNight), today, zone = utc)
        assertEquals(BigDecimal("0.00"), asUtc.single().spentAmount)

        val streak = FinancialCalculators.calculateNoSpendStreak(listOf(lateNight), today, zone = almaty)
        assertEquals(0L, streak.currentStreakDays)
        assertEquals("2026-11-01", streak.lastSpendDate)

        val heatmap = FinancialCalculators.calculateHeatmaps(listOf(lateNight), "KZT", 2026, 11, zone = almaty)
        assertEquals(BigDecimal("5000.00"), heatmap.calendarDays.first { it.date == "2026-11-01" }.totalSpent)

        val insights = FinancialCalculators.calculateInsights(listOf(lateNight), "KZT", today, zone = almaty)
        assertEquals(BigDecimal("5000.00"), insights.currentMonthSpent)
        assertEquals(BigDecimal("0.00"), insights.previousMonthSpent)
    }

    @Test
    fun healthScore_withoutIncomeOrExpense_getsTheNeutralSavingsRate() {
        val score = FinancialCalculators.calculateHealthScore(
            transactions = emptyList(), budgets = emptyList(), lendingRecords = emptyList(),
            baseCurrency = "KZT", today = LocalDate.of(2026, 10, 14), zone = utc
        )

        // Zero expense has scale 2 after rounding; it must still count as "nothing spent".
        assertEquals(BigDecimal("20.0"), score.savingsRatePercentage)
        assertEquals(40, score.savingsRateScore)
    }

    @Test
    fun forecast_namesCurrenciesItCouldNotConvert() {
        val accounts = listOf(
            AccountDto(id = "a-kzt", name = "Card", accountType = "CHECKING", currency = "KZT", currentBalance = BigDecimal("100000")),
            AccountDto(id = "a-usd", name = "Cash", accountType = "CASH", currency = "USD", currentBalance = BigDecimal("50"))
        )
        val obligations = listOf(
            PlannedObligationDto(
                id = "o-1", accountId = "a-kzt", title = "Hosting", type = "EXPENSE", amount = BigDecimal("10"),
                currency = "EUR", frequency = "MONTHLY", startDate = "2026-10-01", nextDueDate = "2026-10-20"
            )
        )

        val forecast = FinancialCalculators.calculateCashFlowForecast(
            accounts, obligations, "KZT", today = LocalDate.of(2026, 10, 14), rates = ExchangeRateBook.EMPTY
        )

        assertEquals(listOf("EUR", "USD"), forecast.unconvertedCurrencies)
        assertEquals(BigDecimal("100000.00"), forecast.currentLiquidBalance)
        assertTrue(forecast.projections.isEmpty())
    }

    @Test
    fun currencyToken_mustBeARealIsoCode() {
        assertEquals("CHF", BankAlertText.normalizeCurrency("chf"))
        assertEquals("KZT", BankAlertText.normalizeCurrency("₸"))
        assertEquals("USD", BankAlertText.normalizeCurrency("usd."))
        // A three-letter merchant name is not a currency.
        assertNull(BankAlertText.normalizeCurrency("KFC"))
        assertNull(BankAlertText.normalizeCurrency("ABC"))
    }
}
