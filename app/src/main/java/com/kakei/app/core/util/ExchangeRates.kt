package com.kakei.app.core.util

import com.kakei.app.data.remote.dto.ExchangeRatesResponse
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * Exchange rates as served by `GET /profile/exchange-rates` (cached locally). There are
 * deliberately no built-in rates: an unknown pair yields `null` so callers can show the
 * original currency / "rate unavailable" instead of converting with an invented value.
 *
 * Cross rate from→to = unitsPerUsd(to) / unitsPerUsd(from). A user override for
 * (from, to) wins; an override for (to, from) is used inverted.
 */
data class ExchangeRateBook(
    /** ISO 4217 code → units of that currency per 1 USD. */
    val unitsPerUsd: Map<String, BigDecimal> = emptyMap(),
    /** (FROM, TO) → target units per 1 source unit, set manually by the user. */
    val overrides: Map<Pair<String, String>, BigDecimal> = emptyMap()
) {
    /** Target units per 1 source unit, or null when the pair is unknown. */
    fun rate(fromCurrency: String, toCurrency: String): BigDecimal? {
        val from = fromCurrency.trim().uppercase(Locale.ROOT)
        val to = toCurrency.trim().uppercase(Locale.ROOT)
        if (from == to) return BigDecimal.ONE
        overrides[from to to]?.takeIf { it.signum() > 0 }?.let { return it }
        overrides[to to from]?.takeIf { it.signum() > 0 }?.let {
            return BigDecimal.ONE.divide(it, MATH)
        }
        val fromUnits = unitsPerUsd[from]?.takeIf { it.signum() > 0 } ?: return null
        val toUnits = unitsPerUsd[to]?.takeIf { it.signum() > 0 } ?: return null
        return toUnits.divide(fromUnits, MATH)
    }

    fun convert(amount: BigDecimal, fromCurrency: String, toCurrency: String): BigDecimal? {
        val r = rate(fromCurrency, toCurrency) ?: return null
        return amount.multiply(r).setScale(4, RoundingMode.HALF_UP)
    }

    /** Every currency we hold a market rate or an override for. */
    val knownCurrencies: Set<String>
        get() = unitsPerUsd.keys + overrides.keys.flatMap { listOf(it.first, it.second) }

    companion object {
        private val MATH = MathContext(20, RoundingMode.HALF_EVEN)

        val EMPTY = ExchangeRateBook()

        fun from(response: ExchangeRatesResponse): ExchangeRateBook = ExchangeRateBook(
            unitsPerUsd = response.rates.associate { it.currency.trim().uppercase(Locale.ROOT) to it.unitsPerUsd },
            overrides = response.overrides.associate {
                (it.fromCurrency.trim().uppercase(Locale.ROOT) to it.toCurrency.trim().uppercase(Locale.ROOT)) to it.rate
            }
        )
    }
}

/**
 * Sum of amounts in several currencies converted to one target currency. Amounts whose
 * rate is unknown are kept per original currency in [unconverted] rather than dropped
 * or converted with a made-up rate.
 */
data class ConvertedTotal(
    val currency: String,
    val total: BigDecimal,
    val unconverted: Map<String, BigDecimal> = emptyMap()
) {
    companion object {
        fun of(
            items: List<Pair<BigDecimal, String>>,
            targetCurrency: String,
            rates: ExchangeRateBook
        ): ConvertedTotal {
            var total = BigDecimal.ZERO
            val missing = linkedMapOf<String, BigDecimal>()
            for ((amount, currency) in items) {
                val converted = rates.convert(amount, currency, targetCurrency)
                if (converted != null) {
                    total += converted
                } else {
                    val key = currency.uppercase(Locale.ROOT)
                    missing[key] = (missing[key] ?: BigDecimal.ZERO) + amount
                }
            }
            return ConvertedTotal(targetCurrency, total, missing)
        }
    }
}
