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

// Robolectric supplies a real org.json (the JVM stub returns null from
// JSONObject.toString when isReturnDefaultValues=true), which the DTO parsing
// and request body construction depend on.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZennopayRestClientTest {

    private val config = ZennopayConfig(apiBaseUrl = "https://api.test.zennopay.in")

    private fun client(
        transport: FakeTransport,
        jwt: String = "session.jwt.1",
        refresh: (suspend (String) -> String?)? = null,
    ) = ZennopayRestClient(config, transport, jwt, refresh)

    @Test
    fun `scan parses merchant and quote per canonical contract`() = runTest {
        val transport = FakeTransport().on(
            "POST", "/scan",
            FakeTransport.ok(
                """{"intent_id":"pi_1","status":"created",
                   "merchant":{"scheme":"promptpay","name":"Khao Mun Gai","city":"Bangkok","country":"TH","mcc":"5411"},
                   "qr_kind":"dynamic",
                   "quote":{"quote_id":"q_1","quote_version":1,"amount_usd_cents":345,
                            "local_amount_minor_units":12075,"local_currency":"764","expires_at":1782908263794}}""",
            ),
        )
        val r = client(transport).scan("pi_1", "00020101...")
        assertTrue(r is ApiResult.Ok)
        val scan = (r as ApiResult.Ok).value
        assertEquals("Khao Mun Gai", scan.merchantName)
        assertEquals("promptpay", scan.merchant?.scheme)
        assertEquals("764", scan.localCurrency)
        assertEquals("q_1", scan.quoteId)
        assertEquals(1, scan.quoteVersion)
        assertEquals(345L, scan.quote?.amountUsdCents)
        assertEquals(12075L, scan.quote?.localAmountMinorUnits)
        assertEquals(1782908263794L, scan.quote?.expiresAt)
        // qr_kind "dynamic" -> amount is fixed (read-only).
        assertEquals(true, scan.amountFixed)
    }

    @Test
    fun `scan sends the raw qr in the body and never in the url`() = runTest {
        val transport = FakeTransport().on("POST", "/scan", FakeTransport.ok("""{"intent_id":"pi_1","status":"created"}"""))
        client(transport).scan("pi_1", "RAWQR123")
        val call = transport.calls.single()
        assertTrue(call.body!!.contains("RAWQR123"))
        assertTrue(!call.url.contains("RAWQR123"))
        // JWT is a bearer header, not in the URL.
        assertEquals("session.jwt.1", call.bearer)
        assertTrue(!call.url.contains("session.jwt"))
    }

    @Test
    fun `confirm passes the idempotency key header and quote binding body`() = runTest {
        val transport = FakeTransport().on(
            "POST", "/confirm",
            FakeTransport.ok("""{"id":"pi_1","status":"captured"}"""),
        )
        val r = client(transport).confirm("pi_1", "idem_key_1", quoteId = "q_1", quoteVersion = 1)
        assertTrue(r is ApiResult.Ok)
        assertEquals(IntentState.CAPTURED, (r as ApiResult.Ok).value.status)
        val call = transport.calls.single()
        // Body is the canonical { quote_id, quote_version } — no amount field.
        assertTrue(call.body!!.contains("\"quote_id\":\"q_1\""))
        assertTrue(call.body!!.contains("\"quote_version\":1"))
        assertTrue(!call.body!!.contains("local_amount"))
    }

    @Test
    fun `401 triggers one refresh and retries with the fresh jwt`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/pi_1",
            FakeTransport.status(401, """{"error":{"code":"jwt.expired"}}"""),
            FakeTransport.ok("""{"id":"pi_1","status":"captured"}"""),
        )
        var refreshCalls = 0
        val c = client(transport, refresh = { refreshCalls++; "session.jwt.2" })
        val r = c.getStatus("pi_1")
        assertTrue(r is ApiResult.Ok)
        assertEquals(1, refreshCalls)
        // Second call used the refreshed token.
        assertEquals("session.jwt.2", transport.calls[1].bearer)
        assertEquals("session.jwt.2", c.currentJwt())
    }

    @Test
    fun `401 with no refresh hook surfaces SessionRefreshFailed`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/pi_1",
            FakeTransport.status(401, ""),
        )
        val r = client(transport, refresh = null).getStatus("pi_1")
        assertTrue(r is ApiResult.Err)
        assertEquals(ZennopayError.SessionRefreshFailed, (r as ApiResult.Err).error)
    }

    @Test
    fun `still-401 after refresh surfaces Unauthorized, no infinite loop`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/pi_1",
            FakeTransport.status(401, ""),
            FakeTransport.status(401, ""),
        )
        val r = client(transport, refresh = { "session.jwt.2" }).getStatus("pi_1")
        assertTrue(r is ApiResult.Err)
        assertEquals(ZennopayError.Unauthorized, (r as ApiResult.Err).error)
    }

    @Test
    fun `specific dotted confirm reason maps through the taxonomy`() = runTest {
        val transport = FakeTransport().on(
            "POST", "/confirm",
            FakeTransport.status(409, """{"error":{"code":"confirm.quote_expired","message":"…","request_id":"r"}}"""),
        )
        val r = client(transport).confirm("pi_1", "k", quoteId = "q", quoteVersion = 1)
        assertTrue(r is ApiResult.Err)
        assertEquals(ZennopayError.QuoteExpired, (r as ApiResult.Err).error)
    }

    @Test
    fun `generic conflict envelope on confirm maps to InvalidState`() = runTest {
        // The live wire carries only the generic envelope code; status disambiguates.
        val transport = FakeTransport().on(
            "POST", "/confirm",
            FakeTransport.status(409, """{"error":{"code":"conflict","message":"…","request_id":"r"}}"""),
        )
        val r = client(transport).confirm("pi_1", "k", quoteId = "q", quoteVersion = 1)
        assertTrue(r is ApiResult.Err)
        assertEquals(ZennopayError.InvalidState, (r as ApiResult.Err).error)
    }

    @Test
    fun `pollUntilTerminal stops at the first terminal state`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/pi_1",
            FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""),
            FakeTransport.ok("""{"id":"pi_1","status":"captured"}"""),
        )
        val r = client(transport).pollUntilTerminal("pi_1", initialDelayMillis = 1, maxDelayMillis = 1)
        assertTrue(r is ApiResult.Ok)
        assertEquals(IntentState.CAPTURED, (r as ApiResult.Ok).value.status)
    }

    @Test
    fun `pollUntilTerminal returns PollingTimeout when never terminal`() = runTest {
        val transport = FakeTransport().on(
            "GET", "/payment_intents/pi_1",
            FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""),
        )
        val r = client(transport).pollUntilTerminal("pi_1", maxAttempts = 3, initialDelayMillis = 1, maxDelayMillis = 1)
        assertTrue(r is ApiResult.Err)
        assertEquals(ZennopayError.PollingTimeout, (r as ApiResult.Err).error)
    }
}
