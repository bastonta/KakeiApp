package com.kakei.app.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Computes deterministic SHA-256 deduplication hashes identical to Kakei's `libs/dedup`:
 * TxHash = SHA256(AccountId || Amount || Currency || BookingDate || Payee)
 */
object HashDedupCalculator {

    fun computeTransactionHash(
        accountId: String,
        amount: BigDecimal,
        currency: String,
        bookingDateIso: String,
        payee: String?
    ): String {
        val normalizedAmount = amount.setScale(4, RoundingMode.HALF_UP).toPlainString()
        val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
        val normalizedDate = bookingDateIso.take(10) // YYYY-MM-DD
        val normalizedPayee = (payee ?: "").trim().lowercase(Locale.ROOT)

        val composite = "$accountId|$normalizedAmount|$normalizedCurrency|$normalizedDate|$normalizedPayee"
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(composite.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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

    // Deterministic offline fallback FX rates relative to USD for multi-currency consolidation
    private val usdRates = mapOf(
        "USD" to BigDecimal("1.0000"),
        "EUR" to BigDecimal("0.9200"),
        "GBP" to BigDecimal("0.7900"),
        "KZT" to BigDecimal("485.0000"),
        "RUB" to BigDecimal("92.5000"),
        "JPY" to BigDecimal("152.0000"),
        "CNY" to BigDecimal("7.2400"),
        "AED" to BigDecimal("3.6725"),
        "TRY" to BigDecimal("34.2000")
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

    fun convert(amount: BigDecimal, fromCurrency: String, toCurrency: String): BigDecimal {
        val from = fromCurrency.uppercase(Locale.ROOT)
        val to = toCurrency.uppercase(Locale.ROOT)
        if (from == to) return amount.setScale(4, RoundingMode.HALF_UP)
        val fromRate = usdRates[from] ?: BigDecimal.ONE
        val toRate = usdRates[to] ?: BigDecimal.ONE
        val inUsd = amount.divide(fromRate, 6, RoundingMode.HALF_UP)
        return inUsd.multiply(toRate).setScale(4, RoundingMode.HALF_UP)
    }

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
