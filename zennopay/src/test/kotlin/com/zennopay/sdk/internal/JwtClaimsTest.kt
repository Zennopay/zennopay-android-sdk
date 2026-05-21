package com.zennopay.sdk.internal

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JwtClaimsTest {

    /** Fixed reference time = 1_700_000_000s (~2023-11-14). */
    private val now: Long = 1_700_000_000L

    /**
     * Build a fake JWT with the given payload claims. Signature segment is
     * deliberately garbage — we don't verify it on-device.
     */
    private fun makeJwt(
        intentId: String? = "zp_abc123",
        exp: Long? = now + 600,
        iss: String? = "wizz",
        omitIntent: Boolean = false,
        omitExp: Boolean = false,
        omitIss: Boolean = false,
    ): String {
        val json = JSONObject()
        if (!omitIntent && intentId != null) json.put("zennopay:intent_id", intentId)
        if (!omitExp && exp != null) json.put("exp", exp)
        if (!omitIss && iss != null) json.put("iss", iss)
        val header = base64Url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = base64Url(json.toString().toByteArray())
        return "$header.$payload.signature-not-checked"
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    @Test
    fun `valid JWT with matching intent, future exp, and issuer passes`() {
        val jwt = makeJwt()
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.Valid, result)
    }

    @Test
    fun `intent_id mismatch returns IntentMismatch`() {
        val jwt = makeJwt(intentId = "zp_attacker_minted_this")
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_host_passed_this", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.IntentMismatch, result)
    }

    @Test
    fun `expired JWT returns Expired`() {
        // exp is 1 hour in the past, well beyond the 30s skew window
        val jwt = makeJwt(exp = now - 3600)
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.Expired, result)
    }

    @Test
    fun `JWT expiring within 30s clock-skew window is still valid`() {
        // exp is 10s in the past — within the 30s skew tolerance
        val jwt = makeJwt(exp = now - 10)
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.Valid, result)
    }

    @Test
    fun `malformed JWT with only 2 segments returns Malformed`() {
        val result = JwtClaims.validate(
            jwt = "header.payload",
            expectedIntentId = "zp_abc123",
            nowSeconds = now,
        )
        assertEquals(JwtClaims.ValidationResult.Malformed, result)
    }

    @Test
    fun `malformed JWT with empty payload segment returns Malformed`() {
        val result = JwtClaims.validate(
            jwt = "header..signature",
            expectedIntentId = "zp_abc123",
            nowSeconds = now,
        )
        assertEquals(JwtClaims.ValidationResult.Malformed, result)
    }

    @Test
    fun `JWT with non-JSON payload returns Malformed`() {
        val header = base64Url("""{"alg":"RS256"}""".toByteArray())
        val badPayload = base64Url("not-json-at-all".toByteArray())
        val jwt = "$header.$badPayload.sig"
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.Malformed, result)
    }

    @Test
    fun `JWT missing zennopay intent_id claim returns Malformed`() {
        val jwt = makeJwt(omitIntent = true)
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.Malformed, result)
    }

    @Test
    fun `JWT missing exp claim returns Malformed`() {
        val jwt = makeJwt(omitExp = true)
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.Malformed, result)
    }

    @Test
    fun `JWT missing iss claim returns InvalidIssuer`() {
        val jwt = makeJwt(omitIss = true)
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        assertEquals(JwtClaims.ValidationResult.InvalidIssuer, result)
    }

    @Test
    fun `JWT with empty iss claim returns InvalidIssuer`() {
        val jwt = makeJwt(iss = "")
        val result = JwtClaims.validate(jwt, expectedIntentId = "zp_abc123", nowSeconds = now)
        // empty string is treated as missing by JSONObject.optString -> Malformed
        // path; both InvalidIssuer and Malformed are acceptable here. Pin the
        // observed behavior so a regression is visible.
        assertEquals(JwtClaims.ValidationResult.InvalidIssuer, result)
    }

    @Test
    fun `decode returns null for empty string`() {
        assertNull(JwtClaims.decode(""))
    }

    @Test
    fun `decode round-trips a well-formed payload`() {
        val jwt = makeJwt(intentId = "zp_zzz", exp = 4_102_444_800L, iss = "wizz")
        val payload = JwtClaims.decode(jwt)
        assertNotNull(payload)
        assertEquals("zp_zzz", payload!!.intentId)
        assertEquals(4_102_444_800L, payload.exp)
        assertEquals("wizz", payload.iss)
    }
}
