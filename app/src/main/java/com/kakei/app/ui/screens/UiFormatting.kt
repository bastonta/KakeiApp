package com.kakei.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Work
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.i18n.AppStrings
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.data.remote.dto.CategoryDto
import com.kakei.app.data.remote.dto.TransactionDto
import com.kakei.app.ui.components.iconForKey
import com.kakei.app.ui.components.parseHexColor
import com.kakei.app.ui.theme.KakeiTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Icon + accent color used to draw a record's category tile. */
data class CategoryVisual(val icon: ImageVector, val color: Color)

/** Visual for a ledger record: transfers and uncategorized income get their own glyphs. */
@Composable
fun recordVisual(tx: TransactionDto, category: CategoryDto?): CategoryVisual {
    val c = KakeiTheme.colors
    return when {
        tx.type == "TRANSFER" -> CategoryVisual(Icons.Outlined.SwapHoriz, c.cyan)
        category != null -> categoryVisual(category)
        tx.type == "INCOME" -> CategoryVisual(Icons.Outlined.Work, c.mint)
        else -> CategoryVisual(iconForKey(null), c.muted)
    }
}

@Composable
fun categoryVisual(category: CategoryDto): CategoryVisual =
    CategoryVisual(iconForKey(category.iconKey), parseHexColor(category.colorHex, KakeiTheme.colors.mint))

/** "−8 450 ₸" / "+650 000 ₸" / "⇄ 50 000 ₸", using a typographic minus. */
fun signedRecordAmount(tx: TransactionDto): String {
    val formatted = CurrencyFormatter.format(tx.amount.abs(), tx.currency)
    return when (tx.type) {
        "INCOME" -> "+$formatted"
        "TRANSFER" -> "⇄ $formatted"
        else -> "−$formatted"
    }
}

/** Formats with a typographic minus instead of a hyphen. */
fun formatMoney(amount: BigDecimal, currency: String, showPlus: Boolean = false): String =
    CurrencyFormatter.format(amount, currency, showPlus).replaceFirst('-', '−')

/** Compact amount for small cards: "4,81 млн ₸" / "4.81M ₸" from one million up. */
fun formatCompactMoney(amount: BigDecimal, currency: String, s: AppStrings): String {
    val million = BigDecimal(1_000_000)
    if (amount.abs() < million) return formatMoney(amount, currency)
    val value = amount.divide(million, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    val sign = if (amount.signum() < 0) "−" else ""
    val number = value.removePrefix("-")
    return if (s.language == AppLanguage.RU) {
        "$sign${number.replace('.', ',')} млн ${CurrencyFormatter.symbolFor(currency)}"
    } else {
        "$sign${number}M ${CurrencyFormatter.symbolFor(currency)}"
    }
}

fun localeFor(s: AppStrings): Locale =
    if (s.language == AppLanguage.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH

/** Parses a record's booking timestamp (ISO instant, or a bare date) in the device time zone. */
fun bookingDateTime(iso: String): LocalDateTime? = runCatching {
    LocalDateTime.ofInstant(Instant.parse(iso), ZoneId.systemDefault())
}.recoverCatching {
    LocalDate.parse(iso.take(10)).atStartOfDay()
}.getOrNull()

/** "Сегодня", "Вчера" or "12 октября" / "Today", "Yesterday", "October 12". */
fun dayLabel(date: LocalDate, s: AppStrings): String {
    val today = LocalDate.now()
    return when (date) {
        today -> s.pick("Сегодня", "Today")
        today.minusDays(1) -> s.pick("Вчера", "Yesterday")
        else -> {
            val pattern = if (s.language == AppLanguage.RU) "d MMMM" else "MMMM d"
            val withYear = if (date.year != today.year) "$pattern yyyy" else pattern
            DateTimeFormatter.ofPattern(withYear, localeFor(s)).format(date)
        }
    }
}

/** "до 15 ноября" style short date, e.g. for due dates and deadlines. */
fun shortDate(isoDate: String?, s: AppStrings, withYear: Boolean = false): String? {
    val date = isoDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null
    val base = if (s.language == AppLanguage.RU) "d MMM" else "MMM d"
    val pattern = if (withYear || date.year != LocalDate.now().year) "$base yyyy" else base
    return DateTimeFormatter.ofPattern(pattern, localeFor(s)).format(date).replace(".", "")
}

/** Standalone month name, capitalized: "Октябрь" / "October". */
fun monthName(month: Int, s: AppStrings, standalone: Boolean = true): String =
    java.time.Month.of(month.coerceIn(1, 12))
        .getDisplayName(if (standalone) TextStyle.FULL_STANDALONE else TextStyle.FULL, localeFor(s))
        .replaceFirstChar { it.titlecase(localeFor(s)) }

/** Russian plural form picker: 1 операция, 2 операции, 5 операций. */
fun ruPlural(n: Long, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
