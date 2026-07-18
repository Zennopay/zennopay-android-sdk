package com.zennopay.sdk

import androidx.activity.ComponentActivity
import com.zennopay.sdk.internal.JwtClaims
import com.zennopay.sdk.ui.ZennopayAppearance
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Public entry point for the native Zennopay Android SDK (Stripe-PaymentSheet
 * model). The SDK renders the ENTIRE pay experience — QR scan → amount → confirm
 * → result — natively in-process, inside the host app. There is NO browser tab,
 * NO `androidx.browser`, and NO happy-path deep link.
 *
 * Usage:
 * ```
 * Zennopay.configure(ZennopayConfig(apiBaseUrl = "https://api.zennopay.in"))
 * Zennopay.presentCheckout(
 *     activity = this,
 *     intentId = "pi_abc123",
 *     sessionJwt = sessionJwtFromYourBackend,
 *     refreshSession = { intentId -> yourBackend.remintSessionJwt(intentId) },
 * ) { result ->
 *     when (result) {
 *         is PaymentResult.Completed -> renderSuccess(result)
 *         is PaymentResult.Pending   -> renderProcessing(result) // reconcile via webhook
 *         is PaymentResult.Failed    -> renderError(result.error)
 *         is PaymentResult.Canceled  -> dismiss()
 *     }
 * }
 * ```
 *
 * The host app must declare the `CAMERA` runtime permission usage; the SDK
 * requests it at runtime and degrades to a paste-QR fallback on denial.
 */
object Zennopay {

    @Volatile
    private var config: ZennopayConfig = ZennopayConfig()

    /** In-flight session handoffs, keyed by a one-shot launch token. */
    private val handoffs = ConcurrentHashMap<String, Handoff>()

    /** In-flight receipt handoffs, keyed by a one-shot launch token. */
    private val receiptHandoffs = ConcurrentHashMap<String, ReceiptHandoff>()

    /**
     * The single pending host callback + its intent id. Only one checkout can be
     * live at a time (the checkout activity covers the host).
     */
    @Volatile private var pendingListener: ((PaymentResult) -> Unit)? = null

    /** The single pending `presentReceipt` dismissal callback. */
    @Volatile private var pendingDismiss: (() -> Unit)? = null

    /** Optionally override the REST base + timeouts. Defaults to staging. */
    @JvmStatic
    fun configure(config: ZennopayConfig) {
        this.config = config
    }

    /**
     * Launch the native checkout flow. Delivers exactly one [PaymentResult] to
     * [onResult]. Fails fast (synchronously, no network) if the session JWT is
     * missing/malformed or not bound to [intentId].
     *
     * @param refreshSession host hook that re-mints a fresh session JWT for the
     *   SAME intent when the SDK sees a 401/expiry mid-flow (D3=A). Return null
     *   if the host cannot refresh; the SDK then surfaces an auth error.
     */
    @JvmStatic
    @JvmOverloads
    fun presentCheckout(
        activity: ComponentActivity,
        intentId: String,
        sessionJwt: String,
        refreshSession: (suspend (String) -> String?)? = null,
        appearance: ZennopayAppearance = ZennopayAppearance.Automatic,
        config: ZennopayConfig = this.config,
        onResult: (PaymentResult) -> Unit,
    ) {
        if (intentId.isBlank()) {
            onResult(PaymentResult.Failed(null, ZennopayError.MissingIntentId))
            return
        }
        if (sessionJwt.isBlank()) {
            onResult(PaymentResult.Failed(intentId, ZennopayError.InvalidJwt))
            return
        }

        // P1 security gate: bind the JWT to this intentId BEFORE launching. A
        // stale/replayed JWT minted for a different intent is caught here, at the
        // SDK boundary, before any REST call. (Backend rejects it too — defense
        // in depth.)
        val validation = JwtClaims.validate(
            jwt = sessionJwt,
            expectedIntentId = intentId,
            nowSeconds = System.currentTimeMillis() / 1000L,
        )
        mapValidationFailure(validation, intentId)?.let {
            onResult(it)
            return
        }

        pendingListener = onResult

        val token = UUID.randomUUID().toString()
        handoffs[token] = Handoff(
            intentId = intentId,
            sessionJwt = sessionJwt,
            refreshSession = refreshSession,
            appearance = appearance,
            config = config,
            // Corridor claim (e.g. "vn_vietqr") drives the scanner's corridor
            // branding row; optional, refined by a QR-payload hint at scan time.
            corridor = JwtClaims.decode(sessionJwt)?.corridor,
        )

        activity.startActivity(
            ZennopayCheckoutActivity.newIntent(activity, token, intentId),
        )
    }

    /**
     * Reopen the **authoritative Zennopay receipt** for a PAST payment, with live
     * pending/refund status. Presents modally over [activity] and renders the
     * same terminal screens as checkout: a captured/refunded payment shows the
     * receipt (refunded carries refund messaging); a failed payment shows the
     * failure screen; a still-processing payment shows the pending detail and
     * polls until it resolves. No session JWT, NO money movement.
     *
     * The host's backend mints a short-lived RS256 **receipt token**
     * (`aud = zennopay-receipt`, `sub = partner_user_id`, ≤15-min exp, reusable
     * for polling) and hands it to the app alongside the intent id. The SDK holds
     * it in memory and sends it as `Authorization: Bearer` on
     * `GET /v1/payment_intents/{id}/receipt`.
     *
     * @param refreshReceiptToken optional host hook invoked on a 401 (token
     *   expiry). Given the intent id, it re-mints a fresh receipt token (or
     *   returns null if it can't). When null, a 401 is surfaced as a failure.
     *   Mirrors [presentCheckout]'s `refreshSession`.
     * @param onDismiss invoked after the sheet is dismissed (the user tapped
     *   Done / close, or the token failed to load).
     */
    @JvmStatic
    @JvmOverloads
    fun presentReceipt(
        activity: ComponentActivity,
        intentId: String,
        receiptToken: String,
        refreshReceiptToken: (suspend (String) -> String?)? = null,
        config: ZennopayConfig = this.config,
        appearance: ZennopayAppearance = ZennopayAppearance.Automatic,
        onDismiss: () -> Unit,
    ) {
        // Light client-side check: seed a failure screen for a structurally
        // broken token (empty / not decodable). The backend is authoritative on
        // validity beyond that (expiry/audience are re-minted / re-checked).
        val preflightError: ZennopayError? = when (JwtClaims.lightDecodeReceiptToken(receiptToken)) {
            JwtClaims.ReceiptTokenResult.Valid -> null
            JwtClaims.ReceiptTokenResult.Empty -> ZennopayError.InvalidJwt
            JwtClaims.ReceiptTokenResult.Malformed -> ZennopayError.MalformedToken
        }

        pendingDismiss = onDismiss

        val token = UUID.randomUUID().toString()
        receiptHandoffs[token] = ReceiptHandoff(
            intentId = intentId,
            receiptToken = receiptToken,
            refreshReceiptToken = refreshReceiptToken,
            appearance = appearance,
            config = config,
            preflightError = preflightError,
        )

        activity.startActivity(
            ZennopayReceiptActivity.newIntent(activity, token, intentId),
        )
    }

    private fun mapValidationFailure(
        result: JwtClaims.ValidationResult,
        intentId: String,
    ): PaymentResult.Failed? = when (result) {
        JwtClaims.ValidationResult.Valid -> null
        JwtClaims.ValidationResult.Malformed ->
            PaymentResult.Failed(intentId, ZennopayError.MalformedToken)
        JwtClaims.ValidationResult.IntentMismatch ->
            PaymentResult.Failed(intentId, ZennopayError.IntentMismatch)
        JwtClaims.ValidationResult.Expired ->
            PaymentResult.Failed(intentId, ZennopayError.JwtExpired)
        JwtClaims.ValidationResult.InvalidIssuer ->
            PaymentResult.Failed(intentId, ZennopayError.InvalidIssuer)
    }

    // ---- internal handoff plumbing (used by ZennopayCheckoutActivity) --------

    internal fun consumeHandoff(token: String): Handoff? = handoffs.remove(token)

    internal fun consumeReceiptHandoff(token: String): ReceiptHandoff? =
        receiptHandoffs.remove(token)

    /** Deliver the terminal result to the host and clear the pending slot. */
    internal fun deliverExternally(result: PaymentResult) {
        val listener = pendingListener
        pendingListener = null
        listener?.invoke(result)
    }

    /** Fire the `presentReceipt` dismissal callback once and clear the slot. */
    internal fun deliverDismiss() {
        val listener = pendingDismiss
        pendingDismiss = null
        listener?.invoke()
    }

    /**
     * In-memory handoff of secrets + config from [presentCheckout] to the
     * checkout activity. Never serialized into an Intent, so the session JWT
     * can't leak via logcat / recents / exported intents.
     */
    internal data class Handoff(
        val intentId: String,
        val sessionJwt: String,
        val refreshSession: (suspend (String) -> String?)?,
        val appearance: ZennopayAppearance,
        val config: ZennopayConfig,
        /** `zennopay:corridor` claim from the session JWT, when present. */
        val corridor: String? = null,
    )

    /**
     * In-memory handoff of the receipt token + config from [presentReceipt] to
     * the receipt activity. Never serialized into an Intent, so the token can't
     * leak via logcat / recents / exported intents.
     */
    internal data class ReceiptHandoff(
        val intentId: String,
        val receiptToken: String,
        val refreshReceiptToken: (suspend (String) -> String?)?,
        val appearance: ZennopayAppearance,
        val config: ZennopayConfig,
        /** Set when the token failed the light preflight check (empty/malformed). */
        val preflightError: ZennopayError? = null,
    )
}
