package com.kakei.app.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * Computes deterministic SHA-256 deduplication hashes byte-identical to the Kakei
 * server's canonical v2 format:
 *
 * `SHA256("{accountId}|{amount}|{CURRENCY}|{bookingDate}|{payee}")`, lowercase hex, where
 * - accountId   = lowercase hyphenated UUID
 * - amount      = |amount| rounded to 4 dp (HALF_EVEN, like rust_decimal `round_dp`), trailing zeros stripped; zero is "0"
 * - CURRENCY    = trimmed, uppercased
 * - bookingDate = UTC, truncated to whole seconds, `yyyy-MM-dd'T'HH:mm:ss'Z'`
 * - payee       = trimmed, lowercased (Locale.ROOT); empty when null
 */
object HashDedupCalculator {

    private val bookingDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun canonicalInput(
        accountId: String,
        amount: BigDecimal,
        currency: String,
        bookingDate: Instant,
        payee: String?
    ): String {
        val normalizedAccount = runCatching { UUID.fromString(accountId.trim()).toString() }
            .getOrDefault(accountId.trim().lowercase(Locale.ROOT))
        val rounded = amount.abs().setScale(4, RoundingMode.HALF_EVEN)
        val normalizedAmount = if (rounded.signum() == 0) "0" else rounded.stripTrailingZeros().toPlainString()
        val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
        val normalizedDate = bookingDateFormatter.format(bookingDate.truncatedTo(ChronoUnit.SECONDS))
        val normalizedPayee = (payee ?: "").trim().lowercase(Locale.ROOT)
        return "$normalizedAccount|$normalizedAmount|$normalizedCurrency|$normalizedDate|$normalizedPayee"
    }

    fun computeTransactionHash(
        accountId: String,
        amount: BigDecimal,
        currency: String,
        bookingDate: Instant,
        payee: String?
    ): String {
        val composite = canonicalInput(accountId, amount, currency, bookingDate, payee)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(composite.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Convenience overload for ISO 8601 booking dates as stored in [com.kakei.app.data.remote.dto.TransactionDto]. */
    fun computeTransactionHash(
        accountId: String,
        amount: BigDecimal,
        currency: String,
        bookingDateIso: String,
        payee: String?
    ): String = computeTransactionHash(accountId, amount, currency, parseBookingDate(bookingDateIso), payee)

    /** Accepts `...Z` instants, offset date-times and bare `yyyy-MM-dd` dates (midnight UTC). */
    fun parseBookingDate(iso: String): Instant {
        val raw = iso.trim()
        return runCatching { Instant.parse(raw) }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
            .recoverCatching { LocalDate.parse(raw.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }
            .getOrThrow()
    }
}

/**
 * Formats financial BigDecimal amounts and ISO 4217 currencies consistently without floating-point loss.
 */
object CurrencyFormatter {

    private val symbols = mapOf(
        "KZT" to "₸",
        "USD" to "$",
        "EUR" to "€",
        "RUB" to "₽",
        "GBP" to "£",
        "JPY" to "¥",
        "CNY" to "¥",
        "AED" to "د.إ",
        "TRY" to "₺"
    )

    val supportedCurrencies = listOf("KZT", "USD", "EUR", "RUB", "GBP", "JPY", "CNY", "AED", "TRY")

    fun symbolFor(currency: String): String =
        symbols[currency.uppercase(Locale.ROOT)] ?: currency.uppercase(Locale.ROOT)

    fun format(amount: BigDecimal, currency: String, showPlus: Boolean = false): String {
        val dfSymbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = ' '
            decimalSeparator = '.'
        }
        val scale = if (amount.stripTrailingZeros().scale() <= 0) 0 else 2
        val pattern = if (scale == 0) "#,##0" else "#,##0.00"
        val formatter = DecimalFormat(pattern, dfSymbols)
        val absFormatted = formatter.format(amount.abs().setScale(scale, RoundingMode.HALF_UP))
        val sign = when {
            amount < BigDecimal.ZERO -> "-"
            showPlus && amount > BigDecimal.ZERO -> "+"
            else -> ""
        }
        return "$sign$absFormatted ${symbolFor(currency)}"
    }

    /**
     * Formats an exchange rate with the same separators as [format]: two decimals for
     * rates of 1 and above, four for smaller ones, trailing zeros dropped ("521.74", "0.0021").
     */
    fun formatRate(rate: BigDecimal): String {
        val dfSymbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = ' '
            decimalSeparator = '.'
        }
        val pattern = if (rate.abs() >= BigDecimal.ONE) "#,##0.##" else "#,##0.####"
        return DecimalFormat(pattern, dfSymbols).apply { roundingMode = RoundingMode.HALF_UP }.format(rate)
    }

    /**
     * Converts [amount] with the server-provided [rates]. Returns null when no rate is
     * known for the pair: callers must show the original currency instead of guessing.
     */
    fun convert(amount: BigDecimal, fromCurrency: String, toCurrency: String, rates: ExchangeRateBook): BigDecimal? =
        rates.convert(amount, fromCurrency, toCurrency)

    fun formatShortDate(isoTimestamp: String): String {
        return try {
            val instant = Instant.parse(isoTimestamp)
            DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault())
                .withZone(ZoneOffset.systemDefault())
                .format(instant)
        } catch (_: Exception) {
            isoTimestamp.take(10)
        }
    }

    fun todayIsoDate(): String = LocalDate.now().toString()
}
