package com.zennopay.sdk.internal

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Light receipt-token validation ([JwtClaims.lightDecodeReceiptToken]) — the
 * Android mirror of the iOS `ReceiptTokenValidationTests`. A receipt token is
 * NOT intent-bound and MUST decode even without a `zennopay:intent_id` claim and
 * even when already expired (the backend re-mints on 401).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReceiptTokenValidationTest {

    private fun b64url(s: String): String =
        Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun makeJwt(payloadJson: String): String =
        "${b64url("""{"alg":"RS256","typ":"JWT"}""")}.${b64url(payloadJson)}.sig"

    @Test
    fun `empty token is Empty`() {
        assertEquals(JwtClaims.ReceiptTokenResult.Empty, JwtClaims.lightDecodeReceiptToken("   "))
    }

    @Test
    fun `wrong segment count is Malformed`() {
        assertEquals(JwtClaims.ReceiptTokenResult.Malformed, JwtClaims.lightDecodeReceiptToken("a.b"))
    }

    @Test
    fun `non-base64 payload is Malformed`() {
        assertEquals(JwtClaims.ReceiptTokenResult.Malformed, JwtClaims.lightDecodeReceiptToken("h.!!!.s"))
    }

    @Test
    fun `a well-formed receipt token is Valid`() {
        val jwt = makeJwt(
            """{"sub":"demo_user_6","aud":"zennopay-receipt","iss":"https://demo.partner.test/issuer","exp":9999999999}""",
        )
        assertEquals(JwtClaims.ReceiptTokenResult.Valid, JwtClaims.lightDecodeReceiptToken(jwt))
    }

    @Test
    fun `receipt token needs no intent claim and tolerates expiry`() {
        // No zennopay:intent_id, and an exp in the past — still Valid (the
        // backend re-mints on 401; validity beyond structure is its authority).
        val jwt = makeJwt("""{"sub":"demo_user_6","aud":"zennopay-receipt","exp":1}""")
        assertEquals(JwtClaims.ReceiptTokenResult.Valid, JwtClaims.lightDecodeReceiptToken(jwt))
    }
}
