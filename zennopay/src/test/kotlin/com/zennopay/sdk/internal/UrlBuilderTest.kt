package com.zennopay.sdk.internal

import android.net.Uri
import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.PaymentStatus
import com.zennopay.sdk.ZennopayError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UrlBuilderTest {

    @Test
    fun `buildCheckoutUrl puts JWT in URL fragment, not query`() {
        val url = UrlBuilder.buildCheckoutUrl(
            intentId = "zp_abc123",
            jwt = "eyJhbGciOiJIUzI1NiJ9.payload.sig",
        )

        // Hash, not query: the JWT must not appear in the query string where
        // it could be logged by intermediate proxies or leaked via Referer.
        assertTrue(
            "URL should contain #token= fragment, got: $url",
            url.contains("#token=eyJhbGciOiJIUzI1NiJ9.payload.sig"),
        )
        assertTrue(
            "URL should not contain ?token= query, got: $url",
            !url.contains("?token=") && !url.contains("&token="),
        )
        assertEquals(
            "https://checkout.zennopay.com/flow/zp_abc123/scan#token=eyJhbGciOiJIUzI1NiJ9.payload.sig",
            url,
        )
    }

    @Test
    fun `parseReturnUrl extracts intent_id and status from valid deep-link`() {
        val uri = Uri.parse("wizz://payment-result?intent_id=zp_abc&status=success")

        val result = UrlBuilder.parseReturnUrl(uri)

        assertTrue("expected Success, got $result", result is PaymentResult.Success)
        val success = result as PaymentResult.Success
        assertEquals("zp_abc", success.intentId)
        assertEquals(PaymentStatus.SUCCESS, success.status)
    }

    @Test
    fun `parseReturnUrl returns ReturnUrlMalformed when intent_id is missing`() {
        val uri = Uri.parse("wizz://payment-result?status=success")

        val result = UrlBuilder.parseReturnUrl(uri)

        assertTrue("expected Failure, got $result", result is PaymentResult.Failure)
        val failure = result as PaymentResult.Failure
        assertEquals(ZennopayError.ReturnUrlMalformed, failure.error)
    }

    @Test
    fun `parseReturnUrl handles all PaymentStatus enum values`() {
        val cases = mapOf(
            "success" to PaymentStatus.SUCCESS,
            "failed" to PaymentStatus.FAILED,
            "canceled" to PaymentStatus.CANCELED,
            "cancelled" to PaymentStatus.CANCELED, // British spelling tolerated
            "pending" to PaymentStatus.PENDING,
            "SUCCESS" to PaymentStatus.SUCCESS,     // case-insensitive
        )

        for ((raw, expected) in cases) {
            val uri = Uri.parse("wizz://payment-result?intent_id=zp_x&status=$raw")
            val result = UrlBuilder.parseReturnUrl(uri)
            assertTrue("status=$raw expected Success, got $result", result is PaymentResult.Success)
            assertEquals("status=$raw", expected, (result as PaymentResult.Success).status)
        }
    }

    @Test
    fun `parseReturnUrl maps unknown status string to ReturnUrlMalformed failure`() {
        val uri = Uri.parse("wizz://payment-result?intent_id=zp_x&status=zaphod")

        val result = UrlBuilder.parseReturnUrl(uri)

        assertTrue("expected Failure for unknown status, got $result", result is PaymentResult.Failure)
        assertEquals(
            ZennopayError.ReturnUrlMalformed,
            (result as PaymentResult.Failure).error,
        )
    }
}
