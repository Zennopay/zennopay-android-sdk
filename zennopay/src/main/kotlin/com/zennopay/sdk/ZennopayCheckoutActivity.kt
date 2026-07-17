package com.zennopay.sdk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.zennopay.sdk.internal.CheckoutController
import com.zennopay.sdk.internal.DataStoreIdempotencyStore
import com.zennopay.sdk.internal.net.HttpUrlConnectionTransport
import com.zennopay.sdk.internal.net.ZennopayRestClient
import com.zennopay.sdk.ui.CheckoutRoot

/**
 * Full-screen host for the native Compose checkout flow. Launched by
 * [Zennopay.presentCheckout]. Wires the REST client, idempotency store, and the
 * platform-neutral [CheckoutController], then renders [CheckoutRoot].
 *
 * The session JWT is passed via a one-shot in-memory handoff ([Zennopay.Handoff])
 * keyed by a launch token — NOT via the Intent extras — so it never lands in
 * logcat, the recents thumbnail metadata, or an exported-intent surface.
 */
class ZennopayCheckoutActivity : ComponentActivity() {

    private var controller: CheckoutController? = null
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        val handoff = token?.let { Zennopay.consumeHandoff(it) }
        if (handoff == null) {
            // No valid handoff (e.g. process death lost the in-memory session).
            // We can't reconstruct the session JWT, so finish cleanly. The host's
            // callback, if still registered, receives a Failed(Unauthorized).
            Zennopay.deliverExternally(
                PaymentResult.Failed(
                    intent.getStringExtra(EXTRA_INTENT_ID),
                    ZennopayError.SessionRefreshFailed,
                ),
            )
            finish()
            return
        }

        val cameraAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        val client = ZennopayRestClient(
            config = handoff.config,
            transport = HttpUrlConnectionTransport(),
            initialSessionJwt = handoff.sessionJwt,
            refreshSession = handoff.refreshSession,
        )

        val ctrl = CheckoutController(
            intentId = handoff.intentId,
            client = client,
            idempotencyStore = DataStoreIdempotencyStore(applicationContext),
            scope = lifecycleScope,
            cameraAvailable = cameraAvailable,
            initialCorridor = handoff.corridor,
            onResult = { result -> finishWith(result) },
        )
        controller = ctrl
        ctrl.recoverOrStart()

        setContent {
            CheckoutRoot(
                controller = ctrl,
                appearance = handoff.appearance,
                sandbox = !handoff.config.isProduction,
            )
        }
    }

    private fun finishWith(result: PaymentResult) {
        if (delivered) return
        delivered = true
        Zennopay.deliverExternally(result)
        finish()
    }

    override fun onDestroy() {
        // If the activity is torn down without an explicit Done (system back /
        // swiped away), the host callback must still fire exactly once. The
        // controller decides WHAT to deliver: the reached terminal result, a
        // Pending when the wallet may have been debited (leave-while-processing),
        // or a Canceled when no money moved.
        if (!delivered && isFinishing) {
            delivered = true
            val fallback = controller?.exitResult()
                ?: PaymentResult.Canceled(intent.getStringExtra(EXTRA_INTENT_ID))
            Zennopay.deliverExternally(fallback)
        }
        super.onDestroy()
    }

    internal companion object {
        const val EXTRA_LAUNCH_TOKEN = "com.zennopay.sdk.LAUNCH_TOKEN"
        const val EXTRA_INTENT_ID = "com.zennopay.sdk.INTENT_ID"

        fun newIntent(context: Context, launchToken: String, intentId: String): Intent =
            Intent(context, ZennopayCheckoutActivity::class.java)
                .putExtra(EXTRA_LAUNCH_TOKEN, launchToken)
                .putExtra(EXTRA_INTENT_ID, intentId)
    }
}
