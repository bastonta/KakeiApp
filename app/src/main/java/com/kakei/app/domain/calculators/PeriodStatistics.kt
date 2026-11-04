package com.kakei.app.domain.calculators

import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.core.util.ExchangeRateBook
import com.kakei.app.data.remote.dto.AccountDto
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.TransactionDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** How the Analytics overview picks its period. */
enum class StatsPeriodKind(val months: Int = 0) { MONTH(1), MONTHS_3(3), MONTHS_6(6), YEAR(12), CUSTOM }

/** Width of one bar in the income / expense chart. */
enum class StatsBucketSize { DAY, WEEK, MONTH }

/**
 * A statistics period: the calendar [start]..[end] it covers, plus the part that has already
 * happened ([elapsedEnd], never after today) and the matching slice of the period before it,
 * so an ongoing month is compared with the same number of days of the previous month.
 */
data class StatsPeriod(
    val kind: StatsPeriodKind,
    val start: LocalDate,
    val end: LocalDate,
    val elapsedEnd: LocalDate,
    val previousStart: LocalDate,
    val previousEnd: LocalDate
) {
    /** Days already elapsed in the period (at least 1). */
    val elapsedDays: Long get() = (ChronoUnit.DAYS.between(start, elapsedEnd) + 1).coerceAtLeast(1)

    val bucketSize: StatsBucketSize
        get() = when (kind) {
            StatsPeriodKind.MONTH -> StatsBucketSize.DAY
            StatsPeriodKind.MONTHS_3 -> StatsBucketSize.WEEK
            StatsPeriodKind.MONTHS_6, StatsPeriodKind.YEAR -> StatsBucketSize.MONTH
            StatsPeriodKind.CUSTOM -> {
                val days = ChronoUnit.DAYS.between(start, end) + 1
                when {
                    days <= 45 -> StatsBucketSize.DAY
                    days <= 190 -> StatsBucketSize.WEEK
                    else -> StatsBucketSize.MONTH
                }
            }
        }

    /** The same kind of period shifted one step back ([steps] < 0) or forward; custom ranges keep their length. */
    fun shift(steps: Long, today: LocalDate = LocalDate.now()): StatsPeriod = when (kind) {
        StatsPeriodKind.MONTH -> month(YearMonth.from(start).plusMonths(steps), today)
        StatsPeriodKind.MONTHS_3, StatsPeriodKind.MONTHS_6 ->
            months(kind, YearMonth.from(end).plusMonths(steps * kind.months), today)
        StatsPeriodKind.YEAR -> year(start.year + steps.toInt(), today)
        StatsPeriodKind.CUSTOM -> {
            val len = ChronoUnit.DAYS.between(start, end) + 1
            custom(start.plusDays(len * steps), end.plusDays(len * steps), today)
        }
    }

    /** True when the period has not started yet, so stepping forward makes no sense. */
    fun isInFuture(today: LocalDate = LocalDate.now()): Boolean = start.isAfter(today)

    companion object {
        fun month(ym: YearMonth, today: LocalDate = LocalDate.now()): StatsPeriod {
            val start = ym.atDay(1)
            val end = ym.atEndOfMonth()
            val elapsedEnd = clampElapsed(start, end, today)
            val prev = ym.minusMonths(1)
            // Same day-of-month in the previous month (Mar 31 → Feb 28).
            val prevEnd = prev.atDay(minOf(elapsedEnd.dayOfMonth, prev.lengthOfMonth()))
            return StatsPeriod(StatsPeriodKind.MONTH, start, end, elapsedEnd, prev.atDay(1), prevEnd)
        }

        /** [kind].months whole months ending with [last] (e.g. Sep–Nov), compared with the months before them. */
        fun months(kind: StatsPeriodKind, last: YearMonth, today: LocalDate = LocalDate.now()): StatsPeriod {
            val n = kind.months.toLong()
            val start = last.minusMonths(n - 1).atDay(1)
            val end = last.atEndOfMonth()
            val elapsedEnd = clampElapsed(start, end, today)
            val prevStart = start.minusMonths(n)
            val prevEnd = minOf(prevStart.plusDays(ChronoUnit.DAYS.between(start, elapsedEnd)), start.minusDays(1))
            return StatsPeriod(kind, start, end, elapsedEnd, prevStart, prevEnd)
        }

        fun year(year: Int, today: LocalDate = LocalDate.now()): StatsPeriod {
            val start = LocalDate.of(year, 1, 1)
            val end = LocalDate.of(year, 12, 31)
            val elapsedEnd = clampElapsed(start, end, today)
            return StatsPeriod(StatsPeriodKind.YEAR, start, end, elapsedEnd, start.minusYears(1), elapsedEnd.minusYears(1))
        }

        fun custom(from: LocalDate, to: LocalDate, today: LocalDate = LocalDate.now()): StatsPeriod {
            val start = minOf(from, to)
            val end = maxOf(from, to)
            val elapsedEnd = clampElapsed(start, end, today)
            val len = ChronoUnit.DAYS.between(start, end) + 1
            val prevStart = start.minusDays(len)
            return StatsPeriod(
                StatsPeriodKind.CUSTOM, start, end, elapsedEnd,
                prevStart, prevStart.plusDays(ChronoUnit.DAYS.between(start, elapsedEnd))
            )
        }

        private fun clampElapsed(start: LocalDate, end: LocalDate, today: LocalDate): LocalDate = when {
            today.isBefore(start) -> start
            today.isAfter(end) -> end
            else -> today
        }
    }
}

/** Income and expense inside one chart bar starting on [start]. */
data class CashFlowBucket(val start: LocalDate, val income: BigDecimal, val expense: BigDecimal)

/** Total of one top-level category; [children] holds its subcategories (and the parent's own records). */
data class CategoryStat(
    val categoryId: String?,
    val amount: BigDecimal,
    val previousAmount: BigDecimal,
    val count: Int,
    val children: List<CategoryStat> = emptyList()
)

data class PayeeStat(val payee: String, val amount: BigDecimal, val count: Int)

data class LargestRecord(val transaction: TransactionDto, val amount: BigDecimal, val date: LocalDate)

/** Everything the Analytics overview shows for one [StatsPeriod], in [currency]. */
data class PeriodStats(
    val period: StatsPeriod,
    val currency: String,
    val income: BigDecimal,
    val expense: BigDecimal,
    val previousIncome: BigDecimal,
    val previousExpense: BigDecimal,
    val incomeCount: Int,
    val expenseCount: Int,
    val buckets: List<CashFlowBucket>,
    val expenseByCategory: List<CategoryStat>,
    val incomeByCategory: List<CategoryStat>,
    val topPayees: List<PayeeStat>,
    /** Average expense on each weekday (Monday first) over the elapsed part of the period. */
    val weekdayAverage: List<BigDecimal>,
    val dailyExpense: Map<LocalDate, BigDecimal>,
    val largestExpense: LargestRecord?,
    val noSpendDays: Int,
    /** Records left out because their currency has no known rate. */
    val unconvertedCount: Int
) {
    val net: BigDecimal get() = income - expense

    /** Share of income left after expenses, in %; null without income. */
    val savingsRate: BigDecimal?
        get() = if (income.signum() > 0) net.multiply(BigDecimal(100)).divide(income, 1, RoundingMode.HALF_UP) else null

    val averageDailyExpense: BigDecimal
        get() = expense.divide(BigDecimal.valueOf(period.elapsedDays), 2, RoundingMode.HALF_UP)

    val busiestDay: Map.Entry<LocalDate, BigDecimal>? get() = dailyExpense.maxByOrNull { it.value }
}

object PeriodStatistics {

    private const val TOP_PAYEES = 5

    /** Booking date of a record in the device time zone (bare dates are taken as is). */
    fun bookingDate(iso: String, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        runCatching { Instant.parse(iso).atZone(zone).toLocalDate() }
            .recoverCatching { LocalDate.parse(iso.take(10)) }
            .getOrNull()

    /**
     * Aggregates income and expenses of [period] in [currency]. Transfers and accounts excluded
     * from statistics are skipped; split records count towards each split's category;
     * subcategories roll up into their top-level group.
     */
    fun calculate(
        transactions: List<TransactionDto>,
        categories: List<CategoryDto>,
        accounts: List<AccountDto>,
        period: StatsPeriod,
        currency: String,
        rates: ExchangeRateBook = ExchangeRateBook.EMPTY,
        zone: ZoneId = ZoneId.systemDefault()
    ): PeriodStats {
        val excludedAccounts = accounts.filter { it.isExcludedFromStats }.map { it.id }.toSet()
        val parentOf = categories.associate { it.id to it.parentId }
        fun topLevel(catId: String?): String? = catId?.let { parentOf[it] ?: it }

        var income = BigDecimal.ZERO
        var expense = BigDecimal.ZERO
        var prevIncome = BigDecimal.ZERO
        var prevExpense = BigDecimal.ZERO
        var incomeCount = 0
        var expenseCount = 0
        var unconverted = 0

        val bucketStarts = bucketStarts(period)
        val bucketIncome = HashMap<LocalDate, BigDecimal>()
        val bucketExpense = HashMap<LocalDate, BigDecimal>()
        val daily = HashMap<LocalDate, BigDecimal>()
        val payees = HashMap<String, Pair<BigDecimal, Int>>()
        var largest: LargestRecord? = null

        // type → top-level → sub (or the parent itself) → amount / previous / count
        class Acc(var amount: BigDecimal = BigDecimal.ZERO, var previous: BigDecimal = BigDecimal.ZERO, var count: Int = 0)
        val byCategory = mapOf(
            "EXPENSE" to LinkedHashMap<String?, LinkedHashMap<String?, Acc>>(),
            "INCOME" to LinkedHashMap<String?, LinkedHashMap<String?, Acc>>()
        )

        for (tx in transactions) {
            if (tx.type != "EXPENSE" && tx.type != "INCOME") continue
            if (tx.transferPairId != null || tx.accountId in excludedAccounts) continue
            val date = bookingDate(tx.bookingDate, zone) ?: continue
            val inCurrent = !date.isBefore(period.start) && !date.isAfter(period.end)
            val inPrevious = !date.isBefore(period.previousStart) && !date.isAfter(period.previousEnd)
            if (!inCurrent && !inPrevious) continue

            val total = CurrencyFormatter.convert(tx.amount.abs(), tx.currency, currency, rates)
            if (total == null) {
                if (inCurrent) unconverted++
                continue
            }
            val isExpense = tx.type == "EXPENSE"
            val parts = if (tx.splits.isNotEmpty()) {
                tx.splits.mapNotNull { sp ->
                    CurrencyFormatter.convert(sp.amount.abs(), tx.currency, currency, rates)?.let { sp.categoryId to it }
                }
            } else listOf(tx.categoryId to total)
            val tree = byCategory.getValue(tx.type)
            parts.forEach { (catId, amt) ->
                val acc = tree.getOrPut(topLevel(catId)) { LinkedHashMap() }.getOrPut(catId) { Acc() }
                if (inCurrent) {
                    acc.amount += amt
                    acc.count++
                } else {
                    acc.previous += amt
                }
            }

            if (inPrevious) {
                if (isExpense) prevExpense += total else prevIncome += total
            }
            if (!inCurrent) continue

            val bucket = bucketStarts.lastOrNull { !it.isAfter(date) } ?: period.start
            if (isExpense) {
                expense += total
                expenseCount++
                bucketExpense[bucket] = (bucketExpense[bucket] ?: BigDecimal.ZERO) + total
                daily[date] = (daily[date] ?: BigDecimal.ZERO) + total
                val payee = tx.payee?.trim().orEmpty()
                if (payee.isNotEmpty()) {
                    val prev = payees[payee] ?: (BigDecimal.ZERO to 0)
                    payees[payee] = (prev.first + total) to (prev.second + 1)
                }
                if (largest == null || total > largest.amount) largest = LargestRecord(tx, total, date)
            } else {
                income += total
                incomeCount++
                bucketIncome[bucket] = (bucketIncome[bucket] ?: BigDecimal.ZERO) + total
            }
        }

        fun categoryStats(type: String): List<CategoryStat> =
            byCategory.getValue(type).map { (topId, subs) ->
                val children = subs.map { (id, a) -> CategoryStat(id, a.amount.money(), a.previous.money(), a.count) }
                    .filter { it.amount.signum() > 0 }
                    .sortedByDescending { it.amount }
                CategoryStat(
                    categoryId = topId,
                    amount = subs.values.fold(BigDecimal.ZERO) { s, a -> s + a.amount }.money(),
                    previousAmount = subs.values.fold(BigDecimal.ZERO) { s, a -> s + a.previous }.money(),
                    count = subs.values.sumOf { it.count },
                    // A single line equal to the group adds nothing.
                    children = if (children.size == 1 && children.first().categoryId == topId) emptyList() else children
                )
            }.filter { it.amount.signum() > 0 }.sortedByDescending { it.amount }

        // Average per weekday: total spent on that weekday / how many such weekdays have elapsed.
        val weekdayTotals = Array(7) { BigDecimal.ZERO }
        val weekdayCounts = IntArray(7)
        var d = period.start
        var noSpend = 0
        while (!d.isAfter(period.elapsedEnd)) {
            val idx = d.dayOfWeek.value - DayOfWeek.MONDAY.value
            weekdayCounts[idx]++
            val spent = daily[d]
            if (spent != null) weekdayTotals[idx] += spent else noSpend++
            d = d.plusDays(1)
        }

        return PeriodStats(
            period = period,
            currency = currency,
            income = income.money(),
            expense = expense.money(),
            previousIncome = prevIncome.money(),
            previousExpense = prevExpense.money(),
            incomeCount = incomeCount,
            expenseCount = expenseCount,
            buckets = bucketStarts.map {
                CashFlowBucket(it, (bucketIncome[it] ?: BigDecimal.ZERO).money(), (bucketExpense[it] ?: BigDecimal.ZERO).money())
            },
            expenseByCategory = categoryStats("EXPENSE"),
            incomeByCategory = categoryStats("INCOME"),
            topPayees = payees.entries
                .sortedByDescending { it.value.first }
                .take(TOP_PAYEES)
                .map { PayeeStat(it.key, it.value.first.money(), it.value.second) },
            weekdayAverage = (0 until 7).map { i ->
                if (weekdayCounts[i] == 0) BigDecimal.ZERO
                else weekdayTotals[i].divide(BigDecimal.valueOf(weekdayCounts[i].toLong()), 2, RoundingMode.HALF_UP)
            },
            dailyExpense = daily.mapValues { it.value.money() },
            largestExpense = largest?.let { it.copy(amount = it.amount.money()) },
            noSpendDays = noSpend,
            unconvertedCount = unconverted
        )
    }

    /** First day of every chart bar covering the whole period. */
    fun bucketStarts(period: StatsPeriod): List<LocalDate> {
        val first = when (period.bucketSize) {
            StatsBucketSize.DAY -> period.start
            StatsBucketSize.WEEK -> period.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            StatsBucketSize.MONTH -> period.start.withDayOfMonth(1)
        }
        return generateSequence(first) {
            when (period.bucketSize) {
                StatsBucketSize.DAY -> it.plusDays(1)
                StatsBucketSize.WEEK -> it.plusWeeks(1)
                StatsBucketSize.MONTH -> it.plusMonths(1)
            }
        }.takeWhile { !it.isAfter(period.end) }.toList()
    }

    /** Change from [previous] to [current] in %, or null when there is nothing to compare with. */
    fun deltaPercent(current: BigDecimal, previous: BigDecimal): BigDecimal? =
        if (previous.signum() <= 0) null
        else (current - previous).multiply(BigDecimal(100)).divide(previous, 0, RoundingMode.HALF_UP)

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
}
