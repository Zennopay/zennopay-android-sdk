package com.zennopay.sdk.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.ZennopayConfig
import com.zennopay.sdk.ZennopayError
import com.zennopay.sdk.internal.CheckoutController
import com.zennopay.sdk.internal.CheckoutState
import com.zennopay.sdk.internal.InMemoryIdempotencyStore
import com.zennopay.sdk.internal.net.HttpRequest
import com.zennopay.sdk.internal.net.HttpResponse
import com.zennopay.sdk.internal.net.HttpTransport
import com.zennopay.sdk.internal.net.IntentState
import com.zennopay.sdk.internal.net.IntentStatus
import com.zennopay.sdk.internal.net.Merchant
import com.zennopay.sdk.internal.net.Quote
import com.zennopay.sdk.internal.net.ScanResult
import com.zennopay.sdk.internal.net.ZennopayRestClient
import com.zennopay.sdk.scanner.QrPayload
import com.zennopay.sdk.ui.CheckoutRoot
import com.zennopay.sdk.ui.FeeBreakdownSheet
import com.zennopay.sdk.ui.ZennopayAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException

/**
 * DEBUG-only screen gallery, the Android mirror of the iOS
 * `ZennopayDebugGallery`: renders any PaymentSheet screen with injected mock
 * state — NO network, NO camera requirement for non-scanner screens, NO money
 * movement. Lives in the `debug` source set so it is compiled out of release
 * artifacts entirely.
 *
 * Spec format `<screen>[:<variant>]`:
 *
 *   screen:   scanner | keypad | review | breakdown | processing | receipt
 *             | failure | pending
 *   variant:  vnd35     ₫3,500,000 / $140.00      (the demo amount)
 *             vndmax    ₫4,999,999 / $200.00      (max under the ₫5M cap)
 *             vndhuge   ₫999,999,999 / $99,999.99 (defensive overflow probe)
 *             thbmax    ฿999,999.99 / $28,571.43  (2-decimal currency)
 *
 * Example: `am start … -e spec review:vndhuge`.
 */
object ZennopayDebugGallery {

    /** A static VietQR (no tag 54) so the keypad screen has a real payload. */
    const val STATIC_DEMO_QR =
        "00020101021238570010A00000072701270006970436011310230203300000208QRIBFTTA53037045802VN6304"

    enum class Variant(
        val localMinorUnits: Long,
        val currencyNumeric: String,
        val usdCents: Long,
    ) {
        VND35(350_000_000L, "704", 14_000L),
        VNDMAX(499_999_900L, "704", 20_000L),
        VNDHUGE(99_999_999_900L, "704", 9_999_999L),
        THBMAX(99_999_999L, "764", 2_857_143L);

        val corridor: String get() = if (this == THBMAX) "th_promptpay" else "vn_vietqr"

        val merchantName: String
            get() = if (this == THBMAX) "Chatuchak Coffee Roasters" else "Cà Phê Sài Gòn"

        internal val scan: ScanResult
            get() = ScanResult(
                intentId = "zp_debug_gallery",
                status = IntentState.CREATED,
                merchant = Merchant(
                    scheme = if (this == THBMAX) "promptpay" else "vietqr",
                    name = merchantName,
                    city = if (this == THBMAX) "Bangkok" else "Ho Chi Minh City",
                    country = if (this == THBMAX) "TH" else "VN",
                    mcc = "5814",
                ),
                qrKind = "dynamic",
                quote = Quote(
                    quoteId = "q_debug",
                    quoteVersion = 1,
                    amountUsdCents = usdCents,
                    localAmountMinorUnits = localMinorUnits,
                    localCurrency = currencyNumeric,
                    // Far-future expiry so the review ticker never re-quotes.
                    expiresAt = System.currentTimeMillis() + 86_400_000L,
                ),
            )

        val peek: QrPayload.Peek
            get() =
                if (this == THBMAX) {
                    QrPayload.Peek(isStatic = false, bankBin = null, accountNumber = null)
                } else {
                    QrPayload.Peek(
                        isStatic = false,
                        bankBin = "970436",
                        accountNumber = "10230203300000",
                    )
                }

        internal val snapshot: IntentStatus
            get() = IntentStatus(
                intentId = "zp_debug_gallery",
                status = IntentState.CAPTURED,
                amountUsdCents = usdCents,
                corridor = corridor,
                merchant = scan.merchant,
                qrKind = "dynamic",
                quoteId = "q_debug",
                quoteVersion = 1,
                quoteLocalAmountMinorUnits = localMinorUnits,
                quoteLocalCurrency = currencyNumeric,
                quoteExpiresAt = null,
                confirmState = "done",
                transactionId = "txn_debug_000042",
                createdAt = null,
                updatedAt = null,
            )

        companion object {
            fun parse(raw: String?): Variant = when (raw?.lowercase()) {
                "vndmax" -> VNDMAX
                "vndhuge" -> VNDHUGE
                "thbmax" -> THBMAX
                else -> VND35
            }
        }
    }

    /**
     * Build a frozen [CheckoutController] for a gallery screen. The transport
     * fails everything instantly — the gallery must never reach the network.
     */
    internal fun frozenController(screen: String, variant: Variant): CheckoutController {
        val client = ZennopayRestClient(
            config = ZennopayConfig(apiBaseUrl = "https://invalid.zennopay.test"),
            transport = GalleryNoopTransport,
            initialSessionJwt = "debug",
            refreshSession = null,
        )
        val controller = CheckoutController(
            intentId = "zp_debug_gallery",
            client = client,
            idempotencyStore = InMemoryIdempotencyStore(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            cameraAvailable = true,
            initialCorridor = variant.corridor,
            onResult = { /* the gallery never delivers */ },
        )
        val terminal = screen in setOf("receipt", "failure", "pending")
        controller.debugApply(
            state = galleryState(screen, variant),
            scan = variant.scan,
            snapshot = if (terminal) variant.snapshot else null,
            peek = variant.peek,
            corridor = variant.corridor,
            purpose = if (screen == "receipt") "Coffee with the team" else "",
            walletDebited = terminal,
            confirmedAtMillis = if (terminal) System.currentTimeMillis() else null,
        )
        return controller
    }

    private fun galleryState(screen: String, variant: Variant): CheckoutState = when (screen) {
        "scanner" -> CheckoutState.Scanning(cameraAvailable = true)
        "keypad" -> CheckoutState.AmountEntry(rawQr = STATIC_DEMO_QR)
        "review" -> CheckoutState.Quoted(scan = variant.scan, rawQr = STATIC_DEMO_QR)
        "processing" -> CheckoutState.Processing(intentId = "zp_debug_gallery")
        "receipt" -> CheckoutState.Terminal(
            PaymentResult.Completed(
                intentId = "zp_debug_gallery",
                merchantName = variant.merchantName,
                localAmount = variant.localMinorUnits.toString(),
                localCurrency = variant.currencyNumeric,
                usdDebited = null,
                transactionId = "txn_debug_000042",
                verifiableQrData = null,
            ),
        )
        "failure" -> CheckoutState.Terminal(
            PaymentResult.Failed("zp_debug_gallery", ZennopayError.PaymentDeclined),
        )
        "pending" -> CheckoutState.Terminal(PaymentResult.Pending("zp_debug_gallery"))
        else -> CheckoutState.Quoted(scan = variant.scan, rawQr = STATIC_DEMO_QR)
    }

    /** The gallery root: parses [spec] and renders the frozen screen. */
    @Composable
    fun Root(spec: String, appearance: ZennopayAppearance = ZennopayAppearance.Automatic) {
        val parts = spec.split(":", limit = 2)
        val screen = parts.getOrNull(0)?.lowercase()?.ifEmpty { null } ?: "review"
        val variant = Variant.parse(parts.getOrNull(1))

        if (screen == "breakdown") {
            // The breakdown is a sheet, not a state — render it directly.
            FeeBreakdownSheet(quote = variant.scan.quote, onDismiss = {})
            return
        }
        val controller = remember(spec) { frozenController(screen, variant) }
        CheckoutRoot(controller = controller, appearance = appearance)
    }

    private object GalleryNoopTransport : HttpTransport {
        override fun execute(request: HttpRequest): HttpResponse {
            throw IOException("Zennopay debug gallery never reaches the network")
        }
    }
}
