package com.miniwall.core.logic

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** Billing period of a product. */
sealed interface Period {
    data object Lifetime : Period
    data class Every(val unit: Unit, val count: Int = 1) : Period

    enum class Unit(val key: String, val days: Double, val abbreviation: String, val periodly: String) {
        DAY("day", 1.0, "day", "daily"),
        WEEK("week", 7.0, "wk", "weekly"),
        MONTH("month", 365.0 / 12, "mo", "monthly"),
        YEAR("year", 365.0, "yr", "yearly"),
    }
}

/**
 * A product's price as the app knows it (from StoreKit / Play Billing / its server).
 * Miniwall never charges (D-109): this only fills `{{ product.* }}` texts.
 */
data class ProductInfo(
    val price: Double,
    /** ISO 4217 code, any case (`usd`, `EUR`). */
    val currency: String,
    val period: Period? = null,
    /** Shown by `{{ product.store_product_name }}`. */
    val title: String? = null,
    /** Introductory or trial offer; a free trial has price 0. */
    val offer: Offer? = null,
) {
    data class Offer(val price: Double, val period: Period)
}

/** `{{ product.* }}` values per package (Web SDK src/pricing.ts). */
object Pricing {
    private val MONTH_DAYS = Period.Unit.MONTH.days

    /** `en_US` (paywall JSON) -> Locale; null -> en-US (the web's default in tests and most hosts). */
    fun locale(code: String?): Locale = if (code.isNullOrEmpty()) Locale.US else Locale.forLanguageTag(code.replace('_', '-'))

    fun money(amount: Double, currency: String, locale: Locale): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        // An unknown code ("", "US") must not crash the screen: show the amount and the code as given.
        val c = runCatching { Currency.getInstance(currency.uppercase()) }.getOrNull() ?: return "%.2f %s".format(Locale.US, amount, currency)
        format.roundingMode = java.math.RoundingMode.HALF_UP // Intl rounds half away from zero; Java defaults to half-even
        format.currency = c
        format.minimumFractionDigits = c.defaultFractionDigits.coerceAtLeast(0)
        format.maximumFractionDigits = c.defaultFractionDigits.coerceAtLeast(0)
        return format.format(amount)
    }

    fun currencySymbol(currency: String, locale: Locale): String =
        runCatching { Currency.getInstance(currency.uppercase()).getSymbol(locale) }.getOrDefault(currency)

    private fun periodVariables(prefix: String, period: Period?): Map<String, String> {
        val k = if (prefix == "product") "product." else "product.offer_"
        return when (period) {
            null -> emptyMap()
            Period.Lifetime -> mapOf("${k}period" to "lifetime", "${k}period_abbreviated" to "lifetime", "${k}period_with_unit" to "lifetime")
            is Period.Every -> {
                val days = period.unit.days * period.count
                val plural = if (period.count == 1) period.unit.key else "${period.unit.key}s"
                val vars = linkedMapOf(
                    "${k}period" to if (period.count == 1) period.unit.key else "${period.count} $plural",
                    "${k}period_abbreviated" to if (period.count == 1) period.unit.abbreviation else "${period.count} ${period.unit.abbreviation}",
                    "${k}period_with_unit" to "${period.count} $plural",
                    "${k}period_in_days" to jsRound(days).toString(),
                    "${k}period_in_weeks" to jsRound(days / 7).toString(),
                    "${k}period_in_months" to jsRound(days / MONTH_DAYS).toString(),
                    "${k}period_in_years" to floor(days / Period.Unit.YEAR.days).toLong().toString(),
                )
                if (prefix == "product") vars["product.periodly"] = if (period.count == 1) period.unit.periodly else "every ${period.count} $plural"
                vars
            }
        }
    }

    private fun pricePerVariables(prefix: String, price: Double, currency: String, period: Period?, locale: Locale): Map<String, String> {
        if (period !is Period.Every) return emptyMap()
        val days = period.unit.days * period.count
        val key = if (prefix == "product") "product.price_per_" else "product.offer_price_per_"
        return Period.Unit.entries.associate { unit -> "$key${unit.key}" to money(price / days * unit.days, currency, locale) }
    }

    private fun monthlyPrice(p: ProductInfo): Double? {
        val period = p.period as? Period.Every ?: return null
        return p.price / ((period.unit.days * period.count) / MONTH_DAYS)
    }

    /** Math.round: halves round up (toward +infinity). */
    private fun jsRound(v: Double): Long = floor(v + 0.5).toLong()

    /**
     * Builds the `{{ product.* }}` values per package id. `relative_discount` compares each
     * package's monthly price with the most expensive package's (annual vs monthly -> "33%").
     */
    fun productVariables(products: Map<String, ProductInfo>, localeCode: String?): Map<String, Map<String, String>> {
        val locale = locale(localeCode)
        val monthly = products.values.mapNotNull(::monthlyPrice)
        val highest = if (monthly.size > 1) monthly.max() else null
        return products.mapValues { (_, product) ->
            val price = money(product.price, product.currency, locale)
            val period = periodVariables("product", product.period)
            val vars = linkedMapOf(
                "product.price" to price,
                "product.currency_code" to product.currency.uppercase(),
                "product.currency_symbol" to currencySymbol(product.currency, locale),
            )
            vars.putAll(period)
            vars.putAll(pricePerVariables("product", product.price, product.currency, product.period, locale))
            if (period["product.period"] != null) {
                vars["product.price_per_period"] = if (product.period == Period.Lifetime) price else "$price/${period["product.period"]}"
                vars["product.price_per_period_abbreviated"] = if (product.period == Period.Lifetime) price else "$price/${period["product.period_abbreviated"]}"
            }
            product.title?.let { vars["product.store_product_name"] = it }
            val own = monthlyPrice(product)
            if (highest != null && own != null && own < highest) {
                vars["product.relative_discount"] = "${(1 - own / highest).times(100).roundToLong()}%"
            }
            product.offer?.let { offer ->
                vars["product.offer_price"] = money(offer.price, product.currency, locale)
                vars.putAll(periodVariables("product.offer", offer.period))
                vars.putAll(pricePerVariables("product.offer", offer.price, product.currency, offer.period, locale))
            }
            vars
        }
    }
}
