package com.zennopay.sdk.internal.net

import org.json.JSONObject

/**
 * The status rollup returned by `GET /v1/payment_intents/{id}/receipt`.
 *
 * The receipt endpoint collapses the richer intent lifecycle
 * (`created`/`authorized`/`captured`/`failed`/`refunded`/`reversed`/`expired`)
 * into the four states a past-payment receipt needs to render:
 *  - [PENDING]  — not yet terminal; the SDK polls until it resolves.
 *  - [CAPTURED] — settled successfully; show the success receipt.
 *  - [FAILED]   — terminal failure; show the failure screen.
 *  - [REFUNDED] — captured then refunded; show the receipt with refund copy.
 *
 * The Kotlin mirror of the iOS SDK's `ReceiptStatus`.
 */
internal enum class ReceiptStatus {
    PENDING,
    CAPTURED,
    FAILED,
    REFUNDED;

    /** A state the receipt will not move out of on its own — polling stops. */
    val isTerminal: Boolean get() = this != PENDING

    companion object {
        /** The parsed status, or null for an unknown wire value. */
        fun fromWire(raw: String?): ReceiptStatus? = when (raw?.lowercase()) {
            "pending" -> PENDING
            "captured" -> CAPTURED
            "failed" -> FAILED
            "refunded" -> REFUNDED
            else -> null
        }
    }
}

/**
 * Response body for `GET /v1/payment_intents/{id}/receipt` — the authoritative
 * receipt for a past payment, authenticated by a partner-minted RS256 receipt
 * token (`aud = zennopay-receipt`). The Kotlin mirror of the iOS `ReceiptDTO`.
 *
 * The parse is deliberately tolerant: only `intent_id`/`status` are load-bearing;
 * every display field is optional and the soft numeric field ([exchangeRate])
 * accepts either a JSON number OR a numeric string, so a wire-shape drift can
 * never fail the whole parse (the SDK still renders what it has). The backend
 * remains authoritative.
 */
internal data class ReceiptDto(
    val intentId: String?,
    /** "pending" | "captured" | "failed" | "refunded". */
    val status: String?,
    val merchant: ReceiptMerchant?,
    val amountUsdCents: Long,
    val localAmountMinorUnits: Long?,
    /**
     * Numeric ISO-4217 (e.g. "704") or alpha (e.g. "VND") — normalized at the
     * display layer via [com.zennopay.sdk.internal.CurrencyDisplay.numericCode].
     */
    val localCurrency: String?,
    val exchangeRate: Double?,
    val fees: ReceiptFees?,
    val corridor: String?,
    /** Provider transaction reference — shown as the receipt's "Transaction ID". */
    val transactionRef: String?,
    val createdAt: String?,
    val updatedAt: String?,
) {
    /** The parsed status, or null for an unknown wire value. */
    val receiptStatus: ReceiptStatus? get() = ReceiptStatus.fromWire(status)

    /**
     * The beneficiary merchant. [accountNo] is already masked to the last 4 by
     * the backend (never a full PAN), so the SDK renders it verbatim.
     */
    data class ReceiptMerchant(
        val name: String?,
        val accountNo: String?,
        val bankNo: String?,
        val country: String?,
    ) {
        companion object {
            fun parse(json: JSONObject?): ReceiptMerchant? {
                if (json == null) return null
                return ReceiptMerchant(
                    name = json.optStringOrNull("name"),
                    accountNo = json.optStringOrNull("account_no"),
                    bankNo = json.optStringOrNull("bank_no"),
                    country = json.optStringOrNull("country"),
                )
            }
        }
    }

    /** The Zennopay margin taken on the corridor, in USD cents. */
    data class ReceiptFees(val marginUsdCents: Long?) {
        companion object {
            fun parse(json: JSONObject?): ReceiptFees? {
                if (json == null) return null
                return ReceiptFees(marginUsdCents = json.optLongOrNull("margin_usd_cents"))
            }
        }
    }

    companion object {
        fun parse(json: JSONObject): ReceiptDto = ReceiptDto(
            intentId = json.optStringOrNull("intent_id"),
            status = json.optStringOrNull("status"),
            merchant = ReceiptMerchant.parse(json.optJSONObject("merchant")),
            // Load-bearing display field, but a missing amount must not fail the
            // whole parse — default to 0 like iOS.
            amountUsdCents = json.optLongOrNull("amount_usd_cents") ?: 0L,
            localAmountMinorUnits = json.optLongOrNull("local_amount_minor_units"),
            localCurrency = json.optStringOrNull("local_currency"),
            exchangeRate = parseSoftDouble(json, "exchange_rate"),
            fees = ReceiptFees.parse(json.optJSONObject("fees")),
            corridor = json.optStringOrNull("corridor"),
            transactionRef = json.optStringOrNull("transaction_ref"),
            createdAt = json.optStringOrNull("created_at"),
            updatedAt = json.optStringOrNull("updated_at"),
        )

        /**
         * `exchange_rate` may arrive as a JSON number OR a numeric string. Accept
         * both; return null for anything else rather than failing the parse.
         */
        private fun parseSoftDouble(json: JSONObject, key: String): Double? {
            if (!json.has(key) || json.isNull(key)) return null
            return when (val raw = json.opt(key)) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
        }
    }
}
