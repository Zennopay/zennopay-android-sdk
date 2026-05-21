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

        return Payload(intentId = intentId, exp = exp, iss = iss)
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
