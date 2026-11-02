package com.kakei.app.core.i18n

import android.content.res.Resources
import com.kakei.app.R

/** Short day count, e.g. "5 дн." / "5d". */
fun Resources.days(count: Long): String = getString(R.string.days_short, count)

/** Display name for an account type code (CASH, CHECKING, ...). Unknown codes are shown as-is. */
fun Resources.accountType(code: String): String = when (code) {
    "CASH" -> getString(R.string.account_type_cash)
    "CHECKING" -> getString(R.string.account_type_checking)
    "CREDIT" -> getString(R.string.account_type_credit)
    "DEBT" -> getString(R.string.account_type_debt)
    "INVESTMENT" -> getString(R.string.account_type_investment)
    "SAVINGS" -> getString(R.string.account_type_savings)
    else -> code
}

/** Display name for a group permission level (ADMIN, TRACK_READ, READ_ONLY). */
fun Resources.permissionLevel(code: String): String = when (code) {
    "ADMIN" -> getString(R.string.permission_admin)
    "TRACK_READ" -> getString(R.string.permission_track_read)
    "READ_ONLY" -> getString(R.string.permission_read_only)
    else -> code
}

/** Display name for a record clearing status (UNCLEARED, CLEARED, RECONCILED). */
fun Resources.clearingStatus(code: String): String = when (code) {
    "UNCLEARED" -> getString(R.string.clearing_uncleared)
    "CLEARED" -> getString(R.string.clearing_cleared)
    "RECONCILED" -> getString(R.string.clearing_reconciled)
    else -> code
}

/** Display name for a record / obligation type (EXPENSE, INCOME, TRANSFER). */
fun Resources.operationType(code: String): String = when (code) {
    "EXPENSE" -> getString(R.string.expense_label)
    "INCOME" -> getString(R.string.income_label)
    "TRANSFER" -> getString(R.string.transfer_label)
    else -> code
}

/** Display name for a budget or obligation period (WEEKLY, MONTHLY, YEARLY, ONE_OFF). */
fun Resources.period(code: String): String = when (code) {
    "WEEKLY" -> getString(R.string.period_weekly)
    "MONTHLY" -> getString(R.string.period_monthly)
    "YEARLY" -> getString(R.string.period_yearly)
    "EVERY_N_DAYS" -> getString(R.string.period_every_n_days)
    "ONE_OFF" -> getString(R.string.period_one_off)
    else -> code
}

/** Schedule caption for a planned payment: "Месяц · 31-го", "Каждые 30 дн.". */
fun Resources.obligationPeriod(code: String, dayOfMonth: Int?, intervalDays: Int?): String = when {
    code == "EVERY_N_DAYS" && intervalDays != null -> getString(R.string.period_every_days, intervalDays)
    code == "MONTHLY" && dayOfMonth != null -> period(code) + " · " + getString(R.string.period_day_of_month, dayOfMonth)
    else -> period(code)
}
