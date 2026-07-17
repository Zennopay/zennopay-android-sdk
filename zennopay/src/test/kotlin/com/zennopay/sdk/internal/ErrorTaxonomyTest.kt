package com.zennopay.sdk.internal

import com.zennopay.sdk.ZennopayError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error-taxonomy mapping tests. These are the Android half of the shared
 * golden-fixture contract (T-STATE-MACHINE): the same canonical server codes
 * must map to the same typed errors on iOS and Android. Codes are the exact
 * dotted reasons from docs/sdk-rest-contract.md — aliases are intentionally NOT
 * mapped (unknown codes are preserved, never swallowed).
 */
class ErrorTaxonomyTest {

    @Test
    fun `scan validation maps to ScanValidationFailed`() {
        assertEquals(ZennopayError.ScanValidationFailed, ErrorTaxonomy.fromServerCode("scan.validation_failed"))
        assertEquals(ZennopayError.ScanValidationFailed, ErrorTaxonomy.fromServerCode("validation_failed"))
    }

    @Test
    fun `confirm quote-binding codes map to typed errors`() {
        assertEquals(ZennopayError.QuoteExpired, ErrorTaxonomy.fromServerCode("confirm.quote_expired"))
        assertEquals(ZennopayError.QuoteMismatch, ErrorTaxonomy.fromServerCode("confirm.quote_mismatch"))
        assertEquals(ZennopayError.QuoteSuperseded, ErrorTaxonomy.fromServerCode("confirm.quote_superseded"))
        assertEquals(ZennopayError.NotScanned, ErrorTaxonomy.fromServerCode("confirm.not_scanned"))
        assertEquals(ZennopayError.DynamicAmountOverride, ErrorTaxonomy.fromServerCode("confirm.dynamic_amount_override"))
    }

    @Test
    fun `jti replay maps to JtiReplay`() {
        assertEquals(ZennopayError.JtiReplay, ErrorTaxonomy.fromServerCode("jwt.jti_replay"))
    }

    @Test
    fun `jwt path + state codes map to typed errors`() {
        assertEquals(ZennopayError.IntentIdMismatch, ErrorTaxonomy.fromServerCode("jwt.intent_id_mismatch_with_path"))
        assertEquals(ZennopayError.InvalidState, ErrorTaxonomy.fromServerCode("jwt.intent_invalid_state"))
    }

    @Test
    fun `generic authentication_failed envelope maps to Unauthorized`() {
        assertEquals(ZennopayError.Unauthorized, ErrorTaxonomy.fromServerCode("authentication_failed"))
    }

    @Test
    fun `null and empty map to Unknown with null server code`() {
        val n = ErrorTaxonomy.fromServerCode(null)
        val e = ErrorTaxonomy.fromServerCode("")
        assertTrue(n is ZennopayError.Unknown && n.serverCode == null)
        assertTrue(e is ZennopayError.Unknown && e.serverCode == null)
    }

    @Test
    fun `unrecognized code is preserved in Unknown, not swallowed`() {
        val r = ErrorTaxonomy.fromServerCode("some.new.backend.code")
        assertTrue(r is ZennopayError.Unknown)
        assertEquals("some.new.backend.code", (r as ZennopayError.Unknown).serverCode)
    }

    @Test
    fun `envelope mapping prefers dotted reason, else disambiguates by status`() {
        // A specific dotted reason wins over the coarse envelope.
        assertEquals(ZennopayError.QuoteExpired, ErrorTaxonomy.fromEnvelope(409, "confirm.quote_expired"))
        // Bare conflict with no dotted reason surfaces as an invalid-state.
        assertEquals(ZennopayError.InvalidState, ErrorTaxonomy.fromEnvelope(409, "conflict"))
        // 401 auth envelope maps to Unauthorized (refreshable upstream).
        assertEquals(ZennopayError.Unauthorized, ErrorTaxonomy.fromEnvelope(401, "authentication_failed"))
    }

    @Test
    fun `every typed error exposes a stable code string`() {
        assertEquals("confirm.quote_expired", ZennopayError.QuoteExpired.code)
        assertEquals("confirm.dynamic_amount_override", ZennopayError.DynamicAmountOverride.code)
        assertEquals("scan.validation_failed", ZennopayError.ScanValidationFailed.code)
        assertEquals("client.intent_mismatch", ZennopayError.IntentMismatch.code)
        assertEquals("scanner.camera_denied", ZennopayError.CameraPermissionDenied.code)
    }
}
