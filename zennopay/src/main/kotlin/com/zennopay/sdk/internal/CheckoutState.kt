package com.zennopay.sdk.internal

import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.ZennopayError
import com.zennopay.sdk.internal.net.ScanResult

/**
 * Platform-neutral checkout UI state, per the shared state machine (scan →
 * [static keypad →] quote → confirm → result, with expiry/cancel/pending/error
 * transitions) — the Kotlin mirror of the iOS SDK's `CheckoutState`. The
 * Compose layer renders one screen per state; the [CheckoutController] owns the
 * transitions. Keeping this a plain sealed hierarchy (no Android types) lets
 * the shared golden-fixture contract tests assert transitions on the JVM.
 */
internal sealed class CheckoutState {

    /**
     * The `presentReceipt` flow's initial state: a themed spinner while the
     * authoritative receipt is fetched (no scan/camera — this reopens a PAST
     * payment). Resolves to a [Terminal] screen once the receipt loads.
     */
    object ReceiptLoading : CheckoutState()

    /** Scanning for a QR code (camera live, or the paste-code sheet shown). */
    data class Scanning(
        val cameraAvailable: Boolean,
        /** Set when a prior submit failed and we returned to the scanner. */
        val transientError: ZennopayError? = null,
    ) : CheckoutState()

    /**
     * A STATIC QR (no embedded amount, peeked locally) was captured; the user
     * is entering the local-currency amount on the keypad before we `/scan`.
     */
    data class AmountEntry(val rawQr: String) : CheckoutState()

    /**
     * Raw QR captured; `/scan` in flight. [fromKeypad] drives which screen
     * hosts the loading state (keypad "Getting your rate…" vs the scanner's
     * "Checking…" pill).
     */
    data class SubmittingScan(val fromKeypad: Boolean = false) : CheckoutState()

    /**
     * `/scan` returned a merchant + quote; the review screen is shown (LOCAL
     * amount primary, USD chip secondary, slide-to-pay).
     */
    data class Quoted(
        val scan: ScanResult,
        /**
         * The raw QR that produced this quote, retained so the review screen
         * can silently re-quote on expiry without re-scanning.
         */
        val rawQr: String = "",
        val refreshingQuote: Boolean = false,
        /** Set when a re-quote failed and the stale quote is still displayed. */
        val transientError: ZennopayError? = null,
    ) : CheckoutState()

    /** Slide fired; `/confirm` in flight (idempotency key already persisted). */
    data class Confirming(val scan: ScanResult, val rawQr: String) : CheckoutState()

    /** Confirm accepted; polling `GET /:id` until terminal. */
    data class Processing(val intentId: String) : CheckoutState()

    /**
     * Terminal IN-SHEET: the receipt / failure / pending-detail screen stays up
     * until the user taps Done ([CheckoutEvent.CloseFromResult]) — the result
     * is NOT auto-delivered to the host (no auto-dismiss). The one exception is
     * [PaymentResult.Canceled], which has no result UI and is delivered
     * immediately by the controller.
     */
    data class Terminal(val result: PaymentResult) : CheckoutState()
}

/**
 * Events the UI raises into the controller. Named to mirror the state machine
 * doc so iOS/Android stay in lockstep.
 */
internal sealed class CheckoutEvent {
    /** A raw QR string was captured (live scanner, gallery decode, or paste). */
    data class QrCaptured(val rawQr: String) : CheckoutEvent()

    /** Keypad "Review" for a static QR: scan with the entered local amount. */
    data class StaticAmountEntered(val localAmountMinorUnits: Long) : CheckoutEvent()

    /** Back from the static-QR keypad to the scanner. */
    object BackToScanner : CheckoutEvent()

    /** The displayed quote's TTL elapsed; silently re-quote (no interruption). */
    object RequoteRequested : CheckoutEvent()

    object SlideConfirmed : CheckoutEvent()

    /** X before any terminal state (behaves like Done on result screens). */
    object Cancel : CheckoutEvent()

    /**
     * "Done" on the processing screen (or its 30s delay sheet): leave while the
     * payment is still processing → delivers [PaymentResult.Pending].
     */
    object LeaveWhileProcessing : CheckoutEvent()

    /** "Done" on a terminal receipt/failure/pending screen: deliver + close. */
    object CloseFromResult : CheckoutEvent()

    /** "Try again" on the failure screen (reuses the idempotency key). */
    object RetryFromFailure : CheckoutEvent()

    object CameraPermissionDenied : CheckoutEvent()
}
