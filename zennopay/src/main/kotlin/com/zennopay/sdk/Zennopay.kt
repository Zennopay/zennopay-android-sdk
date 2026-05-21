package com.zennopay.sdk

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import com.zennopay.sdk.internal.JwtClaims
import com.zennopay.sdk.internal.UrlBuilder

/**
 * The full result of a checkout session, surfaced to the partner via the
 * `onResult` callback supplied to [Zennopay.openCheckout].
 */
sealed class PaymentResult {
    data class Success(val intentId: String, val status: PaymentStatus) : PaymentResult()
    data class Failure(val error: ZennopayError) : PaymentResult()
}

enum class PaymentStatus {
    SUCCESS,
    FAILED,
    CANCELED,
    PENDING,
}

sealed class ZennopayError {
    /** The supplied JWT was empty or syntactically invalid. */
    object InvalidJwt : ZennopayError()

    /**
     * The JWT was structurally invalid: not 3 segments, undecodable base64,
     * unparseable JSON payload, or missing a required claim.
     */
    object MalformedToken : ZennopayError()

    /**
     * The JWT's `zennopay:intent_id` claim did not match the `intentId`
     * argument passed to [Zennopay.openCheckout]. Surfaces a JWT-replay-
     * across-intents attempt at the SDK boundary, before any URL is leaked
     * to a browser process.
     */
    object IntentMismatch : ZennopayError()

    /** The JWT's `exp` claim is in the past (with 30s clock-skew tolerance). */
    object JwtExpired : ZennopayError()

    /** The JWT's `iss` claim is missing or empty. */
    object InvalidIssuer : ZennopayError()

    /** The return deep-link could not be parsed (missing intent_id, etc.). */
    object ReturnUrlMalformed : ZennopayError()

    /** The user dismissed the Custom Tab without completing checkout. */
    object UserCanceled : ZennopayError()

    /** No browser on the device could handle the checkout URL. */
    object CustomTabsUnavailable : ZennopayError()

    data class NetworkError(val cause: Throwable) : ZennopayError()
}

/**
 * Public entry point for the Zennopay Android SDK.
 *
 * Usage:
 * ```
 * Zennopay.openCheckout(
 *     activity = this,
 *     intentId = "zp_abc123",
 *     jwt = "eyJhbGci...",
 *     returnScheme = "wizz",
 * ) { result ->
 *     when (result) {
 *         is PaymentResult.Success -> renderSuccess(result.status)
 *         is PaymentResult.Failure -> renderError(result.error)
 *     }
 * }
 * ```
 *
 * Architectural note: this is a Stripe-Checkout-style integration. We never
 * embed a WebView. The checkout page runs in a Chrome Custom Tab (a system
 * browser tab) so that:
 *   - Cookies are isolated from the partner app.
 *   - The user sees the real https URL in a system-rendered address bar.
 *   - The partner app cannot script the checkout page.
 *
 * On completion the page redirects to `<returnScheme>://payment-result?...`,
 * which fires [ZennopayCallbackActivity] via an intent-filter the partner has
 * registered in its own manifest. The callback activity then invokes the
 * pending listener stored here.
 */
object Zennopay {

    private const val TAG = "Zennopay"
    private const val CHROME_PACKAGE = "com.android.chrome"

    /**
     * Listener slot. The pattern: when the partner calls [openCheckout] we
     * stash the callback here, launch the Custom Tab, and let
     * [ZennopayCallbackActivity] read + invoke + clear when the redirect
     * fires. Single-slot is fine because only one checkout can be live at a
     * time (the Custom Tab covers the partner activity).
     */
    @Volatile
    internal var pendingListener: ((PaymentResult) -> Unit)? = null

    @Volatile
    internal var pendingIntentId: String? = null

    fun openCheckout(
        activity: ComponentActivity,
        intentId: String,
        jwt: String,
        returnScheme: String,
        onResult: (PaymentResult) -> Unit,
    ) {
        if (jwt.isBlank()) {
            onResult(PaymentResult.Failure(ZennopayError.InvalidJwt))
            return
        }
        if (intentId.isBlank()) {
            onResult(PaymentResult.Failure(ZennopayError.ReturnUrlMalformed))
            return
        }
        if (returnScheme.isBlank()) {
            onResult(PaymentResult.Failure(ZennopayError.ReturnUrlMalformed))
            return
        }

        // P1 security gate: bind the JWT to this intentId BEFORE we launch the
        // Custom Tab. Without this check a compromised/stale JWT minted for a
        // different intent would be used silently for whatever intentId the
        // host app passes, leaking intent IDs into URLs and breaking the
        // "JWT is bound to one intent" guarantee in our spec. The backend
        // rejects this server-side too — this is defense in depth at the SDK
        // boundary so the host app fails fast and synchronously.
        val claimsCheck = JwtClaims.validate(
            jwt = jwt,
            expectedIntentId = intentId,
            nowSeconds = System.currentTimeMillis() / 1000L,
        )
        when (claimsCheck) {
            JwtClaims.ValidationResult.Valid -> Unit
            JwtClaims.ValidationResult.Malformed -> {
                onResult(PaymentResult.Failure(ZennopayError.MalformedToken))
                return
            }
            JwtClaims.ValidationResult.IntentMismatch -> {
                onResult(PaymentResult.Failure(ZennopayError.IntentMismatch))
                return
            }
            JwtClaims.ValidationResult.Expired -> {
                onResult(PaymentResult.Failure(ZennopayError.JwtExpired))
                return
            }
            JwtClaims.ValidationResult.InvalidIssuer -> {
                onResult(PaymentResult.Failure(ZennopayError.InvalidIssuer))
                return
            }
        }

        pendingListener = onResult
        pendingIntentId = intentId

        val url = UrlBuilder.buildCheckoutUrl(intentId, jwt)
        val uri = Uri.parse(url)

        val customTabsPackage = CustomTabsClient.getPackageName(
            activity,
            listOf(CHROME_PACKAGE),
            /* ignoreDefault = */ false,
        )

        if (customTabsPackage != null) {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            customTabsIntent.intent.setPackage(customTabsPackage)
            try {
                customTabsIntent.launchUrl(activity, uri)
                return
            } catch (t: ActivityNotFoundException) {
                Log.w(TAG, "Custom Tab launch failed; falling back to ACTION_VIEW", t)
            }
        } else {
            Log.w(TAG, "No Custom Tabs provider found; falling back to ACTION_VIEW")
        }

        // Fallback: default browser via ACTION_VIEW.
        val viewIntent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(viewIntent)
        } catch (t: ActivityNotFoundException) {
            clearPending()
            onResult(PaymentResult.Failure(ZennopayError.CustomTabsUnavailable))
        }
    }

    /**
     * Called by [ZennopayCallbackActivity]. Looks up the pending listener,
     * invokes it once with the parsed result, then clears it.
     */
    internal fun dispatchResult(result: PaymentResult) {
        val listener = pendingListener
        clearPending()
        listener?.invoke(result)
    }

    internal fun clearPending() {
        pendingListener = null
        pendingIntentId = null
    }

    /**
     * Detect whether at least one Custom Tabs provider is installed. Useful
     * for partner apps that want to surface "Chrome required" UI ahead of
     * time. Not used internally by [openCheckout] (we fall back automatically).
     */
    fun isCustomTabsAvailable(activity: ComponentActivity): Boolean {
        return CustomTabsClient.getPackageName(
            activity,
            listOf(CHROME_PACKAGE),
            false,
        ) != null
    }

    @Suppress("unused")
    private const val CUSTOM_TABS_ACTION = CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION
}
