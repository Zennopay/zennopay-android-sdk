package com.zennopay.sdk.internal

import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.ZennopayConfig
import com.zennopay.sdk.ZennopayError
import com.zennopay.sdk.internal.net.FakeTransport
import com.zennopay.sdk.internal.net.ReceiptStatus
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
 * Receipt-flow routing tests for [CheckoutController.runReceiptFlow] — the
 * Android mirror of the iOS `ReceiptFlowViewModelTests`: captured/refunded →
 * receipt (Completed), failed → failure, pending → poll to terminal, a preflight
 * error lands on failure WITHOUT touching the network, and a 401 with no refresh
 * lands on failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReceiptFlowControllerTest {

    private val config = ZennopayConfig(apiBaseUrl = "https://api.test.zennopay.in")

    private fun receiptController(
        scope: CoroutineScope,
        transport: FakeTransport,
        refresh: (suspend (String) -> String?)? = null,
    ): CheckoutController {
        val ioDispatcher =
            scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? CoroutineDispatcher
                ?: Dispatchers.IO
        val client = ZennopayRestClient(config, transport, "rcpt-1", refreshSession = refresh, ioDispatcher = ioDispatcher)
        return CheckoutController(
            intentId = "zp_1",
            client = client,
            idempotencyStore = InMemoryIdempotencyStore(),
            scope = scope,
            cameraAvailable = false,
            receiptMode = true,
            onResult = { /* the receipt flow delivers on Done */ },
        )
    }

    private fun receiptJson(status: String) =
        """{"intent_id":"zp_1","status":"$status","amount_usd_cents":14000,
           "merchant":{"name":"Ca Phe Sai Gon","account_no":"•••• 0000","bank_no":"970436","country":"VN"},
           "local_amount_minor_units":350000000,"local_currency":"704",
           "exchange_rate":25000.0,"corridor":"vn_vietqr","transaction_ref":"demo_txn_42"}"""

    private fun receiptOn(vararg responses: com.zennopay.sdk.internal.net.HttpResponse) =
        FakeTransport().on("GET", "/payment_intents/zp_1/receipt", *responses)

    @Test
    fun `starts in the receipt loading state`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = receiptController(scope, receiptOn(FakeTransport.ok(receiptJson("captured"))))
        assertEquals(CheckoutState.ReceiptLoading, c.state.value)
    }

    @Test
    fun `captured receipt routes to a Completed receipt and maps the fields`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = receiptController(scope, receiptOn(FakeTransport.ok(receiptJson("captured"))))
        c.runReceiptFlow()
        advanceUntilIdle()

        val s = c.state.value
        assertTrue("expected Terminal Completed, got $s", s is CheckoutState.Terminal && s.result is PaymentResult.Completed)
        assertEquals(ReceiptStatus.CAPTURED, c.receiptDisplayStatus)
        assertEquals(14000L, c.receipt?.usdCents)
        assertEquals(350000000L, c.receipt?.localMinorUnits)
        assertEquals("704", c.receipt?.localCurrency)
        assertEquals("demo_txn_42", c.receipt?.transactionId)
        assertEquals("•••• 0000", c.receipt?.accountMasked)
        assertEquals("Ca Phe Sai Gon", c.displayMerchantName)
    }

    @Test
    fun `refunded receipt routes to Completed with the refunded status`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = receiptController(scope, receiptOn(FakeTransport.ok(receiptJson("refunded"))))
        c.runReceiptFlow()
        advanceUntilIdle()
        val s = c.state.value
        assertTrue(s is CheckoutState.Terminal && s.result is PaymentResult.Completed)
        assertEquals(ReceiptStatus.REFUNDED, c.receiptDisplayStatus)
    }

    @Test
    fun `failed receipt routes to the failure screen`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = receiptController(scope, receiptOn(FakeTransport.ok(receiptJson("failed"))))
        c.runReceiptFlow()
        advanceUntilIdle()
        val s = c.state.value
        assertTrue("expected Terminal Failed, got $s", s is CheckoutState.Terminal && s.result is PaymentResult.Failed)
        assertEquals(ReceiptStatus.FAILED, c.receiptDisplayStatus)
    }

    @Test
    fun `pending receipt shows pending then polls to captured`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = receiptController(
            scope,
            receiptOn(FakeTransport.ok(receiptJson("pending")), FakeTransport.ok(receiptJson("captured"))),
        )
        c.runReceiptFlow()
        advanceUntilIdle()
        val s = c.state.value
        assertTrue("expected Terminal Completed, got $s", s is CheckoutState.Terminal && s.result is PaymentResult.Completed)
        assertEquals(ReceiptStatus.CAPTURED, c.receiptDisplayStatus)
    }

    @Test
    fun `a preflight error lands on failure without touching the network`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        // Empty transport: any network call would throw and fail the test.
        val c = receiptController(scope, FakeTransport())
        c.runReceiptFlow(preflightError = ZennopayError.MalformedToken)
        advanceUntilIdle()
        val s = c.state.value
        assertTrue(s is CheckoutState.Terminal)
        assertEquals(ZennopayError.MalformedToken, ((s as CheckoutState.Terminal).result as PaymentResult.Failed).error)
    }

    @Test
    fun `401 with no refresh lands on failure`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val c = receiptController(scope, receiptOn(FakeTransport.status(401, "")), refresh = null)
        c.runReceiptFlow()
        advanceUntilIdle()
        val s = c.state.value
        assertTrue(s is CheckoutState.Terminal && s.result is PaymentResult.Failed)
        assertEquals(ZennopayError.SessionRefreshFailed, ((s as CheckoutState.Terminal).result as PaymentResult.Failed).error)
    }
}
