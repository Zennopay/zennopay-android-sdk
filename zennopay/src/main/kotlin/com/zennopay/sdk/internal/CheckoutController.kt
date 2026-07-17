package com.zennopay.sdk.internal

import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.ZennopayError
import com.zennopay.sdk.internal.net.ApiResult
import com.zennopay.sdk.internal.net.IntentState
import com.zennopay.sdk.internal.net.IntentStatus
import com.zennopay.sdk.internal.net.Merchant
import com.zennopay.sdk.internal.net.ReceiptDto
import com.zennopay.sdk.internal.net.ReceiptStatus
import com.zennopay.sdk.internal.net.ScanResult
import com.zennopay.sdk.internal.net.ZennopayRestClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.zennopay.sdk.scanner.QrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives the native checkout flow. Platform-neutral (no Android UI types) so its
 * transitions are unit-testable on the JVM and shared by the golden-fixture
 * contract tests. The Compose layer observes [state] and forwards [dispatch].
 *
 * Guarantees (mirroring the iOS `CheckoutViewModel`):
 *  - `/scan` is submitted with the RAW QR string (backend is authoritative).
 *    A STATIC QR (no embedded amount, peeked locally) routes to the amount
 *    keypad first; `/scan` then carries `local_amount_minor_units`.
 *  - `/confirm` fires exactly once per user-slide: a [Mutex] + a latched flag
 *    make the slide single-fire even under rapid touch.
 *  - The idempotency key is persisted BEFORE confirm; retries reuse it (D5=A).
 *  - Relaunch recovery: [recoverOrStart] re-GETs the intent if a key exists.
 *  - Terminal results are NOT auto-delivered: the receipt/failure/pending
 *    screen stays up until the user taps Done ([CheckoutEvent.CloseFromResult]).
 *    Leaving while processing — or a lapsed poll budget after the wallet may
 *    have been debited — is a [PaymentResult.Pending], never a hard failure
 *    (the backend auto-refunds an unsettled debit).
 *  - The final result is delivered to the host exactly once via [onResult].
 */
internal class CheckoutController(
    private val intentId: String,
    private val client: ZennopayRestClient,
    private val idempotencyStore: IdempotencyStore,
    private val scope: CoroutineScope,
    cameraAvailable: Boolean,
    /**
     * Corridor from the session JWT's `zennopay:corridor` claim (e.g.
     * "vn_vietqr"), refined by a QR-payload hint once one is scanned. Drives
     * the scanner branding row and merchant-card captions.
     */
    initialCorridor: String? = null,
    /**
     * When true this controller drives the READ-ONLY `presentReceipt` flow (no
     * scan/confirm/money movement): it starts in [CheckoutState.ReceiptLoading]
     * and is advanced by [runReceiptFlow] instead of [recoverOrStart]. The same
     * terminal screens (receipt / failure / pending) are reused verbatim.
     */
    private val receiptMode: Boolean = false,
    private val onResult: (PaymentResult) -> Unit,
) {
    private val _state = MutableStateFlow<CheckoutState>(
        if (receiptMode) {
            CheckoutState.ReceiptLoading
        } else {
            CheckoutState.Scanning(cameraAvailable = cameraAvailable)
        },
    )
    val state: StateFlow<CheckoutState> = _state.asStateFlow()

    private val _corridor = MutableStateFlow(initialCorridor)
    val corridor: StateFlow<String?> = _corridor.asStateFlow()

    /**
     * User-entered "Purpose of payment (optional)". Client-side only: echoed on
     * the receipt; NOT transmitted to the backend (no field exists yet).
     */
    val purposeText = MutableStateFlow("")

    /** Display-only beneficiary facts peeked from the raw QR (bank + account). */
    @Volatile var qrPeek: QrPayload.Peek? = null
        private set

    /**
     * True once `/confirm` was accepted (or replayed) — the wallet debit may
     * have happened, so failure/pending copy must carry the refund reassurance.
     */
    @Volatile var walletDebited: Boolean = false
        private set

    /** When the confirm was accepted (drives the receipt timestamp fallback). */
    @Volatile private var confirmedAtMillis: Long? = null

    private val confirmMutex = Mutex()
    @Volatile private var confirmFired = false
    @Volatile private var resultDelivered = false

    /** Last raw QR submitted to `/scan`, retained for silent re-quote. */
    @Volatile private var lastRawQr: String = ""

    /** Keypad amount for a static QR, re-sent on a silent re-quote. */
    @Volatile private var lastLocalAmountMinor: Long? = null

    /** The last validated scan (merchant + quote), for retry + the receipt. */
    @Volatile private var lastScan: ScanResult? = null

    /** The richest terminal snapshot (confirm response or terminal poll). */
    @Volatile private var receiptStatus: IntentStatus? = null

    /**
     * Account number (already masked by the backend) injected by the receipt
     * flow — the read-only `presentReceipt` path has no local QR peek, so the
     * masked account comes from the receipt DTO's `merchant.account_no`.
     */
    @Volatile private var injectedAccountMasked: String? = null

    /**
     * The receipt's collapsed status (captured/failed/refunded/pending), set by
     * the receipt flow. Drives the refund copy on the receipt screen; null in
     * the checkout flow.
     */
    @Volatile var receiptDisplayStatus: ReceiptStatus? = null
        private set

    /**
     * On (re)launch: if a confirm was already in flight (idempotency key on
     * disk), recover the true terminal status instead of re-scanning. Otherwise
     * start fresh at the scanner. Call once from the host at startup.
     */
    fun recoverOrStart() {
        scope.launch {
            val existingKey = idempotencyStore.peekKey(intentId)
            if (existingKey != null) {
                // A confirm was persisted before this process (possibly died).
                // The money call may have gone through — recover the status.
                walletDebited = true
                confirmFired = true
                _state.value = CheckoutState.Processing(intentId)
                pollToTerminal()
            }
            // else: leave the initial Scanning state as-is.
        }
    }

    // ---- Receipt flow (presentReceipt) ---------------------------------------

    /**
     * Drive the READ-ONLY `presentReceipt` flow: fetch the authoritative receipt
     * for a past payment and render the terminal receipt/failure/refund screen.
     * A `pending` receipt shows the pending-detail screen and polls until it
     * goes terminal, then swaps to the resolved screen. A structurally invalid
     * token ([preflightError]) or any load failure lands on the failure screen
     * with a Done that dismisses. Reuses the existing terminal screens verbatim.
     * The Kotlin mirror of the iOS `CheckoutViewModel.runReceiptFlow`.
     */
    fun runReceiptFlow(preflightError: ZennopayError? = null) {
        scope.launch {
            // Already terminal (e.g. seeded for a static render) — nothing to do.
            if (_state.value is CheckoutState.Terminal) return@launch
            if (preflightError != null) {
                finishTerminal(PaymentResult.Failed(intentId, preflightError))
                return@launch
            }
            when (val r = client.getReceipt(intentId)) {
                is ApiResult.Ok -> {
                    applyReceipt(r.value)
                    val status = r.value.receiptStatus
                    when {
                        status == null -> finishTerminal(
                            PaymentResult.Failed(
                                intentId, ZennopayError.Unknown("unknown_receipt_status"),
                            ),
                        )
                        status.isTerminal -> finishReceipt(status)
                        else -> {
                            // Pending: show the pending-detail screen while we poll.
                            _state.value = CheckoutState.Terminal(PaymentResult.Pending(intentId))
                            pollReceipt()
                        }
                    }
                }
                is ApiResult.Err -> finishTerminal(PaymentResult.Failed(intentId, r.error))
            }
        }
    }

    private suspend fun pollReceipt() {
        when (val r = client.pollReceiptUntilTerminal(intentId)) {
            is ApiResult.Ok -> {
                applyReceipt(r.value)
                finishReceipt(r.value.receiptStatus ?: ReceiptStatus.FAILED)
            }
            is ApiResult.Err -> {
                // A lapsed poll budget is still PENDING — keep the pending screen
                // up (it may yet settle; the backend auto-refunds otherwise).
                if (r.error is ZennopayError.PollingTimeout) {
                    _state.value = CheckoutState.Terminal(PaymentResult.Pending(intentId))
                } else {
                    finishTerminal(PaymentResult.Failed(intentId, r.error))
                }
            }
        }
    }

    /**
     * Collapse a terminal receipt status onto the terminal screens. `refunded`
     * renders the receipt with refund messaging (the debit did happen, then was
     * returned) rather than a failure.
     */
    private fun finishReceipt(status: ReceiptStatus) {
        receiptDisplayStatus = status
        when (status) {
            ReceiptStatus.CAPTURED, ReceiptStatus.REFUNDED ->
                finishTerminal(receiptCompleted())
            ReceiptStatus.FAILED ->
                finishTerminal(PaymentResult.Failed(intentId, ZennopayError.PaymentDeclined))
            ReceiptStatus.PENDING ->
                _state.value = CheckoutState.Terminal(PaymentResult.Pending(intentId))
        }
    }

    /** The host-facing Completed payload for a captured/refunded receipt. */
    private fun receiptCompleted(): PaymentResult.Completed {
        val r = receipt
        return PaymentResult.Completed(
            intentId = intentId,
            merchantName = r?.merchantName,
            localAmount = r?.localMinorUnits?.toString(),
            localCurrency = r?.localCurrency,
            usdDebited = r?.usdCents?.let { CurrencyDisplay.groupedNumber(it / 100.0, 2) },
            transactionId = r?.transactionId,
            verifiableQrData = null,
        )
    }

    /**
     * Map the receipt DTO onto the display model the terminal screens read
     * (the [receiptStatus] snapshot + injected masked account + corridor +
     * timestamp). The Kotlin mirror of the iOS `applyReceipt`.
     */
    private fun applyReceipt(dto: ReceiptDto) {
        val numeric = CurrencyDisplay.numericCode(dto.localCurrency)
        receiptStatus = IntentStatus(
            intentId = dto.intentId ?: intentId,
            status = when (dto.receiptStatus) {
                ReceiptStatus.FAILED -> IntentState.FAILED
                ReceiptStatus.PENDING, null -> IntentState.PENDING
                else -> IntentState.CAPTURED
            },
            amountUsdCents = dto.amountUsdCents,
            corridor = dto.corridor,
            merchant = Merchant(
                scheme = null,
                name = dto.merchant?.name,
                city = null,
                country = dto.merchant?.country,
                mcc = null,
            ),
            qrKind = null,
            quoteId = null,
            quoteVersion = null,
            quoteLocalAmountMinorUnits = dto.localAmountMinorUnits,
            quoteLocalCurrency = numeric,
            quoteExpiresAt = null,
            confirmState = null,
            transactionId = dto.transactionRef,
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt,
        )
        injectedAccountMasked = dto.merchant?.accountNo
        dto.corridor?.takeIf { it.isNotEmpty() }?.let { _corridor.value = it }
        confirmedAtMillis = parseIso8601(dto.updatedAt)
            ?: parseIso8601(dto.createdAt)
            ?: System.currentTimeMillis()
        // A failed receipt was not debited (or already refunded); everything
        // else means money moved — drives the refund reassurance copy.
        walletDebited = dto.receiptStatus != ReceiptStatus.FAILED
        receiptDisplayStatus = dto.receiptStatus
    }

    /** Parse an ISO-8601 timestamp to epoch millis, or null if unparseable. */
    private fun parseIso8601(value: String?): Long? {
        if (value.isNullOrEmpty()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(value)?.time ?: continue
            } catch (e: Exception) {
                // try the next pattern
            }
        }
        return null
    }

    fun dispatch(event: CheckoutEvent) {
        when (event) {
            is CheckoutEvent.QrCaptured -> submitScan(event.rawQr)
            is CheckoutEvent.StaticAmountEntered ->
                submitStaticAmount(event.localAmountMinorUnits)
            CheckoutEvent.BackToScanner -> backToScanner()
            CheckoutEvent.CameraPermissionDenied -> onCameraDenied()
            CheckoutEvent.RequoteRequested -> refreshQuote()
            CheckoutEvent.SlideConfirmed -> confirm()
            CheckoutEvent.Cancel -> cancel()
            CheckoutEvent.LeaveWhileProcessing -> leaveWhileProcessing()
            CheckoutEvent.CloseFromResult -> closeFromResult()
            CheckoutEvent.RetryFromFailure -> retry()
        }
    }

    // ---- Scan ----------------------------------------------------------------

    private fun onCameraDenied() {
        // Graceful degradation: the scanner chrome steers to paste/gallery.
        val s = _state.value
        if (s is CheckoutState.Scanning) {
            _state.value = s.copy(cameraAvailable = false)
        }
    }

    /**
     * Handle a raw QR string (live scanner, gallery decode, or the paste
     * sheet). A STATIC QR (no embedded amount, peeked locally) routes to the
     * amount keypad first; the backend re-parses authoritatively on `/scan`.
     */
    private fun submitScan(rawQr: String) {
        val s = _state.value
        if (s !is CheckoutState.Scanning) return
        val trimmed = rawQr.trim()
        if (trimmed.isEmpty()) {
            _state.value = s.copy(transientError = ZennopayError.ScanValidationFailed)
            return
        }
        lastRawQr = trimmed
        lastLocalAmountMinor = null
        val peek = QrPayload.peek(trimmed)
        qrPeek = peek
        QrPayload.corridorHint(trimmed)?.let { _corridor.value = it }
        if (peek.isStatic) {
            _state.value = CheckoutState.AmountEntry(rawQr = trimmed)
            return
        }
        _state.value = CheckoutState.SubmittingScan(fromKeypad = false)
        scope.launch { runScan(trimmed, localAmountMinorUnits = null, fromKeypad = false) }
    }

    /** Keypad "Review": scan the static QR with the entered local amount. */
    private fun submitStaticAmount(minorUnits: Long) {
        val s = _state.value
        if (s !is CheckoutState.AmountEntry || minorUnits <= 0L) return
        lastLocalAmountMinor = minorUnits
        _state.value = CheckoutState.SubmittingScan(fromKeypad = true)
        scope.launch { runScan(s.rawQr, localAmountMinorUnits = minorUnits, fromKeypad = true) }
    }

    private suspend fun runScan(rawQr: String, localAmountMinorUnits: Long?, fromKeypad: Boolean) {
        when (val r = client.scan(intentId, rawQr, localAmountMinorUnits)) {
            is ApiResult.Ok -> {
                lastScan = r.value
                _state.value = CheckoutState.Quoted(scan = r.value, rawQr = rawQr)
            }
            is ApiResult.Err -> {
                // A keypad-originated scan failure still lands on the scanner —
                // the amount survives nothing; the user re-captures.
                _state.value = CheckoutState.Scanning(
                    cameraAvailable = true,
                    transientError = r.error,
                )
            }
        }
    }

    private fun backToScanner() {
        val s = _state.value
        if (s is CheckoutState.AmountEntry || s is CheckoutState.Quoted) {
            _state.value = CheckoutState.Scanning(cameraAvailable = true)
        }
    }

    // ---- Quote refresh -------------------------------------------------------

    /**
     * Silent quote refresh when the quote's validity window lapses. Re-runs
     * `/scan` with the same payload (and the same static amount, when one was
     * entered) — allowed (D2=B: scan doesn't burn the jti).
     */
    private fun refreshQuote() {
        val s = _state.value
        if (s !is CheckoutState.Quoted || confirmFired || s.refreshingQuote) return
        _state.value = s.copy(refreshingQuote = true)
        scope.launch {
            when (val r = client.scan(intentId, s.rawQr, lastLocalAmountMinor)) {
                is ApiResult.Ok -> {
                    lastScan = r.value
                    val cur = _state.value
                    if (cur is CheckoutState.Quoted) {
                        _state.value = cur.copy(
                            scan = r.value, refreshingQuote = false, transientError = null,
                        )
                    }
                }
                is ApiResult.Err -> {
                    // Non-fatal: keep showing the stale quote; the confirm path
                    // re-validates server-side.
                    val cur = _state.value
                    if (cur is CheckoutState.Quoted) {
                        _state.value = cur.copy(
                            refreshingQuote = false,
                            transientError = ZennopayError.QuoteExpired,
                        )
                    }
                }
            }
        }
    }

    // ---- Confirm (single-fire, idempotent) -----------------------------------

    private fun confirm() {
        val s = _state.value
        if (s !is CheckoutState.Quoted) return
        scope.launch {
            // Single-fire gate: even if the slide fires twice, only the first
            // wins. The mutex serializes; the flag makes it idempotent.
            confirmMutex.withLock {
                if (confirmFired) return@withLock
                confirmFired = true
            }
            if (_state.value !is CheckoutState.Quoted) return@launch

            _state.value = CheckoutState.Confirming(s.scan, s.rawQr)

            // Persist the idempotency key BEFORE the network call so a crash
            // mid-confirm is recoverable and retry-safe (D5=A).
            val key = idempotencyStore.getOrCreateKey(intentId)

            when (
                val r = client.confirm(
                    intentId = intentId,
                    idempotencyKey = key,
                    quoteId = s.scan.quoteId,
                    quoteVersion = s.scan.quoteVersion,
                )
            ) {
                is ApiResult.Ok -> {
                    // Retain the rich confirm snapshot (merchant + txn id) for
                    // the receipt; the terminal GET projection is minimal.
                    receiptStatus = mergeReceipt(r.value, receiptStatus)
                    walletDebited = true
                    confirmedAtMillis = System.currentTimeMillis()
                    if (r.value.status.terminal) {
                        finishTerminal(resultFrom(r.value))
                    } else {
                        _state.value = CheckoutState.Processing(intentId)
                        pollToTerminal()
                    }
                }
                is ApiResult.Err -> onConfirmError(r.error)
            }
        }
    }

    private suspend fun onConfirmError(error: ZennopayError) {
        when (error) {
            // A replayed confirm (single-use jti already consumed) means the
            // money call already happened on a prior attempt — recover the real
            // status instead of failing.
            is ZennopayError.JtiReplay -> {
                walletDebited = true
                if (confirmedAtMillis == null) confirmedAtMillis = System.currentTimeMillis()
                _state.value = CheckoutState.Processing(intentId)
                pollToTerminal()
            }
            else -> {
                // Confirm failed pre-money (quote expired, etc.). Reset the
                // single-fire latch so "Try again" can re-fire.
                confirmFired = false
                finishTerminal(PaymentResult.Failed(intentId, error))
            }
        }
    }

    // ---- Poll → result -------------------------------------------------------

    private suspend fun pollToTerminal() {
        when (val r = client.pollUntilTerminal(intentId)) {
            is ApiResult.Ok -> {
                receiptStatus = mergeReceipt(r.value, receiptStatus)
                finishTerminal(resultFrom(r.value))
            }
            is ApiResult.Err -> {
                // The confirm went through; an unresolved poll is PENDING, not
                // a failure — the backend auto-refunds an unsettled debit.
                if (r.error is ZennopayError.PollingTimeout || walletDebited) {
                    finishTerminal(PaymentResult.Pending(intentId))
                } else {
                    finishTerminal(PaymentResult.Failed(intentId, r.error))
                }
            }
        }
    }

    private fun resultFrom(status: IntentStatus): PaymentResult = when {
        status.status.isSuccess -> completedFrom(status)
        status.status == IntentState.CANCELED -> PaymentResult.Canceled(intentId)
        status.status.terminal -> PaymentResult.Failed(intentId, ZennopayError.PaymentDeclined)
        // Non-terminal at the poll deadline is a PENDING outcome.
        else -> PaymentResult.Pending(intentId)
    }

    /** Build the Completed payload, preferring the richer scan-quote fields. */
    private fun completedFrom(status: IntentStatus): PaymentResult.Completed {
        val quote = lastScan?.quote
        return PaymentResult.Completed(
            intentId = intentId,
            merchantName = status.merchantName ?: lastScan?.merchantName,
            localAmount = status.localAmount
                ?: quote?.localAmountMinorUnits?.toString(),
            localCurrency = status.localCurrency ?: quote?.localCurrency,
            usdDebited = status.usdDebited
                ?: quote?.amountUsdCents?.let { CurrencyDisplay.groupedNumber(it / 100.0, 2) },
            transactionId = status.transactionId,
            verifiableQrData = null,
        )
    }

    // ---- Terminal + delivery (single host delivery) --------------------------

    /**
     * Reach a terminal state IN-SHEET. The result is NOT delivered to the host
     * yet — the receipt/failure/pending screen stays up until Done — except for
     * Canceled, which has no result UI.
     */
    private fun finishTerminal(result: PaymentResult) {
        _state.value = CheckoutState.Terminal(result)
        when (result) {
            // Completed-and-terminal → the store record is no longer needed.
            is PaymentResult.Completed -> scope.launch { idempotencyStore.clear(intentId) }
            is PaymentResult.Canceled -> {
                scope.launch { idempotencyStore.clear(intentId) }
                deliverToHost(result)
            }
            // Failed keeps the key so a retry reuses it; Pending keeps it so a
            // relaunch recovers the true status.
            is PaymentResult.Failed, is PaymentResult.Pending -> Unit
        }
    }

    /** "Done" on a terminal result screen: deliver and let the host dismiss. */
    private fun closeFromResult() {
        val s = _state.value
        if (s is CheckoutState.Terminal) deliverToHost(s.result)
    }

    /**
     * "Done" on the processing screen: the user leaves while the payment is
     * still processing. Delivers Pending; the backend keeps working and the
     * host reconciles via webhook / status read.
     */
    private fun leaveWhileProcessing() {
        deliverToHost(PaymentResult.Pending(intentId))
    }

    /** X: on a result screen behaves like Done; mid-processing → Pending. */
    private fun cancel() {
        when (val s = _state.value) {
            is CheckoutState.Terminal -> deliverToHost(s.result)
            is CheckoutState.Confirming, is CheckoutState.Processing ->
                leaveWhileProcessing()
            else -> {
                if (confirmFired) {
                    leaveWhileProcessing()
                } else {
                    finishTerminal(PaymentResult.Canceled(intentId))
                }
            }
        }
    }

    private fun retry() {
        val s = _state.value
        if (s !is CheckoutState.Terminal || s.result !is PaymentResult.Failed) return
        if (resultDelivered) return
        confirmFired = false
        val scan = lastScan
        if (scan != null) {
            // Re-fires confirm with the same idempotency key (backend dedupes).
            _state.value = CheckoutState.Quoted(scan = scan, rawQr = lastRawQr)
            confirm()
        } else {
            _state.value = CheckoutState.Scanning(cameraAvailable = true)
        }
    }

    /**
     * The result to deliver when the host tears the sheet down without an
     * explicit Done (system back / swiped away): the terminal result if one was
     * reached; Pending if the wallet may have been debited; else Canceled.
     */
    fun exitResult(): PaymentResult {
        val s = _state.value
        return when {
            s is CheckoutState.Terminal -> s.result
            confirmFired || walletDebited -> PaymentResult.Pending(intentId)
            else -> PaymentResult.Canceled(intentId)
        }
    }

    private fun deliverToHost(result: PaymentResult) {
        if (resultDelivered) return
        resultDelivered = true
        onResult(result)
    }

    /**
     * Prefer the terminal snapshot's status, but keep the richer confirm
     * snapshot's merchant/txn/amount fields when the terminal read omits them.
     */
    private fun mergeReceipt(terminal: IntentStatus, prior: IntentStatus?): IntentStatus {
        if (prior == null) return terminal
        return terminal.copy(
            amountUsdCents = terminal.amountUsdCents ?: prior.amountUsdCents,
            corridor = terminal.corridor ?: prior.corridor,
            merchant = terminal.merchant ?: prior.merchant,
            qrKind = terminal.qrKind ?: prior.qrKind,
            quoteId = terminal.quoteId ?: prior.quoteId,
            quoteVersion = terminal.quoteVersion ?: prior.quoteVersion,
            quoteLocalAmountMinorUnits =
                terminal.quoteLocalAmountMinorUnits ?: prior.quoteLocalAmountMinorUnits,
            quoteLocalCurrency = terminal.quoteLocalCurrency ?: prior.quoteLocalCurrency,
            quoteExpiresAt = terminal.quoteExpiresAt ?: prior.quoteExpiresAt,
            confirmState = terminal.confirmState ?: prior.confirmState,
            transactionId = terminal.transactionId ?: prior.transactionId,
            createdAt = terminal.createdAt ?: prior.createdAt,
            updatedAt = terminal.updatedAt ?: prior.updatedAt,
        )
    }

    /**
     * Whether the failure screen should offer "Try again": retry re-fires
     * confirm with the same idempotency key, which is only sensible when a
     * validated quote survived the failure.
     */
    val hasRetryQuote: Boolean get() = lastScan != null

    // ---- Receipt assembly ----------------------------------------------------

    /**
     * Merchant display name with a corridor-aware fallback: a personal /
     * bank-account VietQR carries no merchant-name tag.
     */
    val displayMerchantName: String
        get() {
            lastScan?.merchantName?.takeIf { it.isNotEmpty() }?.let { return it }
            receiptStatus?.merchantName?.takeIf { it.isNotEmpty() }?.let { return it }
            CorridorBranding.entry(_corridor.value)?.let { return "${it.countryName} Merchant" }
            return "Recipient"
        }

    /**
     * The receipt shown on the success/pending screens, assembled from the last
     * quote (merchant + local amount), the QR peek (bank + account), and the
     * terminal snapshot (USD + txn id + corridor).
     */
    val receipt: Receipt?
        get() {
            val scan = lastScan
            val snap = receiptStatus
            if (scan == null && snap == null) return null
            val localMinor = snap?.quoteLocalAmountMinorUnits
                ?: scan?.quote?.localAmountMinorUnits
            val localCurrency = snap?.quoteLocalCurrency ?: scan?.quote?.localCurrency
            val usdCents = snap?.amountUsdCents?.takeIf { it != 0L }
                ?: scan?.quote?.amountUsdCents ?: 0L
            return Receipt(
                merchantName = displayMerchantName,
                localMinorUnits = localMinor,
                localCurrency = localCurrency,
                usdCents = usdCents,
                transactionId = snap?.transactionId,
                intentId = intentId,
                bankName = qrPeek?.bankName,
                // The receipt flow injects the backend-masked account; the
                // checkout flow peeks it from the raw QR.
                accountMasked = injectedAccountMasked ?: qrPeek?.accountMasked,
                purpose = purposeText.value.trim(),
                timestampMillis = confirmedAtMillis ?: System.currentTimeMillis(),
                corridor = snap?.corridor ?: _corridor.value,
            )
        }

    // ---- Debug gallery -------------------------------------------------------

    /**
     * DEBUG-GALLERY ONLY: freeze the controller into an arbitrary state with
     * injected mock data — NO network, NO money movement (the gallery's
     * transport fails instantly if anything ever tries). Called exclusively by
     * the debug-variant screen gallery (`src/debug`) and semantics tests;
     * never from the production flow.
     */
    internal fun debugApply(
        state: CheckoutState,
        scan: ScanResult? = null,
        snapshot: IntentStatus? = null,
        peek: QrPayload.Peek? = null,
        corridor: String? = null,
        purpose: String = "",
        walletDebited: Boolean = false,
        confirmedAtMillis: Long? = null,
    ) {
        lastScan = scan
        receiptStatus = snapshot
        qrPeek = peek
        corridor?.let { _corridor.value = it }
        purposeText.value = purpose
        this.walletDebited = walletDebited
        this.confirmedAtMillis = confirmedAtMillis
        _state.value = state
    }

    /**
     * DEBUG-GALLERY ONLY: seed a fully-formed receipt for static rendering /
     * screenshots — NO network, NO money movement. Mirrors the iOS
     * `debugApplyReceipt`. Freezes into the resolved terminal screen.
     */
    internal fun debugApplyReceipt(dto: ReceiptDto) {
        applyReceipt(dto)
        when (dto.receiptStatus ?: ReceiptStatus.CAPTURED) {
            ReceiptStatus.CAPTURED, ReceiptStatus.REFUNDED ->
                _state.value = CheckoutState.Terminal(receiptCompleted())
            ReceiptStatus.FAILED ->
                _state.value = CheckoutState.Terminal(
                    PaymentResult.Failed(intentId, ZennopayError.PaymentDeclined),
                )
            ReceiptStatus.PENDING ->
                _state.value = CheckoutState.Terminal(PaymentResult.Pending(intentId))
        }
    }

    /** Display model for the terminal receipt / pending-detail screens. */
    data class Receipt(
        val merchantName: String,
        val localMinorUnits: Long?,
        val localCurrency: String?,
        val usdCents: Long,
        val transactionId: String?,
        val intentId: String,
        val bankName: String?,
        val accountMasked: String?,
        val purpose: String,
        val timestampMillis: Long,
        val corridor: String?,
    )
}
