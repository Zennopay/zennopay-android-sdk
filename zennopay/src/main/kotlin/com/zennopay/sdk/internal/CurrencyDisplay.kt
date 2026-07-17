package com.zennopay.sdk.internal

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Pure JVM currency + limit helpers, the Kotlin port of the iOS SDK's
 * `CurrencyLimits.swift`. The UI layer formats on top of these; the backend
 * remains authoritative for money movement.
 *
 * Symbol / label / flag / formatting are keyed by the numeric ISO-4217 codes
 * the backend returns (`704` VND, `764` THB, `840` USD). All numeric display
 * must be rendered tabular-nums at the view layer (DESIGN.md).
 *
 * LOCAL currency is always the PRIMARY amount in the sheet (partner-approved
 * reference designs); USD is the secondary chip. Grouping is fixed to Western
 * thousands (a device-locale formatter would render VND in lakhs on an
 * Indian-region device, for example).
 */
internal object CurrencyDisplay {

    /** Currency symbol for a numeric ISO-4217 code. */
    fun symbol(numeric: String?): String = when (numeric) {
        "764" -> "฿" // THB
        "704" -> "₫" // VND
        "840" -> "$" // USD
        else -> ""
    }

    /** Short alpha label for a numeric ISO-4217 code. */
    fun label(numeric: String?): String = when (numeric) {
        "764" -> "THB"
        "704" -> "VND"
        "840" -> "USD"
        else -> numeric ?: ""
    }

    /**
     * Flag emoji for a numeric ISO-4217 code (merchant avatar and the
     * secondary-amount chip).
     */
    fun flag(numeric: String?): String = when (numeric) {
        "764" -> "🇹🇭"
        "704" -> "🇻🇳"
        "840" -> "🇺🇸"
        else -> "🏳️"
    }

    /**
     * Whether a numeric ISO-4217 code uses minor units in display. VND (704)
     * has no minor unit in practice; THB (764) uses satang (2 places).
     */
    fun fractionDigits(numeric: String?): Int = if (numeric == "704") 0 else 2

    /** Shared formatter core: Western thousands grouping, fixed fraction digits. */
    fun groupedNumber(value: Double, fractionDigits: Int): String {
        val symbols = DecimalFormatSymbols(Locale.US)
        val pattern = if (fractionDigits > 0) {
            "#,##0." + "0".repeat(fractionDigits)
        } else {
            "#,##0"
        }
        return DecimalFormat(pattern, symbols).format(value)
    }

    /**
     * Render a minor-unit amount with the currency symbol prefixed, e.g.
     * `฿120.00` / `₫3,500,000`. The backend value is authoritative and always
     * uses hundredths as its minor-unit convention; VND is shown without a
     * fractional part, THB with two places.
     */
    fun formatMinor(minorUnits: Long, numeric: String?): String =
        symbol(numeric) + groupedNumber(minorUnits / 100.0, fractionDigits(numeric))

    /**
     * Render a minor-unit amount with the alpha label suffixed and no symbol,
     * e.g. `3,500,000 VND` — the receipt-card hero format.
     */
    fun formatMinorWithLabel(minorUnits: Long, numeric: String?): String =
        groupedNumber(minorUnits / 100.0, fractionDigits(numeric)) + " " + label(numeric)

    /** Render a USD cent amount as `$1,140.00` (grouped, always 2 places). */
    fun formatUsdCents(cents: Long): String =
        "$" + groupedNumber(cents / 100.0, 2)

    /**
     * The implied exchange-rate line for the detail screens, e.g.
     * `1 USD = 25,000.00 VND`. Null when either side is zero/absent.
     */
    fun exchangeRateLine(usdCents: Long, localMinorUnits: Long?, localCurrency: String?): String? {
        if (usdCents <= 0L) return null
        val minor = localMinorUnits ?: return null
        if (minor <= 0L) return null
        val rate = (minor / 100.0) / (usdCents / 100.0)
        return "1 USD = ${groupedNumber(rate, 2)} ${label(localCurrency)}"
    }
}

/**
 * VND disbursement caps, enforced authoritatively by the backend. Only the
 * per-transaction cap is a client-side pre-check; the daily (10,000,000) and
 * monthly (25,000,000) caps require server state and surface at confirm.
 */
internal object DisbursementLimit {
    /** Per-transaction VND cap (₫5,000,000) in the backend's minor-unit convention. */
    const val VND_PER_TRANSACTION_MINOR_UNITS: Long = 500_000_000L

    /** VND numeric ISO-4217 code. */
    const val VND_NUMERIC: String = "704"

    /**
     * Client pre-check: is the (dynamic or entered) amount above the
     * per-transaction VND cap? Only applies to VND (704).
     */
    fun exceedsVndPerTransaction(minorUnits: Long, currencyNumeric: String?): Boolean =
        currencyNumeric == VND_NUMERIC && minorUnits > VND_PER_TRANSACTION_MINOR_UNITS
}
