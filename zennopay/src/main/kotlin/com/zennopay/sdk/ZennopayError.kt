package com.zennopay.sdk

/**
 * Public error taxonomy surfaced to the host app inside [PaymentResult.Failed].
 *
 * These map 1:1 to the shared SDK error taxonomy in the eng-review-4 design
 * (docs/sdk-error-taxonomy.md / the state-machine section). Codes are stable
 * strings so the host app and both platforms (iOS/Android) agree — see
 * [ErrorTaxonomy] for the string<->type mapping used by the REST client.
 */
sealed class ZennopayError(val code: String) {

    // ---- Client-side / pre-flight (never hit the network) --------------------

    /** The supplied session JWT was empty or syntactically invalid. */
    object InvalidJwt : ZennopayError("client.invalid_jwt")

    /**
     * The JWT was structurally invalid: not 3 segments, undecodable base64,
     * unparseable JSON payload, or missing a required claim.
     */
    object MalformedToken : ZennopayError("client.malformed_token")

    /**
     * The JWT's `zennopay:intent_id` claim did not match the `intentId`
     * argument passed to [Zennopay.presentCheckout]. A JWT-replay-across-intents
     * attempt caught at the SDK boundary, before any REST call.
     */
    object IntentMismatch : ZennopayError("client.intent_mismatch")

    /** The JWT's `exp` claim is in the past (with 30s clock-skew tolerance). */
    object JwtExpired : ZennopayError("client.jwt_expired")

    /** The JWT's `iss` claim is missing or empty. */
    object InvalidIssuer : ZennopayError("client.invalid_issuer")

    /** `intentId` argument was blank. */
    object MissingIntentId : ZennopayError("client.missing_intent_id")

    // ---- Camera / scanner ----------------------------------------------------

    /**
     * The user denied the CAMERA permission. The SDK falls back to the manual
     * paste-QR entry path; this is only surfaced as a terminal error if the user
     * also abandons the fallback.
     */
    object CameraPermissionDenied : ZennopayError("scanner.camera_denied")

    /** The QR payload could not be decoded on-device (unreadable/empty). */
    object QrUndecodable : ZennopayError("scanner.qr_undecodable")

    // ---- Backend-surfaced (mapped from REST error bodies) --------------------
    //
    // Codes are the CANONICAL backend wire codes (docs/sdk-rest-contract.md +
    // backend/src/services/payment_intent_service.ts). The scan/confirm handlers
    // throw ApiError(code, internalReason); the internalReason is the dotted
    // string the SDK maps on (e.g. "confirm.quote_expired", "scan.validation_failed").

    /**
     * Backend rejected the raw QR payload during `/scan`: bad CRC, malformed
     * TLV, duplicate tag, personal recipient, corridor mismatch, missing/override
     * amount, etc. (all `qr.*` reasons under the `validation_failed` envelope).
     */
    object ScanValidationFailed : ZennopayError("scan.validation_failed")

    /** The FX quote bound to the confirm expired (`confirm.quote_expired`). */
    object QuoteExpired : ZennopayError("confirm.quote_expired")

    /** The presented quote id did not match the bound quote (`confirm.quote_mismatch`). */
    object QuoteMismatch : ZennopayError("confirm.quote_mismatch")

    /** A newer quote has superseded the presented one (`confirm.quote_superseded`). */
    object QuoteSuperseded : ZennopayError("confirm.quote_superseded")

    /** Quote metadata was presented on an unscanned intent (`confirm.not_scanned`). */
    object NotScanned : ZennopayError("confirm.not_scanned")

    /**
     * A dynamic QR carries an amount and the client tried to override it
     * (`confirm.dynamic_amount_override`). The embedded amount is immutable.
     */
    object DynamicAmountOverride : ZennopayError("confirm.dynamic_amount_override")

    /** The single-use confirm jti was already consumed (`jwt.jti_replay`). */
    object JtiReplay : ZennopayError("jwt.jti_replay")

    /** The URL `:id` did not match the JWT's `zennopay:intent_id` claim. */
    object IntentIdMismatch : ZennopayError("jwt.intent_id_mismatch_with_path")

    /** The intent already moved to a terminal state (`jwt.intent_invalid_state`). */
    object InvalidState : ZennopayError("jwt.intent_invalid_state")

    /** The session could not be refreshed (host `refreshSession` returned null). */
    object SessionRefreshFailed : ZennopayError("auth.refresh_failed")

    /** Auth failed and could not be recovered (401 with no/failed refresh). */
    object Unauthorized : ZennopayError("auth.unauthorized")

    /** The backend reported the payment intent itself as failed/declined. */
    object PaymentDeclined : ZennopayError("payment.declined")

    /** Status polling exceeded its bounded budget without reaching terminal. */
    object PollingTimeout : ZennopayError("status.polling_timeout")

    // ---- Transport / unknown -------------------------------------------------

    /** A network/IO failure talking to the REST API. */
    data class NetworkError(val cause: Throwable?) : ZennopayError("network.error")

    /** A backend error code the SDK doesn't have a typed mapping for. */
    data class Unknown(val serverCode: String?) : ZennopayError("unknown")
}
