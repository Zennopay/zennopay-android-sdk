package com.zennopay.sdk.internal.net

import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.ZennopayError
import org.json.JSONObject

/**
 * Merchant block returned by `/scan` (and echoed on `/confirm` + `GET /:id`).
 *
 * Shape is the canonical SDK REST contract (docs/sdk-rest-contract.md):
 * `{ "scheme": "promptpay"|"vietqr", "name", "city", "country", "mcc" }`.
 * All fields are display-only; the backend is authoritative for money movement.
 */
internal data class Merchant(
    val scheme: String?,
    val name: String?,
    val city: String?,
    val country: String?,
    val mcc: String?,
) {
    companion object {
        fun parse(json: JSONObject?): Merchant? {
            if (json == null) return null
            return Merchant(
                scheme = json.optStringOrNull("scheme"),
                name = json.optStringOrNull("name"),
                city = json.optStringOrNull("city"),
                country = json.optStringOrNull("country"),
                mcc = json.optStringOrNull("mcc"),
            )
        }
    }
}

/**
 * The FX quote produced by `/scan` (T-QUOTE-BINDING). `quoteId` + `quoteVersion`
 * bind the later `/confirm` to a specific quote; the server rejects a confirm
 * that presents a stale/superseded quote.
 *
 * Canonical shape (docs/sdk-rest-contract.md → `scan.response.json`):
 * ```
 * "quote": {
 *   "quote_id": str, "quote_version": int,
 *   "amount_usd_cents": int, "local_amount_minor_units": int,
 *   "local_currency": str /* numeric ISO-4217, e.g. "704","764" */,
 *   "expires_at": int /* epoch millis */
 * }
 * ```
 */
internal data class Quote(
    val quoteId: String?,
    val quoteVersion: Int?,
    val amountUsdCents: Long?,
    val localAmountMinorUnits: Long?,
    /** Numeric ISO-4217 currency code as a string, e.g. "704" (VND), "764" (THB). */
    val localCurrency: String?,
    /** Quote expiry as epoch milliseconds. */
    val expiresAt: Long?,
) {
    companion object {
        fun parse(json: JSONObject?): Quote? {
            if (json == null) return null
            return Quote(
                quoteId = json.optStringOrNull("quote_id"),
                quoteVersion = json.optIntOrNull("quote_version"),
                amountUsdCents = json.optLongOrNull("amount_usd_cents"),
                localAmountMinorUnits = json.optLongOrNull("local_amount_minor_units"),
                localCurrency = json.optStringOrNull("local_currency"),
                expiresAt = json.optLongOrNull("expires_at"),
            )
        }
    }
}

/**
 * Parsed response of `POST /v1/payment_intents/{id}/scan`.
 *
 * Aligned EXACTLY to the canonical SDK REST contract
 * (docs/sdk-rest-contract.md / `sdk-contract-fixtures/scan.response.json`):
 * ```
 * {
 *   "intent_id": str, "status": str,
 *   "merchant": { scheme, name, city, country, mcc },
 *   "qr_kind": "dynamic" | "static",
 *   "quote": { quote_id, quote_version, amount_usd_cents,
 *              local_amount_minor_units, local_currency, expires_at }
 * }
 * ```
 *
 * `qrKind == "dynamic"` means the QR carries its own amount (EMVCo tag 54) — the
 * amount screen is then read-only. A static QR requires a user-entered amount.
 */
internal data class ScanResult(
    val intentId: String?,
    val status: IntentState,
    val merchant: Merchant?,
    /** "dynamic" (amount embedded, read-only) or "static" (user must enter). */
    val qrKind: String?,
    val quote: Quote?,
) {
    /** True when the QR embeds its own amount (dynamic) — amount is read-only. */
    val amountFixed: Boolean get() = qrKind.equals("dynamic", ignoreCase = true)

    val merchantName: String? get() = merchant?.name
    val merchantCity: String? get() = merchant?.city

    /** Numeric ISO-4217 local currency (e.g. "704","764") from the quote. */
    val localCurrency: String? get() = quote?.localCurrency

    /** Quote id used to bind `/confirm` to this quote. */
    val quoteId: String? get() = quote?.quoteId

    /** Quote version used to bind `/confirm`; rejected if superseded. */
    val quoteVersion: Int? get() = quote?.quoteVersion

    /**
     * The dynamic-QR embedded local amount in MINOR units, as a display string.
     * Null for static QR (the user enters the amount). Used to seed the amount
     * screen when [amountFixed] is true.
     */
    val fixedLocalAmount: String? get() =
        if (amountFixed) quote?.localAmountMinorUnits?.toString() else null

    companion object {
        fun parse(json: JSONObject): ScanResult = ScanResult(
            intentId = json.optStringOrNull("intent_id"),
            status = IntentState.fromWire(json.optStringOrNull("status")),
            merchant = Merchant.parse(json.optJSONObject("merchant")),
            qrKind = json.optStringOrNull("qr_kind"),
            quote = Quote.parse(json.optJSONObject("quote")),
        )
    }
}

/**
 * Parsed response of `POST .../confirm` and `GET /v1/payment_intents/{id}`.
 *
 * Aligned to the canonical contract (docs/sdk-rest-contract.md /
 * `sdk-contract-fixtures/confirm.response.json`). The full intent record:
 * ```
 * {
 *   "id", "status", "amount_usd_cents", "corridor",
 *   "merchant": {...}, "qr_kind",
 *   "quote_id", "quote_version",
 *   "quote_local_amount_minor_units", "quote_local_currency", "quote_expires_at",
 *   "confirm_state", "beneficiary": {...}, "transaction_id"?,
 *   "created_at", "updated_at"
 * }
 * ```
 * The state machine reads [status] to decide terminal-ness. The SDK/JWT
 * projection of `GET /:id` is a subset (id/status/amount_usd_cents/corridor/
 * timestamps) — all extra fields are optional here so both shapes parse.
 */
internal data class IntentStatus(
    val intentId: String,
    val status: IntentState,
    val amountUsdCents: Long?,
    val corridor: String?,
    val merchant: Merchant?,
    val qrKind: String?,
    val quoteId: String?,
    val quoteVersion: Int?,
    val quoteLocalAmountMinorUnits: Long?,
    /** Numeric ISO-4217 currency code as a string, e.g. "704","764". */
    val quoteLocalCurrency: String?,
    val quoteExpiresAt: Long?,
    /** "in_flight" | "done" | null. */
    val confirmState: String?,
    val transactionId: String?,
    val createdAt: String?,
    val updatedAt: String?,
) {
    val merchantName: String? get() = merchant?.name

    /** USD debited, as a decimal display string (e.g. "10.00" from 1000 cents). */
    val usdDebited: String? get() = amountUsdCents?.let {
        String.format("%.2f", it / 100.0)
    }

    /** Local amount as a display string (minor units). Null when unknown. */
    val localAmount: String? get() = quoteLocalAmountMinorUnits?.toString()

    val localCurrency: String? get() = quoteLocalCurrency

    companion object {
        fun parse(fallbackIntentId: String, json: JSONObject): IntentStatus =
            IntentStatus(
                intentId = json.optStringOrNull("id") ?: fallbackIntentId,
                status = IntentState.fromWire(json.optStringOrNull("status")),
                amountUsdCents = json.optLongOrNull("amount_usd_cents"),
                corridor = json.optStringOrNull("corridor"),
                merchant = Merchant.parse(json.optJSONObject("merchant")),
                qrKind = json.optStringOrNull("qr_kind"),
                quoteId = json.optStringOrNull("quote_id"),
                quoteVersion = json.optIntOrNull("quote_version"),
                quoteLocalAmountMinorUnits =
                    json.optLongOrNull("quote_local_amount_minor_units"),
                quoteLocalCurrency = json.optStringOrNull("quote_local_currency"),
                quoteExpiresAt = json.optLongOrNull("quote_expires_at"),
                confirmState = json.optStringOrNull("confirm_state"),
                transactionId = json.optStringOrNull("transaction_id"),
                createdAt = json.optStringOrNull("created_at"),
                updatedAt = json.optStringOrNull("updated_at"),
            )
    }

    fun toCompleted(): PaymentResult.Completed = PaymentResult.Completed(
        intentId = intentId,
        merchantName = merchantName,
        localAmount = localAmount,
        localCurrency = localCurrency,
        usdDebited = usdDebited,
        transactionId = transactionId,
        // The canonical contract carries no verifiable-QR field on the intent
        // record; TH receipt verification is surfaced elsewhere. Null here.
        verifiableQrData = null,
    )

    fun toFailed(): PaymentResult.Failed = PaymentResult.Failed(
        intentId = intentId,
        error = ZennopayError.PaymentDeclined,
    )
}

/**
 * The intent lifecycle states the SDK cares about, per the shared state machine.
 * Non-terminal states keep the poller running; terminal states stop it.
 *
 * Canonical terminal statuses (docs/sdk-rest-contract.md): `captured` (success)
 * and `failed`. `created` is the non-terminal starting/scanned state.
 */
internal enum class IntentState(val terminal: Boolean) {
    CREATED(false),
    SCANNED(false),
    QUOTED(false),
    CONFIRMING(false),
    PROCESSING(false),
    PENDING(false),
    AUTHORIZED(false),
    CAPTURED(true),
    SUCCEEDED(true),
    FAILED(true),
    CANCELED(true),
    UNKNOWN(false);

    val isSuccess: Boolean get() = this == CAPTURED || this == SUCCEEDED

    companion object {
        fun fromWire(raw: String?): IntentState = when (raw?.lowercase()) {
            "created", "requires_scan" -> CREATED
            "scanned" -> SCANNED
            "quoted", "requires_confirmation" -> QUOTED
            "confirming" -> CONFIRMING
            "processing", "requires_action" -> PROCESSING
            "pending" -> PENDING
            "authorized" -> AUTHORIZED
            "captured", "succeeded", "success" -> CAPTURED
            "failed", "declined" -> FAILED
            "canceled", "cancelled", "voided" -> CANCELED
            else -> UNKNOWN
        }
    }
}

// ---- small JSON helpers ------------------------------------------------------

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } else null

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
