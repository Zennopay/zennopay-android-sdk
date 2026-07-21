package com.zennopay.sdk

import android.content.Context
import android.content.Intent
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
 * Full-screen host for the read-only Compose **receipt** flow. Launched by
 * [Zennopay.presentReceipt]. Wires the REST client (authenticated by the receipt
 * token) and the platform-neutral [CheckoutController] in RECEIPT MODE, drives
 * [CheckoutController.runReceiptFlow], then renders [CheckoutRoot] — reusing the
 * exact terminal screens (receipt / failure / pending) as checkout.
 *
 * The receipt token is passed via a one-shot in-memory handoff
 * ([Zennopay.ReceiptHandoff]) keyed by a launch token — NOT via the Intent
 * extras — so it never lands in logcat, the recents thumbnail, or an
 * exported-intent surface. No money movement: there is no scan/confirm here.
 */
class ZennopayReceiptActivity : ComponentActivity() {

    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN)
        val handoff = token?.let { Zennopay.consumeReceiptHandoff(it) }
        if (handoff == null) {
            // No valid handoff (e.g. process death lost the in-memory token).
            // We can't reconstruct it, so finish cleanly and fire onDismiss.
            finishAndDismiss()
            return
        }

        val client = ZennopayRestClient(
            config = handoff.config,
            transport = HttpUrlConnectionTransport(),
            initialSessionJwt = handoff.receiptToken,
            refreshSession = handoff.refreshReceiptToken,
            appPackage = applicationContext.packageName,
        )

        val ctrl = CheckoutController(
            intentId = handoff.intentId,
            client = client,
            idempotencyStore = DataStoreIdempotencyStore(applicationContext),
            scope = lifecycleScope,
            // No camera in the receipt flow — it never reaches the scanner.
            cameraAvailable = false,
            receiptMode = true,
            onResult = { finishAndDismiss() },
        )
        ctrl.runReceiptFlow(handoff.preflightError)

        setContent {
            CheckoutRoot(
                controller = ctrl,
                appearance = handoff.appearance,
                sandbox = !handoff.config.isProduction,
            )
        }
    }

    private fun finishAndDismiss() {
        if (dismissed) return
        dismissed = true
        Zennopay.deliverDismiss()
        finish()
    }

    override fun onDestroy() {
        // If the sheet is torn down without an explicit Done (system back /
        // swiped away), the host's onDismiss must still fire exactly once.
        if (!dismissed && isFinishing) {
            dismissed = true
            Zennopay.deliverDismiss()
        }
        super.onDestroy()
    }

    internal companion object {
        const val EXTRA_LAUNCH_TOKEN = "com.zennopay.sdk.RECEIPT_LAUNCH_TOKEN"
        const val EXTRA_INTENT_ID = "com.zennopay.sdk.RECEIPT_INTENT_ID"

        fun newIntent(context: Context, launchToken: String, intentId: String): Intent =
            Intent(context, ZennopayReceiptActivity::class.java)
                .putExtra(EXTRA_LAUNCH_TOKEN, launchToken)
                .putExtra(EXTRA_INTENT_ID, intentId)
    }
}
