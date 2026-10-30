package com.kakei.app.domain.calculators

import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.core.util.ExchangeRateBook
import com.kakei.app.core.util.HashDedupCalculator
import com.kakei.app.data.remote.dto.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Local financial analytics, envelope budget burn-rate, 30-day cash flow forecast,
 * and statement import/export calculators matching the Kakei Rust backend logic.
 *
 * Cross-currency amounts are converted with the server-provided [ExchangeRateBook];
 * an amount whose rate is unknown is left out of a converted aggregate (it is never
 * converted with a guessed rate). The UI surfaces such amounts in their own currency.
 */
object FinancialCalculators {

    /** Minor-unit scale of an ISO 4217 currency (JPY → 0, USD → 2, KWD → 3); 2 if unknown. */
    fun currencyScale(currency: String): Int =
        runCatching { java.util.Currency.getInstance(currency.trim().uppercase(Locale.ROOT)).defaultFractionDigits }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: 2

    /**
     * Splits [total] (rounded to [scale]) into [parts] shares that add up to it exactly.
     * The indivisible remainder is spread one minor unit at a time over the first shares,
     * so nothing is lost to rounding: 100.00 / 3 → 33.34, 33.33, 33.33.
     */
    fun splitEvenly(total: BigDecimal, parts: Int, scale: Int): List<BigDecimal> {
        require(parts > 0) { "parts must be positive" }
        val units = total.setScale(scale, RoundingMode.HALF_UP).unscaledValue()
        val (base, remainder) = units.divideAndRemainder(BigInteger.valueOf(parts.toLong()))
        val extra = remainder.signum()
        val extraCount = remainder.abs().toInt()
        return List(parts) { i ->
            val share = if (i < extraCount) base + BigInteger.valueOf(extra.toLong()) else base
            BigDecimal(share, scale)
        }
    }

    /**
     * Recomputes spentAmount, remainingAmount, usagePercentage, dailyBurnRateLimit, and alertLevel
     * for each budget envelope based on local ledger transactions.
     */
    fun evaluateBudgets(
        budgets: List<BudgetEnvelopeDto>,
        transactions: List<TransactionDto>,
        today: LocalDate = LocalDate.now(),
        rates: ExchangeRateBook = ExchangeRateBook.EMPTY
    ): List<BudgetEnvelopeDto> {
        val currentMonthStart = today.withDayOfMonth(1)
        val currentMonthEnd = YearMonth.from(today).atEndOfMonth()
        val remainingDays = maxOf(1L, ChronoUnit.DAYS.between(today, currentMonthEnd) + 1L)

        return budgets.map { budget ->
            val periodStart = budget.periodStart.takeIf { it.length >= 10 }
                ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                ?: currentMonthStart
            val periodEnd = budget.periodEnd.takeIf { it.length >= 10 }
                ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                ?: currentMonthEnd

            val matchingExpenses = transactions.filter { tx ->
                tx.type == "EXPENSE" &&
                    tx.transferPairId == null &&
                    runCatching {
                        val txDate = LocalDate.parse(tx.bookingDate.take(10))
                        !txDate.isBefore(periodStart) && !txDate.isAfter(periodEnd)
                    }.getOrDefault(false) &&
                    (budget.categoryIds.isEmpty() ||
                        (tx.categoryId != null && budget.categoryIds.contains(tx.categoryId)) ||
                        tx.splits.any { it.categoryId != null && budget.categoryIds.contains(it.categoryId) })
            }

            val spent = matchingExpenses.fold(BigDecimal.ZERO) { acc, tx ->
                val nominal = if (tx.splits.isNotEmpty() && budget.categoryIds.isNotEmpty()) {
                    tx.splits.filter { it.categoryId != null && budget.categoryIds.contains(it.categoryId) }
                        .fold(BigDecimal.ZERO) { s, split -> s + split.amount }
                } else {
                    tx.amount
                }
                acc + (CurrencyFormatter.convert(nominal, tx.currency, budget.currency, rates) ?: BigDecimal.ZERO)
            }.setScale(2, RoundingMode.HALF_UP)

            val remaining = (budget.limitAmount - spent).setScale(2, RoundingMode.HALF_UP)
            val usagePct = if (budget.limitAmount > BigDecimal.ZERO) {
                spent.multiply(BigDecimal("100")).divide(budget.limitAmount, 1, RoundingMode.HALF_UP)
            } else BigDecimal.ZERO

            val dailyBurn = if (remaining > BigDecimal.ZERO) {
                remaining.divide(BigDecimal.valueOf(remainingDays), 2, RoundingMode.HALF_UP)
            } else BigDecimal.ZERO

            val pctInt = usagePct.toInt()
            val alertLevel = when {
                pctInt >= 100 -> "EXCEEDED_100"
                pctInt >= 90 -> "WARNING_90"
                pctInt >= 75 -> "WARNING_75"
                else -> "NORMAL"
            }

            budget.copy(
                spentAmount = spent,
                remainingAmount = remaining,
                usagePercentage = usagePct,
                dailyBurnRateLimit = dailyBurn,
                remainingDaysInPeriod = remainingDays,
                periodStart = periodStart.toString(),
                periodEnd = periodEnd.toString(),
                alertLevel = alertLevel,
                thresholdTriggered = pctInt >= budget.notifyThreshold
            )
        }
    }

    /**
     * Computes the composite 0..100 Financial Health Score (Savings Rate + Budget Compliance + Debt-to-Income).
     */
    fun calculateHealthScore(
        transactions: List<TransactionDto>,
        budgets: List<BudgetEnvelopeDto>,
        lendingRecords: List<LendingRecordDto>,
        baseCurrency: String,
        today: LocalDate = LocalDate.now(),
        rates: ExchangeRateBook = ExchangeRateBook.EMPTY
    ): FinancialHealthScoreDto {
        val windowStart = today.minusDays(30)
        val recentTx = transactions.filter { tx ->
            tx.transferPairId == null && runCatching {
                !LocalDate.parse(tx.bookingDate.take(10)).isBefore(windowStart)
            }.getOrDefault(true)
        }

        val income = recentTx.filter { it.type == "INCOME" }
            .fold(BigDecimal.ZERO) { acc, tx ->
                acc + (CurrencyFormatter.convert(tx.amount, tx.currency, baseCurrency, rates) ?: BigDecimal.ZERO)
            }.setScale(2, RoundingMode.HALF_UP)

        val expense = recentTx.filter { it.type == "EXPENSE" }
            .fold(BigDecimal.ZERO) { acc, tx ->
                acc + (CurrencyFormatter.convert(tx.amount, tx.currency, baseCurrency, rates) ?: BigDecimal.ZERO)
            }.setScale(2, RoundingMode.HALF_UP)

        val savingsRatePct = if (income > BigDecimal.ZERO) {
            ((income - expense).coerceAtLeast(BigDecimal.ZERO))
                .multiply(BigDecimal("100"))
                .divide(income, 1, RoundingMode.HALF_UP)
        } else if (expense == BigDecimal.ZERO) {
            BigDecimal("20.0")
        } else {
            BigDecimal.ZERO
        }

        // Savings score out of 100 (25%+ savings rate = 100 pts)
        val savingsScore = (savingsRatePct.toDouble() * 4.0).toInt().coerceIn(0, 100)

        val evaluatedBudgets = evaluateBudgets(budgets, transactions, today, rates)
        val compliantCount = evaluatedBudgets.count { it.usagePercentage <= BigDecimal("100") }
        val budgetCompliancePct = if (evaluatedBudgets.isNotEmpty()) {
            BigDecimal.valueOf(compliantCount * 100L)
                .divide(BigDecimal.valueOf(evaluatedBudgets.size.toLong()), 1, RoundingMode.HALF_UP)
        } else BigDecimal("100.0")
        val budgetScore = budgetCompliancePct.toInt().coerceIn(0, 100)

        val totalDebt = lendingRecords.filter { it.type == "BORROWED" && !it.isSettled && !it.isForgiven }
            .fold(BigDecimal.ZERO) { acc, r ->
                acc + (CurrencyFormatter.convert(r.outstandingAmount, r.currency, baseCurrency, rates) ?: BigDecimal.ZERO)
            }.setScale(2, RoundingMode.HALF_UP)

        val dtiPct = if (income > BigDecimal.ZERO) {
            totalDebt.multiply(BigDecimal("100")).divide(income, 1, RoundingMode.HALF_UP)
        } else if (totalDebt > BigDecimal.ZERO) {
            BigDecimal("100.0")
        } else BigDecimal.ZERO

        val dtiScore = (100 - dtiPct.toInt()).coerceIn(0, 100)

        val overall = ((savingsScore * 0.40) + (budgetScore * 0.35) + (dtiScore * 0.25)).toInt().coerceIn(0, 100)
        val grade = when {
            overall >= 90 -> "A+"
            overall >= 80 -> "A"
            overall >= 70 -> "B"
            overall >= 60 -> "C"
            overall >= 45 -> "D"
            else -> "F"
        }

        val recommendations = buildList {
            if (savingsRatePct < BigDecimal("15")) {
                add("Aim to save at least 15-20% of monthly income by automating transfers to Savings Goals.")
            }
            if (evaluatedBudgets.any { it.usagePercentage > BigDecimal("90") }) {
                add("One or more budget envelopes are above 90% utilization; check your Daily Burn Rate limit.")
            }
            if (totalDebt > BigDecimal.ZERO) {
                add("Prioritize settling outstanding borrowed balances (${CurrencyFormatter.format(totalDebt, baseCurrency)}).")
            }
            if (isEmpty()) {
                add("Excellent financial discipline! Your savings rate, budgets, and debt ratio are in optimal shape.")
            }
        }

        return FinancialHealthScoreDto(
            overallScore = overall,
            grade = grade,
            savingsRatePercentage = savingsRatePct,
            savingsRateScore = savingsScore,
            budgetCompliancePercentage = budgetCompliancePct,
            budgetComplianceScore = budgetScore,
            debtToIncomePercentage = dtiPct,
            debtToIncomeScore = dtiScore,
            periodIncome = income,
            periodExpense = expense,
            totalOutstandingDebt = totalDebt,
            currency = baseCurrency,
            recommendations = recommendations
        )
    }

    /**
     * Computes consecutive zero-expense streaks (`NoSpendStreakDto`).
     */
    fun calculateNoSpendStreak(
        transactions: List<TransactionDto>,
        today: LocalDate = LocalDate.now()
    ): NoSpendStreakDto {
        val spendDates = transactions
            .filter { it.type == "EXPENSE" && it.transferPairId == null }
            .mapNotNull { runCatching { LocalDate.parse(it.bookingDate.take(10)) }.getOrNull() }
            .toSet()

        var currentStreak = 0L
        var cursor = today
        while (!spendDates.contains(cursor) && currentStreak < 365L) {
            currentStreak++
            cursor = cursor.minusDays(1)
        }

        var longestStreak = 0L
        var running = 0L
        for (i in 89 downTo 0) {
            val d = today.minusDays(i.toLong())
            if (!spendDates.contains(d)) {
                running++
                if (running > longestStreak) longestStreak = running
            } else {
                running = 0L
            }
        }

        var noSpendLast30 = 0L
        for (i in 0 until 30) {
            if (!spendDates.contains(today.minusDays(i.toLong()))) {
                noSpendLast30++
            }
        }

        val lastSpend = spendDates.maxOrNull()?.toString()
        return NoSpendStreakDto(
            currentStreakDays = currentStreak,
            longestStreakDays = maxOf(longestStreak, currentStreak),
            totalNoSpendDaysLast30 = noSpendLast30,
            lastSpendDate = lastSpend
        )
    }

    /**
     * Generates GitHub-style calendar and 12-month spending heatmaps.
     */
    fun calculateHeatmaps(
        transactions: List<TransactionDto>,
        baseCurrency: String,
        year: Int = LocalDate.now().year,
        month: Int = LocalDate.now().monthValue,
        rates: ExchangeRateBook = ExchangeRateBook.EMPTY
    ): SpendingHeatmapsDto {
        val ym = YearMonth.of(year, month.coerceIn(1, 12))
        val expenses = transactions.filter { it.type == "EXPENSE" && it.transferPairId == null }

        val byDate = mutableMapOf<LocalDate, Pair<BigDecimal, Long>>()
        val byMonth = mutableMapOf<Int, Pair<BigDecimal, Long>>()

        for (tx in expenses) {
            val date = runCatching { LocalDate.parse(tx.bookingDate.take(10)) }.getOrNull() ?: continue
            if (date.year != year) continue
            val conv = CurrencyFormatter.convert(tx.amount, tx.currency, baseCurrency, rates) ?: continue
            val prevDay = byDate[date] ?: (BigDecimal.ZERO to 0L)
            byDate[date] = (prevDay.first + conv) to (prevDay.second + 1L)

            val prevMonth = byMonth[date.monthValue] ?: (BigDecimal.ZERO to 0L)
            byMonth[date.monthValue] = (prevMonth.first + conv) to (prevMonth.second + 1L)
        }

        val maxDaily = byDate.values.maxOfOrNull { it.first } ?: BigDecimal.ONE
        val calendarDays = (1..ym.lengthOfMonth()).map { day ->
            val d = ym.atDay(day)
            val (spent, count) = byDate[d] ?: (BigDecimal.ZERO to 0L)
            val ratio = if (maxDaily > BigDecimal.ZERO && spent > BigDecimal.ZERO) {
                spent.divide(maxDaily, 2, RoundingMode.HALF_UP).toDouble()
            } else 0.0
            val level = when {
                spent <= BigDecimal.ZERO -> 0
                ratio < 0.25 -> 1
                ratio < 0.55 -> 2
                ratio < 0.80 -> 3
                else -> 4
            }
            HeatmapDayCellDto(
                date = d.toString(),
                totalSpent = spent.setScale(2, RoundingMode.HALF_UP),
                transactionCount = count,
                intensityLevel = level
            )
        }

        val monthlySummaries = (1..12).map { m ->
            val (spent, count) = byMonth[m] ?: (BigDecimal.ZERO to 0L)
            HeatmapMonthSummaryDto(
                month = m,
                totalSpent = spent.setScale(2, RoundingMode.HALF_UP),
                transactionCount = count
            )
        }

        return SpendingHeatmapsDto(
            year = year,
            month = month,
            currency = baseCurrency,
            calendarDays = calendarDays,
            monthlySummaries = monthlySummaries
        )
    }

    /**
     * Computes Kakei Insights: MoM comparison, month-end burn projection, small spends,
     * weekday vs weekend distribution, and top merchants.
     */
    fun calculateInsights(
        transactions: List<TransactionDto>,
        baseCurrency: String,
        today: LocalDate = LocalDate.now(),
        rates: ExchangeRateBook = ExchangeRateBook.EMPTY
    ): InsightsSummaryDto {
        val currentYm = YearMonth.from(today)
        val prevYm = currentYm.minusMonths(1)
        // Without a USD rate there is no meaningful "small spend" threshold: skip that metric.
        val smallSpendThreshold = CurrencyFormatter.convert(BigDecimal("10"), "USD", baseCurrency, rates)

        var currentMonthSpent = BigDecimal.ZERO
        var prevMonthSpent = BigDecimal.ZERO
        var smallSpendsTotal = BigDecimal.ZERO
        var smallSpendsCount = 0L
        var weekdayTotal = BigDecimal.ZERO
        var weekendTotal = BigDecimal.ZERO
        val payeeMap = mutableMapOf<String, Pair<BigDecimal, Long>>()

        for (tx in transactions) {
            if (tx.type != "EXPENSE" || tx.transferPairId != null) continue
            val date = runCatching { LocalDate.parse(tx.bookingDate.take(10)) }.getOrNull() ?: continue
            val conv = CurrencyFormatter.convert(tx.amount, tx.currency, baseCurrency, rates) ?: continue
            val txYm = YearMonth.from(date)

            if (txYm == currentYm) {
                currentMonthSpent += conv
                if (smallSpendThreshold != null && conv <= smallSpendThreshold) {
                    smallSpendsTotal += conv
                    smallSpendsCount++
                }
                if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
                    weekendTotal += conv
                } else {
                    weekdayTotal += conv
                }
                val payeeName = tx.payee?.takeIf { it.isNotBlank() } ?: "Unassigned Merchant"
                val prev = payeeMap[payeeName] ?: (BigDecimal.ZERO to 0L)
                payeeMap[payeeName] = (prev.first + conv) to (prev.second + 1L)
            } else if (txYm == prevYm) {
                prevMonthSpent += conv
            }
        }

        val momDelta = if (prevMonthSpent > BigDecimal.ZERO) {
            (currentMonthSpent - prevMonthSpent)
                .multiply(BigDecimal("100"))
                .divide(prevMonthSpent, 1, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val elapsedDays = maxOf(1, today.dayOfMonth)
        val totalDaysInMonth = currentYm.lengthOfMonth()
        val projectedEnd = currentMonthSpent
            .multiply(BigDecimal.valueOf(totalDaysInMonth.toLong()))
            .divide(BigDecimal.valueOf(elapsedDays.toLong()), 2, RoundingMode.HALF_UP)

        val topPayees = payeeMap.entries
            .sortedByDescending { it.value.first }
            .take(6)
            .map { (name, pair) ->
                TopPayeeEntryDto(
                    payee = name,
                    totalSpent = pair.first.setScale(2, RoundingMode.HALF_UP),
                    transactionCount = pair.second
                )
            }

        return InsightsSummaryDto(
            currency = baseCurrency,
            currentMonthSpent = currentMonthSpent.setScale(2, RoundingMode.HALF_UP),
            previousMonthSpent = prevMonthSpent.setScale(2, RoundingMode.HALF_UP),
            monthOverMonthDeltaPercentage = momDelta,
            monthEndProjectedSpend = projectedEnd,
            smallSpendsTotal = smallSpendsTotal.setScale(2, RoundingMode.HALF_UP),
            smallSpendsCount = smallSpendsCount,
            weekdaySpendTotal = weekdayTotal.setScale(2, RoundingMode.HALF_UP),
            weekendSpendTotal = weekendTotal.setScale(2, RoundingMode.HALF_UP),
            topPayees = topPayees
        )
    }

    /**
     * Generates a 30-day Cash Flow Forecast & virtual recurrence projections (`CashFlowForecastDto`).
     */
    fun calculateCashFlowForecast(
        accounts: List<AccountDto>,
        obligations: List<PlannedObligationDto>,
        baseCurrency: String,
        horizonDays: Long = 30L,
        today: LocalDate = LocalDate.now(),
        rates: ExchangeRateBook = ExchangeRateBook.EMPTY
    ): CashFlowForecastDto {
        val liquidBalance = accounts
            .filter { !it.isArchived && !it.isExcludedFromStats }
            .fold(BigDecimal.ZERO) { acc, a ->
                acc + (CurrencyFormatter.convert(a.currentBalance, a.currency, baseCurrency, rates) ?: BigDecimal.ZERO)
            }.setScale(2, RoundingMode.HALF_UP)

        val projections = mutableListOf<VirtualProjectionDto>()
        val endDate = today.plusDays(horizonDays)

        for (ob in obligations.filter { it.isActive }) {
            var cursor = runCatching { LocalDate.parse(ob.nextDueDate.take(10)) }.getOrNull() ?: today
            val limitDate = ob.endDate?.takeIf { it.length >= 10 }
                ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

            var safety = 0
            while (!cursor.isAfter(endDate) && safety < 60) {
                safety++
                if (!cursor.isBefore(today) && (limitDate == null || !cursor.isAfter(limitDate))) {
                    val conv = CurrencyFormatter.convert(ob.amount, ob.currency, baseCurrency, rates)
                    // An obligation in a currency without a known rate cannot be projected
                    // into the base-currency timeline; it is skipped rather than guessed.
                    if (conv != null) projections.add(
                        VirtualProjectionDto(
                            obligationId = ob.id,
                            title = ob.title,
                            accountId = ob.accountId,
                            type = ob.type,
                            amount = ob.amount,
                            convertedAmount = conv,
                            currency = ob.currency,
                            projectedDate = cursor.toString()
                        )
                    )
                }
                cursor = when (ob.frequency.uppercase(Locale.ROOT)) {
                    "DAILY" -> cursor.plusDays(1)
                    "WEEKLY" -> cursor.plusWeeks(1)
                    "MONTHLY" -> cursor.plusMonths(1)
                    "YEARLY" -> cursor.plusYears(1)
                    else -> endDate.plusDays(1) // ONE_OFF
                }
            }
        }

        projections.sortBy { it.projectedDate }

        var runningBalance = liquidBalance
        var totalIncome = BigDecimal.ZERO
        var totalExpense = BigDecimal.ZERO
        val dailyTimeline = mutableListOf<DailyForecastPointDto>()

        for (dayOffset in 0..horizonDays) {
            val d = today.plusDays(dayOffset).toString()
            val dayItems = projections.filter { it.projectedDate == d }
            val dayInc = dayItems.filter { it.type == "INCOME" }
                .fold(BigDecimal.ZERO) { acc, p -> acc + p.convertedAmount }
            val dayExp = dayItems.filter { it.type == "EXPENSE" }
                .fold(BigDecimal.ZERO) { acc, p -> acc + p.convertedAmount }

            totalIncome += dayInc
            totalExpense += dayExp
            runningBalance = runningBalance + dayInc - dayExp

            dailyTimeline.add(
                DailyForecastPointDto(
                    date = d,
                    plannedIncome = dayInc.setScale(2, RoundingMode.HALF_UP),
                    plannedExpense = dayExp.setScale(2, RoundingMode.HALF_UP),
                    projectedBalance = runningBalance.setScale(2, RoundingMode.HALF_UP)
                )
            )
        }

        val ratio = if (liquidBalance > BigDecimal.ZERO) {
            totalExpense.multiply(BigDecimal("100")).divide(liquidBalance, 1, RoundingMode.HALF_UP)
        } else if (totalExpense > BigDecimal.ZERO) {
            BigDecimal("100.0")
        } else BigDecimal.ZERO

        return CashFlowForecastDto(
            baseCurrency = baseCurrency,
            currentLiquidBalance = liquidBalance,
            plannedIncomeTotal = totalIncome.setScale(2, RoundingMode.HALF_UP),
            plannedExpenseTotal = totalExpense.setScale(2, RoundingMode.HALF_UP),
            forecastedEndBalance = runningBalance.setScale(2, RoundingMode.HALF_UP),
            horizonDays = horizonDays,
            obligationToLiquidityRatio = ratio,
            liquidityDeficitAlert = ratio > BigDecimal("50.0") || runningBalance < BigDecimal.ZERO,
            projections = projections,
            dailyTimeline = dailyTimeline
        )
    }

    /**
     * Parses CSV, TSV, or OFX 2.2 statements locally with SHA-256 deduplication.
     */
    fun parseStatementImport(
        account: AccountDto,
        existingHashes: Set<String>,
        request: ImportStatementRequest
    ): Pair<ImportStatementResponse, List<CreateRecordItem>> {
        val format = (request.format ?: "csv").lowercase(Locale.ROOT)
        if (format == "ofx") {
            return parseOfxStatement(account, existingHashes, request.content)
        }

        val delimiter = (request.delimiter ?: ",").firstOrNull() ?: ','
        val lines = request.content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val dataLines = if (request.hasHeader != false && lines.isNotEmpty()) lines.drop(1) else lines
        val mapping = request.mapping ?: ColumnMapping()

        var imported = 0
        var duplicates = 0
        var failed = 0
        val createdItems = mutableListOf<CreateRecordItem>()
        val seenHashes = existingHashes.toMutableSet()

        for (line in dataLines) {
            val cols = line.split(delimiter).map { it.trim().removeSurrounding("\"") }
            val dateRaw = cols.getOrNull(mapping.dateColumn ?: 0).orEmpty()
            val amountRaw = cols.getOrNull(mapping.amountColumn ?: 1).orEmpty()
                .replace(" ", "")
                .replace(",", ".")
            val payee = cols.getOrNull(mapping.payeeColumn ?: 2)?.takeIf { it.isNotBlank() }
            val note = cols.getOrNull(mapping.noteColumn ?: 3)?.takeIf { it.isNotBlank() }

            val parsedAmount = runCatching { BigDecimal(amountRaw) }.getOrNull()
            if (parsedAmount == null || parsedAmount.compareTo(BigDecimal.ZERO) == 0) {
                failed++
                continue
            }

            val normalizedDate = if (dateRaw.length >= 10) "${dateRaw.take(10)}T12:00:00Z" else "${LocalDate.now()}T12:00:00Z"
            if (runCatching { HashDedupCalculator.parseBookingDate(normalizedDate) }.isFailure) {
                failed++
                continue
            }
            val type = if (parsedAmount < BigDecimal.ZERO) "EXPENSE" else "INCOME"
            val absAmount = parsedAmount.abs().setScale(4, RoundingMode.HALF_UP)

            val hash = HashDedupCalculator.computeTransactionHash(
                accountId = account.id,
                amount = absAmount,
                currency = account.currency,
                bookingDateIso = normalizedDate,
                payee = payee
            )

            if (!seenHashes.add(hash)) {
                duplicates++
                continue
            }

            imported++
            createdItems.add(
                CreateRecordItem(
                    accountId = account.id,
                    type = type,
                    amount = absAmount,
                    bookingDate = normalizedDate,
                    payee = payee,
                    note = note,
                    status = "CLEARED",
                    skipIfDuplicateHash = true
                )
            )
        }

        return ImportStatementResponse(
            totalParsed = dataLines.size,
            importedCount = imported,
            duplicateSkippedCount = duplicates,
            failedCount = failed
        ) to createdItems
    }

    private fun parseOfxStatement(
        account: AccountDto,
        existingHashes: Set<String>,
        content: String
    ): Pair<ImportStatementResponse, List<CreateRecordItem>> {
        val blocks = content.split("<STMTTRN>", ignoreCase = true).drop(1)
        var imported = 0
        var duplicates = 0
        var failed = 0
        val createdItems = mutableListOf<CreateRecordItem>()
        val seenHashes = existingHashes.toMutableSet()

        fun extractTag(block: String, tag: String): String? {
            val regex = Regex("<$tag>([^<\\r\\n]+)", RegexOption.IGNORE_CASE)
            return regex.find(block)?.groupValues?.getOrNull(1)?.trim()
        }

        for (block in blocks) {
            val trnAmt = extractTag(block, "TRNAMT")?.replace(",", ".")
            val dtPosted = extractTag(block, "DTPOSTED")
            val name = extractTag(block, "NAME") ?: extractTag(block, "MEMO")
            val memo = extractTag(block, "MEMO")
            val fitId = extractTag(block, "FITID")

            val parsedAmount = trnAmt?.let { runCatching { BigDecimal(it) }.getOrNull() }
            if (parsedAmount == null || parsedAmount.compareTo(BigDecimal.ZERO) == 0) {
                failed++
                continue
            }

            val dateIso = if (dtPosted != null && dtPosted.length >= 8) {
                "${dtPosted.substring(0, 4)}-${dtPosted.substring(4, 6)}-${dtPosted.substring(6, 8)}T12:00:00Z"
            } else "${LocalDate.now()}T12:00:00Z"
            if (runCatching { HashDedupCalculator.parseBookingDate(dateIso) }.isFailure) {
                failed++
                continue
            }

            val type = if (parsedAmount < BigDecimal.ZERO) "EXPENSE" else "INCOME"
            val absAmount = parsedAmount.abs().setScale(4, RoundingMode.HALF_UP)
            val hash = HashDedupCalculator.computeTransactionHash(
                accountId = account.id,
                amount = absAmount,
                currency = account.currency,
                bookingDateIso = dateIso,
                payee = name
            )

            if (!seenHashes.add(hash)) {
                duplicates++
                continue
            }
            imported++
            createdItems.add(
                CreateRecordItem(
                    accountId = account.id,
                    type = type,
                    amount = absAmount,
                    bookingDate = dateIso,
                    payee = name,
                    note = memo,
                    externalId = fitId,
                    status = "CLEARED",
                    skipIfDuplicateHash = true
                )
            )
        }

        return ImportStatementResponse(
            totalParsed = blocks.size,
            importedCount = imported,
            duplicateSkippedCount = duplicates,
            failedCount = failed
        ) to createdItems
    }

    /**
     * Generates a localized CSV/TSV report from filtered transactions.
     */
    fun exportTransactionsToCsv(
        transactions: List<TransactionDto>,
        accounts: Map<String, AccountDto>,
        categories: Map<String, CategoryDto>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Date,Account,Type,Amount,Currency,Category,Payee,Status,Note")
        for (tx in transactions) {
            val accName = accounts[tx.accountId]?.name ?: tx.accountId
            val catName = tx.categoryId?.let { categories[it]?.name } ?: ""
            val safePayee = (tx.payee ?: "").replace("\"", "\"\"")
            val safeNote = (tx.note ?: "").replace("\"", "\"\"")
            sb.appendLine(
                "${tx.bookingDate.take(10)},\"$accName\",${tx.type},${tx.amount.toPlainString()},${tx.currency},\"$catName\",\"$safePayee\",${tx.status},\"$safeNote\""
            )
        }
        return sb.toString()
    }
}
