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
    config: ZennopayConfig = ZennopayConfig.STAGING,
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

/** Walk the ContextWrapper chain to find the hosting [ComponentActivity]. */
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
