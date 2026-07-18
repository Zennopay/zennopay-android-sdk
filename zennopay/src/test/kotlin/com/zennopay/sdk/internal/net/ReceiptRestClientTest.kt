package com.zennopay.sdk.internal.net

import com.zennopay.sdk.ZennopayConfig
import com.zennopay.sdk.ZennopayError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Receipt fetch + poll tests for [ZennopayRestClient] — the Android mirror of the
 * iOS `ReceiptRESTClientTests`: hits the receipt path, refreshes the receipt
 * token on 401 and retries, does not leak existence on 404, and polls to terminal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReceiptRestClientTest {

    private val config = ZennopayConfig(apiBaseUrl = "https://api.test.zennopay.in")

    private fun client(
        transport: FakeTransport,
        jwt: String = "rcpt-1",
        refresh: (suspend (String) -> String?)? = null,
    ) = ZennopayRestClient(config, transport, jwt, refresh)

    private fun receiptJson(status: String) =
        """{"intent_id":"zp_1","status":"$status","amount_usd_cents":14000,
           "merchant":{"name":"Ca Phe Sai Gon","account_no":"•••• 0000","bank_no":"970436","country":"VN"},
           "local_amount_minor_units":350000000,"local_currency":"704",
           "exchange_rate":25000.0,"fees":{"margin_usd_cents":210},
           "corridor":"vn_vietqr","transaction_ref":"demo_txn_42"}"""

    private fun errorJson(code: String) =
        """{"error":{"code":"$code","message":"x","request_id":"r"}}"""

    @Test
    fun `getReceipt hits the receipt path and decodes`() = runTest {
        val transport = FakeTransport().on("GET", "/payment_intents/zp_1/receipt", FakeTransport.ok(receiptJson("captured")))
        val r = client(transport).getReceipt("zp_1")
        assertTrue(r is ApiResult.Ok)
        val dto = (r as ApiResult.Ok).value
        assertEquals(ReceiptStatus.CAPTURED, dto.receiptStatus)
        assertEquals("Ca Phe Sai Gon", dto.merchant?.name)
        assertEquals(14000L, dto.amountUsdCents)
        // Token is a bearer header, never in the URL.
        val call = transport.calls.single()
        assertTrue(call.url.endsWith("/payment_intents/zp_1/receipt"))
        assertEquals("GET", call.method)
        assertEquals("rcpt-1", call.bearer)
    }

    @Test
    fun `401 refreshes the receipt token then retries`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/zp_1/receipt",
            FakeTransport.status(401, errorJson("authentication_failed")),
            FakeTransport.ok(receiptJson("captured")),
        )
        var refreshCalls = 0
        val r = client(transport, jwt = "stale", refresh = { refreshCalls++; "fresh" }).getReceipt("zp_1")
        assertTrue(r is ApiResult.Ok)
        assertEquals(1, refreshCalls)
        assertEquals("fresh", transport.calls[1].bearer)
    }

    @Test
    fun `401 with no refresh hook surfaces SessionRefreshFailed`() = runTest {
        val transport = FakeTransport().on("GET", "/payment_intents/zp_1/receipt", FakeTransport.status(401, ""))
        val r = client(transport, refresh = null).getReceipt("zp_1")
        assertTrue(r is ApiResult.Err)
        assertEquals(ZennopayError.SessionRefreshFailed, (r as ApiResult.Err).error)
    }

    @Test
    fun `404 does not leak existence`() = runTest {
        val transport = FakeTransport().on("GET", "/payment_intents/zp_unknown/receipt", FakeTransport.status(404, errorJson("not_found")))
        val r = client(transport).getReceipt("zp_unknown")
        assertTrue(r is ApiResult.Err)
        // Generic Unknown — no "this user vs unknown intent" distinction leaks.
        val err = (r as ApiResult.Err).error
        assertTrue(err is ZennopayError.Unknown)
    }

    @Test
    fun `pollReceiptUntilTerminal stops at the first terminal status`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/zp_1/receipt",
            FakeTransport.ok(receiptJson("pending")),
            FakeTransport.ok(receiptJson("captured")),
        )
        val r = client(transport).pollReceiptUntilTerminal("zp_1", initialDelayMillis = 1, maxDelayMillis = 1)
        assertTrue(r is ApiResult.Ok)
        assertEquals(ReceiptStatus.CAPTURED, (r as ApiResult.Ok).value.receiptStatus)
    }

    @Test
    fun `pollReceiptUntilTerminal times out when stuck pending`() = runTest {
        val transport = FakeTransport().on("GET", "/payment_intents/zp_1/receipt", FakeTransport.ok(receiptJson("pending")))
        val r = client(transport).pollReceiptUntilTerminal("zp_1", maxAttempts = 3, initialDelayMillis = 1, maxDelayMillis = 1)
        assertTrue(r is ApiResult.Err)
        assertEquals(ZennopayError.PollingTimeout, (r as ApiResult.Err).error)
    }
}
