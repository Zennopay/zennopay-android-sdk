package com.zennopay.sdk.internal

import com.zennopay.sdk.ZennopayError

/**
 * Maps backend error identifiers to the SDK's typed [ZennopayError].
 *
 * ## Wire shape (canonical)
 *
 * The backend error envelope (backend/src/util/errors.ts) is:
 * ```
 * { "error": { "code": <generic>, "message": "…", "request_id": "…" } }
 * ```
 * where `<generic>` is one of the coarse envelope codes: `authentication_failed`,
 * `authorization_failed`, `not_found`, `conflict`, `validation_failed`,
 * `rate_limited`, `payload_too_large`, `internal_error`. The SPECIFIC dotted
 * reason (`confirm.quote_expired`, `jwt.jti_replay`, …) is the server-side
 * `internalReason`, logged for triage and NOT guaranteed on the wire.
 *
 * So the SDK maps in two layers, most-specific first:
 *  1. [fromServerCode] — if a dotted reason IS present (fixtures, richer error
 *     surfaces, future contract revs), map it exactly to the canonical type.
 *  2. [fromEnvelope] — otherwise fall back to (HTTP status + generic code),
 *     which is what the live wire currently carries.
 *
 * Kept pure so the shared golden-fixture contract tests assert iOS/Android
 * parity on identifier -> type.
 */
internal object ErrorTaxonomy {

    /**
     * Map a specific dotted backend reason to a typed [ZennopayError]. These are
     * the canonical codes from docs/sdk-rest-contract.md and
     * backend/src/services/payment_intent_service.ts. Unknown/absent codes fall
     * through to [ZennopayError.Unknown] carrying the raw code (nothing swallowed).
     */
    fun fromServerCode(code: String?): ZennopayError = when (code) {
        // scan validation (all qr.* reasons ride the validation_failed envelope)
        "scan.validation_failed" -> ZennopayError.ScanValidationFailed

        // confirm / quote binding
        "confirm.quote_expired" -> ZennopayError.QuoteExpired
        "confirm.quote_mismatch" -> ZennopayError.QuoteMismatch
        "confirm.quote_superseded" -> ZennopayError.QuoteSuperseded
        "confirm.not_scanned" -> ZennopayError.NotScanned
        "confirm.dynamic_amount_override" -> ZennopayError.DynamicAmountOverride

        // auth / jti (all ride the authentication_failed envelope)
        "jwt.jti_replay" -> ZennopayError.JtiReplay
        "jwt.intent_id_mismatch_with_path" -> ZennopayError.IntentIdMismatch
        "jwt.intent_invalid_state" -> ZennopayError.InvalidState

        // generic envelope codes, when that's all we get on the wire
        "authentication_failed" -> ZennopayError.Unauthorized
        "validation_failed" -> ZennopayError.ScanValidationFailed
        "not_found" -> ZennopayError.Unknown(code)

        null, "" -> ZennopayError.Unknown(null)
        else -> ZennopayError.Unknown(code)
    }

    /**
     * Wire-accurate mapping: given the HTTP status AND the (possibly dotted,
     * possibly generic) error code, resolve the typed error. Prefers a specific
     * dotted mapping; otherwise disambiguates the generic envelope code by
     * status so that, e.g., a `conflict` on `/confirm` becomes [ZennopayError.
     * QuoteExpired]-class (mapped via the dotted reason when present) or a bare
     * `conflict` surfaces as [ZennopayError.InvalidState].
     */
    fun fromEnvelope(httpStatus: Int, code: String?): ZennopayError {
        // 1) If a specific dotted reason is present, that wins.
        val specific = fromServerCode(code)
        if (specific !is ZennopayError.Unknown &&
            !(code == "authentication_failed" && specific is ZennopayError.Unauthorized) &&
            !(code == "validation_failed" && specific is ZennopayError.ScanValidationFailed)
        ) {
            return specific
        }

        // 2) Otherwise map the coarse envelope code + status.
        return when (code) {
            "authentication_failed" -> ZennopayError.Unauthorized
            "validation_failed" -> ZennopayError.ScanValidationFailed
            "conflict" -> ZennopayError.InvalidState
            "not_found" -> ZennopayError.Unknown(code)
            "rate_limited", "rate_limit_exceeded" -> ZennopayError.Unknown(code)
            else -> when (httpStatus) {
                401 -> ZennopayError.Unauthorized
                400, 409 -> ZennopayError.InvalidState
                else -> fromServerCode(code)
            }
        }
    }
}
