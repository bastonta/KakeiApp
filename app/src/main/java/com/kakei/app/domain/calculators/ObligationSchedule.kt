package com.kakei.app.domain.calculators

import com.kakei.app.data.remote.dto.PlannedObligationDto
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Recurrence rule of a planned payment, mirroring the server's `Schedule`.
 * Occurrences are derived from [startDate] (and [dayOfMonth] for MONTHLY), never from
 * the previous occurrence, so a short month does not shift the following ones:
 * the 31st becomes Feb 28 (29) and returns to Mar 31.
 */
data class ObligationSchedule(
    val frequency: String,
    val startDate: LocalDate,
    val dayOfMonth: Int? = null,
    val intervalDays: Int? = null
) {
    /** First occurrence on or after [from] (never before [startDate]); null once a ONE_OFF is past. */
    fun firstOnOrAfter(from: LocalDate): LocalDate? {
        val f = if (from.isBefore(startDate)) startDate else from
        return when (frequency.uppercase(Locale.ROOT)) {
            "DAILY" -> f
            "WEEKLY" -> stepFrom(f, 7)
            "EVERY_N_DAYS" -> stepFrom(f, (intervalDays ?: 1).coerceAtLeast(1).toLong())
            "YEARLY" -> {
                val thisYear = clamped(YearMonth.of(f.year, startDate.month), startDate.dayOfMonth)
                if (!thisYear.isBefore(f)) thisYear
                else clamped(YearMonth.of(f.year + 1, startDate.month), startDate.dayOfMonth)
            }
            "ONE_OFF" -> if (f == startDate) startDate else null
            else -> { // MONTHLY
                val day = (dayOfMonth ?: startDate.dayOfMonth).coerceIn(1, 31)
                val month = YearMonth.from(f)
                val thisMonth = clamped(month, day)
                if (!thisMonth.isBefore(f)) thisMonth else clamped(month.plusMonths(1), day)
            }
        }
    }

    /** Occurrence that follows [date]. */
    fun nextAfter(date: LocalDate): LocalDate? = firstOnOrAfter(date.plusDays(1))

    private fun stepFrom(from: LocalDate, step: Long): LocalDate {
        val elapsed = ChronoUnit.DAYS.between(startDate, from)
        val steps = (elapsed + step - 1) / step
        return startDate.plusDays(steps * step)
    }

    private fun clamped(month: YearMonth, day: Int): LocalDate =
        month.atDay(day.coerceAtMost(month.lengthOfMonth()))

    companion object {
        fun of(ob: PlannedObligationDto): ObligationSchedule {
            val start = ob.startDate.ifBlank { ob.nextDueDate }
            return ObligationSchedule(
                frequency = ob.frequency,
                startDate = runCatching { LocalDate.parse(start.take(10)) }.getOrDefault(LocalDate.now()),
                dayOfMonth = ob.dayOfMonth,
                intervalDays = ob.intervalDays
            )
        }
    }
}
