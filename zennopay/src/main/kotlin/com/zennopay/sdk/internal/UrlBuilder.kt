package com.zennopay.sdk.internal

import android.net.Uri
import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.PaymentStatus
import com.zennopay.sdk.ZennopayError

/**
 * Pure functions for constructing the checkout URL and parsing the return
 * deep-link. Kept out of the Activity layer so they can be unit-tested under
 * Robolectric (only [parseReturnUrl] needs Android's [Uri]).
 */
internal object UrlBuilder {

    private const val CHECKOUT_BASE = "https://checkout.zennopay.com"

    /**
     * Builds the checkout URL. The JWT is placed in the URL fragment (`#token=`)
     * rather than the query string so it isn't sent in the HTTP Referer header
     * or logged by intermediate servers.
     */
    fun buildCheckoutUrl(intentId: String, jwt: String): String {
        require(intentId.isNotBlank()) { "intentId must not be blank" }
        require(jwt.isNotBlank()) { "jwt must not be blank" }
        return "$CHECKOUT_BASE/flow/$intentId/scan#token=$jwt"
    }

    /**
     * Parses the return deep-link issued by the checkout web after the user
     * completes (or abandons) the flow.
     *
     * Expected form: `<scheme>://payment-result?intent_id=<id>&status=<status>`
     *
     * Returns [PaymentResult.Failure] with [ZennopayError.ReturnUrlMalformed]
     * if `intent_id` is missing. An unknown status string maps to
     * [PaymentStatus.FAILED] but is still wrapped in a Failure with
     * [ZennopayError.ReturnUrlMalformed] so the partner can distinguish a
     * legitimate failure from a parse error.
     */
    fun parseReturnUrl(uri: Uri): PaymentResult {
        val intentId = uri.getQueryParameter("intent_id")
        if (intentId.isNullOrBlank()) {
            return PaymentResult.Failure(ZennopayError.ReturnUrlMalformed)
        }

        val statusRaw = uri.getQueryParameter("status")?.lowercase()
        val status = when (statusRaw) {
            "success" -> PaymentStatus.SUCCESS
            "failed" -> PaymentStatus.FAILED
            "canceled", "cancelled" -> PaymentStatus.CANCELED
            "pending" -> PaymentStatus.PENDING
            else -> null
        }

        return if (status != null) {
            PaymentResult.Success(intentId = intentId, status = status)
        } else {
            PaymentResult.Failure(ZennopayError.ReturnUrlMalformed)
        }
    }
}
