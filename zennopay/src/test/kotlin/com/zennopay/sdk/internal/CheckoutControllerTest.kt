package com.zennopay.sdk.internal

import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.ZennopayConfig
import com.zennopay.sdk.ZennopayError
import com.zennopay.sdk.internal.net.FakeTransport
import com.zennopay.sdk.internal.net.ZennopayRestClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * State-machine transition tests for [CheckoutController] under the
 * reference-design semantics (T-STATE-MACHINE, iOS parity):
 *   scan → [static keypad →] quote → confirm → IN-SHEET terminal, with
 * single-fire confirm, error mapping, PENDING (never hard-fail) on poll
 * timeout / leave-while-processing, and NO auto-delivery of terminal results —
 * the receipt/failure/pending screen waits for Done (CloseFromResult).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CheckoutControllerTest {

    private val config = ZennopayConfig(apiBaseUrl = "https://api.test.zennopay.in")

    /** EMVCo dynamic PromptPay-ish payload: tag 54 (amount) PRESENT. */
    private val dynamicQr = "00020153037645405120755802TH"

    /** EMVCo static payload: tag 54 ABSENT → keypad first. */
    private val staticQr = "00020153037645802TH"

    private fun controller(
        scope: CoroutineScope,
        transport: FakeTransport,
        store: IdempotencyStore = InMemoryIdempotencyStore(),
        corridor: String? = null,
        onResult: (PaymentResult) -> Unit,
    ): CheckoutController {
        val ioDispatcher =
            scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? CoroutineDispatcher
                ?: Dispatchers.IO
        val client = ZennopayRestClient(
            config, transport, "session.jwt.1", refreshSession = null, ioDispatcher = ioDispatcher,
        )
        return CheckoutController(
            intentId = "pi_1",
            client = client,
            idempotencyStore = store,
            scope = scope,
            cameraAvailable = true,
            initialCorridor = corridor,
            onResult = onResult,
        )
    }

    private val scanOk = FakeTransport.ok(
        """{"intent_id":"pi_1","status":"created",
           "merchant":{"scheme":"promptpay","name":"Khao Mun Gai","city":"Bangkok","country":"TH","mcc":"5411"},
           "qr_kind":"dynamic",
           "quote":{"quote_id":"q_1","quote_version":1,"amount_usd_cents":345,
                    "local_amount_minor_units":12075,"local_currency":"764","expires_at":1782908263794}}""",
    )

    private val confirmCaptured = FakeTransport.ok(
        """{"id":"pi_1","status":"captured","amount_usd_cents":345,"corridor":"th_promptpay",
           "merchant":{"scheme":"promptpay","name":"Khao Mun Gai","city":"Bangkok","country":"TH","mcc":"5411"},
           "qr_kind":"dynamic","quote_id":"q_1","quote_version":1,
           "quote_local_amount_minor_units":12075,"quote_local_currency":"764",
           "confirm_state":"done","transaction_id":"tx_9",
           "created_at":"2026-07-01T12:27:03.450Z","updated_at":"2026-07-01T12:27:03.453Z"}""",
    )

    @Test
    fun `dynamic QR routes straight to Quoted, then confirm reaches an in-sheet terminal`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", confirmCaptured)

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport) { results += it }

        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        assertTrue("dynamic scan -> quoted", c.state.value is CheckoutState.Quoted)

        c.dispatch(CheckoutEvent.SlideConfirmed)
        advanceUntilIdle()

        // Terminal IN-SHEET; the result is NOT delivered until Done.
        val terminal = c.state.value
        assertTrue(terminal is CheckoutState.Terminal)
        assertTrue((terminal as CheckoutState.Terminal).result is PaymentResult.Completed)
        assertTrue("no delivery before Done", results.isEmpty())

        c.dispatch(CheckoutEvent.CloseFromResult)
        advanceUntilIdle()
        assertEquals(1, results.size)
        val done = results.single() as PaymentResult.Completed
        assertEquals("Khao Mun Gai", done.merchantName)
        assertEquals("3.45", done.usdDebited)
        assertEquals("tx_9", done.transactionId)
    }

    @Test
    fun `static QR (no tag 54) routes through the amount keypad and scans with the entered amount`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport().on("POST", "/scan", scanOk)

        val c = controller(scope, transport) {}
        c.dispatch(CheckoutEvent.QrCaptured(staticQr))
        advanceUntilIdle()
        assertTrue("static scan -> keypad", c.state.value is CheckoutState.AmountEntry)
        // No network call yet — the amount comes first.
        assertTrue(transport.calls.isEmpty())

        c.dispatch(CheckoutEvent.StaticAmountEntered(12075))
        advanceUntilIdle()
        assertTrue(c.state.value is CheckoutState.Quoted)
        val scanCall = transport.calls.single { it.url.endsWith("/scan") }
        assertTrue(
            "scan carries local_amount_minor_units",
            scanCall.body!!.contains("\"local_amount_minor_units\":12075"),
        )
    }

    @Test
    fun `keypad back returns to the scanner`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = controller(scope, FakeTransport()) {}
        c.dispatch(CheckoutEvent.QrCaptured(staticQr))
        advanceUntilIdle()
        assertTrue(c.state.value is CheckoutState.AmountEntry)
        c.dispatch(CheckoutEvent.BackToScanner)
        advanceUntilIdle()
        assertTrue(c.state.value is CheckoutState.Scanning)
    }

    @Test
    fun `confirm is single-fire under a double slide`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", FakeTransport.ok("""{"id":"pi_1","status":"captured"}"""))

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport) { results += it }

        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        c.dispatch(CheckoutEvent.SlideConfirmed)
        c.dispatch(CheckoutEvent.SlideConfirmed)
        advanceUntilIdle()

        assertEquals(1, transport.calls.count { it.url.endsWith("/confirm") })
        c.dispatch(CheckoutEvent.CloseFromResult)
        assertEquals(1, results.size)
    }

    @Test
    fun `confirm persists the idempotency key and clears it on terminal success`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val store = InMemoryIdempotencyStore()
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", FakeTransport.ok("""{"id":"pi_1","status":"captured"}"""))

        val c = controller(scope, transport, store) {}
        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        c.dispatch(CheckoutEvent.SlideConfirmed)
        advanceUntilIdle()

        val confirmCall = transport.calls.first { it.url.endsWith("/confirm") }
        assertTrue(confirmCall.body != null)
        assertTrue("cleared on terminal success", store.peekKey("pi_1") == null)
    }

    @Test
    fun `confirm quote-expired maps to an in-sheet Failed with the mapped code`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", FakeTransport.status(409, """{"error":{"code":"confirm.quote_expired"}}"""))

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport) { results += it }
        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        c.dispatch(CheckoutEvent.SlideConfirmed)
        advanceUntilIdle()

        val terminal = c.state.value
        assertTrue(terminal is CheckoutState.Terminal)
        val f = (terminal as CheckoutState.Terminal).result
        assertTrue(f is PaymentResult.Failed)
        assertEquals(ZennopayError.QuoteExpired, (f as PaymentResult.Failed).error)

        c.dispatch(CheckoutEvent.CloseFromResult)
        assertEquals(1, results.size)
    }

    @Test
    fun `scan rejection returns to the scanner, not a terminal state`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
            .on("POST", "/scan", FakeTransport.status(400, """{"error":{"code":"validation_failed","message":"…","request_id":"r"}}"""))

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport) { results += it }
        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()

        val s = c.state.value
        assertTrue(s is CheckoutState.Scanning)
        assertEquals(ZennopayError.ScanValidationFailed, (s as CheckoutState.Scanning).transientError)
        assertTrue("no premature result", results.isEmpty())
    }

    @Test
    fun `cancel before confirm delivers Canceled immediately (no result UI)`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, FakeTransport()) { results += it }

        c.dispatch(CheckoutEvent.Cancel)
        advanceUntilIdle()
        assertEquals(1, results.size)
        assertTrue(results.single() is PaymentResult.Canceled)
    }

    @Test
    fun `camera denied keeps scanning with the paste and gallery paths`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, FakeTransport()) { results += it }

        c.dispatch(CheckoutEvent.CameraPermissionDenied)
        advanceUntilIdle()
        val s = c.state.value
        assertTrue(s is CheckoutState.Scanning)
        assertTrue(!(s as CheckoutState.Scanning).cameraAvailable)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `non-terminal confirm polls to a terminal captured status`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""))
            .on(
                "GET", "/payment_intents/pi_1",
                FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""),
                FakeTransport.ok("""{"id":"pi_1","status":"captured","transaction_id":"tx_9"}"""),
            )

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport) { results += it }
        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        c.dispatch(CheckoutEvent.SlideConfirmed)
        advanceUntilIdle()

        val terminal = c.state.value
        assertTrue(terminal is CheckoutState.Terminal)
        assertTrue((terminal as CheckoutState.Terminal).result is PaymentResult.Completed)
        c.dispatch(CheckoutEvent.CloseFromResult)
        assertEquals(1, results.size)
    }

    @Test
    fun `poll timeout after a confirmed debit is PENDING, not a failure`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""))
            // Never terminal: the poll budget lapses.
            .on("GET", "/payment_intents/pi_1", FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""))

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport) { results += it }
        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        c.dispatch(CheckoutEvent.SlideConfirmed)
        advanceUntilIdle()

        val terminal = c.state.value
        assertTrue(terminal is CheckoutState.Terminal)
        assertTrue(
            "poll timeout maps to Pending",
            (terminal as CheckoutState.Terminal).result is PaymentResult.Pending,
        )
        c.dispatch(CheckoutEvent.CloseFromResult)
        assertEquals(1, results.size)
        assertTrue(results.single() is PaymentResult.Pending)
    }

    @Test
    fun `leaving while processing delivers Pending exactly once`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""))
            .on("GET", "/payment_intents/pi_1", FakeTransport.ok("""{"id":"pi_1","status":"processing"}"""))

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport) { results += it }
        c.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        c.dispatch(CheckoutEvent.SlideConfirmed)
        // Leave immediately — do NOT drain the poll first.
        c.dispatch(CheckoutEvent.LeaveWhileProcessing)
        c.dispatch(CheckoutEvent.LeaveWhileProcessing)
        advanceUntilIdle()

        assertEquals(1, results.size)
        assertTrue(results.single() is PaymentResult.Pending)
    }

    @Test
    fun `relaunch with a persisted key recovers terminal status without re-scanning`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val store = InMemoryIdempotencyStore()
        store.getOrCreateKey("pi_1")
        val transport = FakeTransport()
            .on("GET", "/payment_intents/pi_1", FakeTransport.ok("""{"id":"pi_1","status":"captured"}"""))

        val results = mutableListOf<PaymentResult>()
        val c = controller(scope, transport, store) { results += it }
        c.recoverOrStart()
        advanceUntilIdle()

        assertTrue(transport.calls.none { it.url.endsWith("/scan") })
        val terminal = c.state.value
        assertTrue(terminal is CheckoutState.Terminal)
        assertTrue((terminal as CheckoutState.Terminal).result is PaymentResult.Completed)
        c.dispatch(CheckoutEvent.CloseFromResult)
        assertEquals(1, results.size)
    }

    @Test
    fun `exitResult maps teardown to terminal, pending, or canceled`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        // Fresh controller, nothing confirmed → Canceled.
        val c1 = controller(scope, FakeTransport()) {}
        assertTrue(c1.exitResult() is PaymentResult.Canceled)

        // Confirm in flight (wallet may be debited) → Pending.
        val transport = FakeTransport()
            .on("POST", "/scan", scanOk)
            .on("POST", "/confirm", FakeTransport.ok("""{"id":"pi_1","status":"captured"}"""))
        val c2 = controller(scope, transport) {}
        c2.dispatch(CheckoutEvent.QrCaptured(dynamicQr))
        advanceUntilIdle()
        c2.dispatch(CheckoutEvent.SlideConfirmed)
        advanceUntilIdle()
        // Terminal reached in-sheet → teardown returns the terminal result.
        assertTrue(c2.exitResult() is PaymentResult.Completed)
    }

    @Test
    fun `static QR corridor hint drives the merchant-name fallback`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = controller(scope, FakeTransport(), corridor = "vn_vietqr") {}
        assertEquals("Vietnam Merchant", c.displayMerchantName)
    }
}
