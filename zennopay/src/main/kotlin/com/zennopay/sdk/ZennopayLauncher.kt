package com.zennopay.sdk

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.zennopay.sdk.ui.ZennopayAppearance

/**
 * Compose entry point for hosts that are fully Compose. Returns a `present`
 * lambda `(intentId, sessionJwt) -> Unit`; invoking it launches the native
 * checkout from the current Activity (resolved from [LocalContext]) and delivers
 * the terminal [PaymentResult] to [onResult].
 *
 * ```
 * val present = rememberZennopayLauncher(
 *     appearance = myAppearance,
 *     refreshSession = { id -> yourBackend.remintSessionJwt(id) },
 * ) { result -> handle(result) }
 * // later:
 * Button(onClick = { present(intentId, sessionJwt) }) { Text("Pay") }
 * ```
 *
 * The lambda is stable across recompositions ([remember]-keyed on its inputs) so
 * it can be passed to click handlers without re-launching on every recompose.
 */
@Composable
fun rememberZennopayLauncher(
    config: ZennopayConfig = ZennopayConfig.SANDBOX,
    appearance: ZennopayAppearance = ZennopayAppearance.Automatic,
    refreshSession: (suspend (String) -> String?)? = null,
    onResult: (PaymentResult) -> Unit,
): (intentId: String, sessionJwt: String) -> Unit {
    val context = LocalContext.current
    return remember(context, config, appearance, refreshSession, onResult) {
        { intentId: String, sessionJwt: String ->
            val activity = context.findComponentActivity()
            if (activity == null) {
                // No Activity in the Context chain — an integration error, not a
                // payment failure. Surface it as a Failed so onResult still fires
                // exactly once.
                onResult(PaymentResult.Failed(intentId, ZennopayError.Unknown("no_host_activity")))
            } else {
                Zennopay.presentCheckout(
                    activity = activity,
                    intentId = intentId,
                    sessionJwt = sessionJwt,
                    refreshSession = refreshSession,
                    appearance = appearance,
                    config = config,
                    onResult = onResult,
                )
            }
        }
    }
}

/**
 * Compose entry point for the read-only receipt flow. Returns a `present` lambda
 * `(intentId, receiptToken) -> Unit`; invoking it reopens the authoritative
 * Zennopay receipt from the current Activity (resolved from [LocalContext]) and
 * fires [onDismiss] when the sheet closes. The Compose mirror of
 * [Zennopay.presentReceipt], consistent with [rememberZennopayLauncher].
 *
 * ```
 * val presentReceipt = rememberZennopayReceiptLauncher(
 *     refreshReceiptToken = { id -> yourBackend.remintReceiptToken(id) },
 * ) { /* onDismiss */ }
 * // later:
 * Button(onClick = { presentReceipt(intentId, receiptToken) }) { Text("View receipt") }
 * ```
 */
@Composable
fun rememberZennopayReceiptLauncher(
    config: ZennopayConfig = ZennopayConfig.SANDBOX,
    appearance: ZennopayAppearance = ZennopayAppearance.Automatic,
    refreshReceiptToken: (suspend (String) -> String?)? = null,
    onDismiss: () -> Unit = {},
): (intentId: String, receiptToken: String) -> Unit {
    val context = LocalContext.current
    return remember(context, config, appearance, refreshReceiptToken, onDismiss) {
        { intentId: String, receiptToken: String ->
            val activity = context.findComponentActivity()
            if (activity == null) {
                // No Activity in the Context chain — an integration error. There
                // is no result channel here, so just fire onDismiss.
                onDismiss()
            } else {
                Zennopay.presentReceipt(
                    activity = activity,
                    intentId = intentId,
                    receiptToken = receiptToken,
                    refreshReceiptToken = refreshReceiptToken,
                    config = config,
                    appearance = appearance,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/** Walk the ContextWrapper chain to find the hosting [ComponentActivity]. */
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
