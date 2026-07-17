package com.zennopay.sdk.internal

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure-JVM (modulo [android.util.Base64]) JWT claim inspector.
 *
 * Why this exists: the SDK is handed a JWT minted by the backend (RS256). We do
 * NOT verify the signature on-device — the checkout web service does that
 * server-side and is the source of truth. But before we launch a Chrome Custom
 * Tab, we MUST verify that the JWT's claims match what the host app asked us
 * to do. Otherwise a compromised/stale JWT (e.g. one minted for a different
 * intent) would be silently used for whatever intentId the host passes,
 * leaking intent IDs into URLs and breaking the "JWT is bound to one intent"
 * guarantee in our spec.
 *
 * This is fail-fast at the SDK boundary: the host app sees the failure
 * synchronously, before any network/browser side effect.
 *
 * No networking. No signature verification. Just claim inspection.
 */
internal object JwtClaims {

    /** 30s tolerance for client clock skew when checking `exp`. */
    private const val CLOCK_SKEW_SECONDS: Long = 30L

    /**
     * Decoded JWT payload claims the SDK cares about.
     */
    internal data class Payload(
        val intentId: String?,
        val exp: Long?,
        val iss: String?,
        /**
         * Optional `zennopay:corridor` claim (e.g. "vn_vietqr"). Drives the
         * scanner's corridor branding row; absence is fine (the UI hides it).
         */
        val corridor: String? = null,
    )

    /**
     * Result of [validate]. Either [Valid] or one of the typed failure modes
     * mapped to a public [com.zennopay.sdk.ZennopayError].
     */
    internal sealed class ValidationResult {
        object Valid : ValidationResult()
        object Malformed : ValidationResult()
        object IntentMismatch : ValidationResult()
        object Expired : ValidationResult()
        object InvalidIssuer : ValidationResult()
    }

    /**
     * Decode a JWT's payload segment. Returns null on any structural problem
     * (not 3 segments, bad base64, bad JSON). Caller treats null as malformed.
     *
     * Uses URL-safe base64 (no padding) per RFC 7519 / RFC 7515.
     */
    internal fun decode(jwt: String): Payload? {
        val segments = jwt.split('.')
        if (segments.size != 3) return null

        val payloadSegment = segments[1]
        if (payloadSegment.isEmpty()) return null

        val decodedBytes = try {
            Base64.decode(payloadSegment, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }

        val json = try {
            JSONObject(String(decodedBytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            return null
        }

        // `zennopay:intent_id` is the namespaced custom claim the backend mints.
        // `optString` returns "" when missing, so normalize to null for the
        // caller's "missing claim" check.
        val intentId = json.optString("zennopay:intent_id", "").ifEmpty { null }
        val iss = json.optString("iss", "").ifEmpty { null }
        // `exp` per RFC 7519 is a NumericDate (seconds since epoch). Treat 0
        // / missing as null so the caller fails the "exp in future" check.
        val exp = if (json.has("exp")) json.optLong("exp", -1L).takeIf { it >= 0 } else null
        val corridor = json.optString("zennopay:corridor", "").ifEmpty { null }

        return Payload(intentId = intentId, exp = exp, iss = iss, corridor = corridor)
    }

    /**
     * Outcome of [lightDecodeReceiptToken]. A receipt token is scoped to the
     * partner user (not one intent) and reusable for polling, so — unlike
     * [validate] — the check is deliberately light: it fails fast ONLY on a
     * structurally broken token, never on expiry or audience (a stale token is
     * re-minted via `refreshReceiptToken` on the backend's 401, and the backend
     * is the authority on `aud`).
     */
    internal sealed class ReceiptTokenResult {
        object Valid : ReceiptTokenResult()

        /** Empty / whitespace-only token. */
        object Empty : ReceiptTokenResult()

        /** Not 3 segments, bad base64, or unparseable JSON payload. */
        object Malformed : ReceiptTokenResult()
    }

    /**
     * Light client-side check for the receipt token: it must be non-empty and
     * structurally decode to a JSON claims object. Does NOT require an intent-id
     * binding and does NOT reject on expiry or audience. Mirrors the iOS SDK's
     * `lightDecodeReceiptToken`.
     */
    internal fun lightDecodeReceiptToken(jwt: String): ReceiptTokenResult {
        if (jwt.isBlank()) return ReceiptTokenResult.Empty
        val segments = jwt.split('.')
        if (segments.size != 3) return ReceiptTokenResult.Malformed
        val payloadSegment = segments[1]
        if (payloadSegment.isEmpty()) return ReceiptTokenResult.Malformed

        val decodedBytes = try {
            Base64.decode(payloadSegment, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return ReceiptTokenResult.Malformed
        }
        return try {
            JSONObject(String(decodedBytes, Charsets.UTF_8))
            ReceiptTokenResult.Valid
        } catch (e: JSONException) {
            ReceiptTokenResult.Malformed
        }
    }

    /**
     * Validate a JWT against the expected intentId at the SDK boundary.
     *
     * Required:
     *   - well-formed JWT (3 dot-separated base64url segments, parseable JSON)
     *   - `zennopay:intent_id` claim matches [expectedIntentId] exactly
     *   - `exp` is in the future (with [CLOCK_SKEW_SECONDS] tolerance)
     *   - `iss` is present and non-empty
     *
     * @param nowSeconds current time in seconds since epoch. Parameterized so
     *   tests can inject a fixed clock; production callers should pass
     *   `System.currentTimeMillis() / 1000`.
     */
    internal fun validate(
        jwt: String,
        expectedIntentId: String,
        nowSeconds: Long,
    ): ValidationResult {
        val payload = decode(jwt) ?: return ValidationResult.Malformed

        // Order matters: structural problems (missing claim) are reported as
        // Malformed; semantic mismatches get their specific error.
        val claimIntentId = payload.intentId ?: return ValidationResult.Malformed
        val exp = payload.exp ?: return ValidationResult.Malformed
        val iss = payload.iss ?: return ValidationResult.InvalidIssuer

        if (iss.isBlank()) return ValidationResult.InvalidIssuer
        if (claimIntentId != expectedIntentId) return ValidationResult.IntentMismatch
        // `exp` is "not valid at or after"; allow CLOCK_SKEW_SECONDS of grace.
        if (exp + CLOCK_SKEW_SECONDS <= nowSeconds) return ValidationResult.Expired

        return ValidationResult.Valid
    }
}
