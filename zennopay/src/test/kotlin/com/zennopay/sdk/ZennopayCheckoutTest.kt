package com.zennopay.sdk

import android.util.Base64
import androidx.activity.ComponentActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests the P1 security gate in [Zennopay.openCheckout]: the JWT must be
 * bound to the supplied intentId before we launch a Custom Tab. On any
 * failure (mismatch, expired, malformed) we MUST NOT touch the browser —
 * the host app sees a synchronous [PaymentResult.Failure].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZennopayCheckoutTest {

    private val now: Long = System.currentTimeMillis() / 1000L

    @After
    fun tearDown() {
        Zennopay.clearPending()
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun makeJwt(
        intentId: String = "zp_abc123",
        exp: Long = now + 600,
        iss: String = "wizz",
    ): String {
        val json = JSONObject()
            .put("zennopay:intent_id", intentId)
            .put("exp", exp)
            .put("iss", iss)
        val header = base64Url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = base64Url(json.toString().toByteArray())
        return "$header.$payload.sig-not-checked"
    }

    private fun buildActivity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Test
    fun `matching intent_id launches the Custom Tab and registers a pending listener`() {
        val activity = buildActivity()
        val received = mutableListOf<PaymentResult>()

        Zennopay.openCheckout(
            activity = activity,
            intentId = "zp_abc123",
            jwt = makeJwt(intentId = "zp_abc123"),
            returnScheme = "wizz",
        ) { received += it }

        // The security gate did not fire: no synchronous failure was delivered.
        assertTrue("expected no synchronous result, got $received", received.isEmpty())
        // The pending listener is registered so the eventual deep-link return
        // can dispatch to the host. This is the post-launch state.
        assertEquals("zp_abc123", Zennopay.pendingIntentId)
        assertNotNull(Zennopay.pendingListener)
        // An outbound Activity was started — the Custom Tab (or fallback
        // ACTION_VIEW under Robolectric, which lacks a Custom Tabs provider).
        // Either way the URL must target the checkout host.
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("openCheckout should have started an activity", started)
        val dataString = started!!.dataString ?: ""
        assertTrue(
            "started activity should target checkout.zennopay.com, got: $dataString",
            dataString.startsWith("https://checkout.zennopay.com/flow/zp_abc123/scan"),
        )
    }

    @Test
    fun `intent_id mismatch throws IntentMismatch and does NOT launch a tab`() {
        val activity = buildActivity()
        val received = mutableListOf<PaymentResult>()

        Zennopay.openCheckout(
            activity = activity,
            intentId = "zp_host_passed_this",
            jwt = makeJwt(intentId = "zp_attacker_minted_this"),
            returnScheme = "wizz",
        ) { received += it }

        assertEquals(1, received.size)
        val failure = received.single() as PaymentResult.Failure
        assertEquals(ZennopayError.IntentMismatch, failure.error)
        // Critical: no Activity was started, so no URL leaked to the browser.
        assertNull(shadowOf(activity).nextStartedActivity)
        // And no stale listener was registered.
        assertNull(Zennopay.pendingListener)
        assertNull(Zennopay.pendingIntentId)
    }

    @Test
    fun `expired JWT throws JwtExpired and does NOT launch a tab`() {
        val activity = buildActivity()
        val received = mutableListOf<PaymentResult>()

        Zennopay.openCheckout(
            activity = activity,
            intentId = "zp_abc123",
            jwt = makeJwt(intentId = "zp_abc123", exp = now - 3600),
            returnScheme = "wizz",
        ) { received += it }

        assertEquals(1, received.size)
        assertEquals(
            ZennopayError.JwtExpired,
            (received.single() as PaymentResult.Failure).error,
        )
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(Zennopay.pendingListener)
    }

    @Test
    fun `malformed JWT (not 3 segments) throws MalformedToken and does NOT launch a tab`() {
        val activity = buildActivity()
        val received = mutableListOf<PaymentResult>()

        Zennopay.openCheckout(
            activity = activity,
            intentId = "zp_abc123",
            jwt = "only.twoSegments",
            returnScheme = "wizz",
        ) { received += it }

        assertEquals(1, received.size)
        assertEquals(
            ZennopayError.MalformedToken,
            (received.single() as PaymentResult.Failure).error,
        )
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(Zennopay.pendingListener)
    }

    @Test
    fun `blank JWT short-circuits with InvalidJwt before claim inspection`() {
        val activity = buildActivity()
        val received = mutableListOf<PaymentResult>()

        Zennopay.openCheckout(
            activity = activity,
            intentId = "zp_abc123",
            jwt = "",
            returnScheme = "wizz",
        ) { received += it }

        assertEquals(
            ZennopayError.InvalidJwt,
            (received.single() as PaymentResult.Failure).error,
        )
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
