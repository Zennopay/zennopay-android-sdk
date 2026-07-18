package com.zennopay.sdk.internal.net

import com.zennopay.sdk.ZennopayConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live + decode proof for the `presentReceipt` fetch path.
 *
 *  - [liveStagingReceipt_returns200_andDecodesCaptured] (env-gated by
 *    `ZP_LIVE_RECEIPT=1` + `ZP_RECEIPT_BASE` + `ZP_INTENT_ID` +
 *    `ZP_RECEIPT_TOKEN`) hits the LIVE staging receipt endpoint with a
 *    partner-minted receipt token and asserts a 200 that decodes to a captured
 *    [ReceiptDto]. No base URL or token is committed — everything is injected.
 *  - [realCapturedReceipt_decodesThroughTheSdkFetchPath] decodes the REAL
 *    receipt captured from staging (a personal VietQR → null merchant name,
 *    alpha `VND` currency) through the SDK's fetch path with a stub transport,
 *    proving the tolerant parse handles the real wire shape. Always runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReceiptLiveE2ETest {

    /** The REAL receipt captured LIVE from staging for demo_user_6's captured intent. */
    private val liveCapturedJson = """
        {"intent_id":"0747aca3-1c1a-4ab7-b8d2-4e500c0033a3","status":"captured",
         "merchant":{"name":null,"account_no":"••••0000","bank_no":"970436","country":"VN"},
         "amount_usd_cents":14000,"local_amount_minor_units":350000000,"local_currency":"VND",
         "exchange_rate":25000,"fees":{"margin_usd_cents":210},"corridor":"vn_vietqr",
         "transaction_ref":"stubq_48825a27-8975-4aa5-8c2f-62d8813c2180",
         "created_at":"2026-07-17T22:49:26.196Z","updated_at":"2026-07-17T22:49:28.780Z"}
    """.trimIndent()

    @Test
    fun `liveStagingReceipt returns 200 and decodes captured`() = runBlocking {
        val env = System.getenv()
        assumeTrue("live receipt E2E not requested", env["ZP_LIVE_RECEIPT"] == "1")
        val base = requireNotNull(env["ZP_RECEIPT_BASE"])
        val intentId = requireNotNull(env["ZP_INTENT_ID"])
        val token = requireNotNull(env["ZP_RECEIPT_TOKEN"])

        val client = ZennopayRestClient(
            ZennopayConfig(apiBaseUrl = base),
            HttpUrlConnectionTransport(),
            token,
            refreshSession = null,
        )
        val r = client.getReceipt(intentId)
        assertTrue("expected 200 Ok, got $r", r is ApiResult.Ok)
        val dto = (r as ApiResult.Ok).value
        assertEquals(intentId, dto.intentId)
        assertEquals(ReceiptStatus.CAPTURED, dto.receiptStatus)
        assertTrue((dto.localAmountMinorUnits ?: 0) > 0)
    }

    @Test
    fun `realCapturedReceipt decodes through the sdk fetch path`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/0747aca3-1c1a-4ab7-b8d2-4e500c0033a3/receipt",
            FakeTransport.ok(liveCapturedJson),
        )
        val client = ZennopayRestClient(
            ZennopayConfig(apiBaseUrl = "https://api.test.zennopay.in"),
            transport,
            "rcpt",
            refreshSession = null,
        )
        val r = client.getReceipt("0747aca3-1c1a-4ab7-b8d2-4e500c0033a3")
        assertTrue(r is ApiResult.Ok)
        val dto = (r as ApiResult.Ok).value
        assertEquals(ReceiptStatus.CAPTURED, dto.receiptStatus)
        assertEquals(14000L, dto.amountUsdCents)
        assertEquals(350000000L, dto.localAmountMinorUnits)
        // Alpha currency on the live wire — tolerant parse keeps it verbatim.
        assertEquals("VND", dto.localCurrency)
        // Personal VietQR → no merchant name; the masked account still parses.
        assertEquals("••••0000", dto.merchant?.accountNo)
        assertEquals(null, dto.merchant?.name)
    }
}
