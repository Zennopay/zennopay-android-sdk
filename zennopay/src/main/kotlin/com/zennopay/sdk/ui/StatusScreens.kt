package com.zennopay.sdk.ui

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zennopay.sdk.ZennopayError
import com.zennopay.sdk.internal.CheckoutController
import com.zennopay.sdk.internal.CheckoutEvent
import com.zennopay.sdk.internal.CurrencyDisplay
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Post-confirm edge/terminal screens per the partner-approved reference (iOS
 * `ProcessingScreen` / `ReceiptScreen` / `FailureScreen` / `PendingDetailScreen`
 * mirrors). Terminal screens are persistent — Done delivers the result; there
 * is NO auto-dismiss.
 */

/** Themed loading state while a keypad-entered amount is being quoted. */
@Composable
internal fun QuoteLoadingScreen() {
    val palette = ZColors.palette()
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp)
        Text(
            text = "Getting your rate…",
            color = palette.text2,
            style = tabularStyle(15.sp, ZType.regular, palette.text2),
            modifier = Modifier.padding(top = ZSpace.md),
        )
    }
}

/**
 * Processing card: spinner + the 30-second promise, a dark tip banner, and a
 * Done button that lets the user leave while processing continues (delivers
 * `PaymentResult.Pending`; polling continues until teardown). After ~30s a
 * "taking longer than usual" bottom sheet escalates.
 */
@Composable
internal fun ProcessingScreen(onEvent: (CheckoutEvent) -> Unit) {
    val palette = ZColors.palette()
    var showDelaySheet by remember { mutableStateOf(false) }

    // Delay escalation (reference f62): after ~30s of processing, slide up the
    // "taking longer than usual" sheet. Polling continues underneath.
    LaunchedEffect(Unit) {
        delay(30_000L)
        showDelaySheet = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = ZSpace.md),
            verticalArrangement = Arrangement.spacedBy(ZSpace.md),
        ) {
            SheetHeader(title = "", onLeading = { onEvent(CheckoutEvent.LeaveWhileProcessing) })
            SurfaceCard(modifier = Modifier.weight(1f).padding(horizontal = ZSpace.md)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        text = "Payment processing…",
                        color = palette.text,
                        style = tabularStyle(16.sp, ZType.medium, palette.text),
                        modifier = Modifier
                            .padding(top = ZSpace.lg)
                            .testTag("zp.processing.title"),
                    )
                    Text(
                        text = "This can take up to 30 seconds",
                        color = palette.text2,
                        style = tabularStyle(14.sp, ZType.regular, palette.text2),
                        modifier = Modifier.padding(top = ZSpace.xs),
                    )
                }
            }
            TipBanner(modifier = Modifier.padding(horizontal = ZSpace.md))
            ZPrimaryButton(
                label = "Done",
                testTag = "zp.processing.done",
                modifier = Modifier.padding(horizontal = ZSpace.md),
            ) {
                onEvent(CheckoutEvent.LeaveWhileProcessing)
            }
        }

        if (showDelaySheet) {
            DelaySheet(
                modifier = Modifier.align(Alignment.BottomCenter),
                onDone = { onEvent(CheckoutEvent.LeaveWhileProcessing) },
            )
        }
    }
}

/** The dark tip banner under the processing card (reference). */
@Composable
private fun TipBanner(modifier: Modifier = Modifier) {
    val shapes = LocalZShapes.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF171C26), RoundedCornerShape(shapes.slide))
            .padding(ZSpace.md),
        horizontalArrangement = Arrangement.spacedBy(ZSpace.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = "💡", style = tabularStyle(16.sp, ZType.regular, Color.White))
        Text(
            text = "The merchant may have already received your payment. Check with them to confirm.",
            color = Color.White,
            style = tabularStyle(13.sp, ZType.regular, Color.White),
        )
    }
}

/** Reference f62: the "taking longer than usual" bottom sheet after ~30s. */
@Composable
private fun DelaySheet(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val palette = ZColors.palette()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                palette.surface,
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .padding(top = ZSpace.sm)
                .width(40.dp)
                .height(4.dp)
                .background(palette.text3.copy(alpha = 0.4f), CircleShape),
        )
        Text(
            text = "Payment processing",
            color = palette.text,
            style = tabularStyle(20.sp, ZType.bold, palette.text),
        )
        Text(
            text = "This payment is taking longer than usual to process. You can check " +
                "with the merchant in the meantime — if it does not complete, the money " +
                "will be refunded back to your account.",
            color = palette.text2,
            style = tabularStyle(14.sp, ZType.regular, palette.text2),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = ZSpace.md),
        )
        ZPrimaryButton(label = "Done", modifier = Modifier.padding(ZSpace.md)) { onDone() }
    }
}

// ---- Success receipt ---------------------------------------------------------

/** Receipt timestamp format shared with the pending-detail screen. */
internal fun receiptTimestamp(millis: Long): String =
    SimpleDateFormat("MMMM d, yyyy, h:mm:ss a", Locale.US).format(Date(millis))

/**
 * Success receipt: Receipt title + Android share sheet, a surface card with the
 * green check, "Payment successful", timestamp, the "3,500,000 VND" hero, and
 * merchant/account/transaction/paid/purpose rows. Persistent — Done delivers
 * `PaymentResult.Completed`; NO auto-dismiss.
 */
@Composable
internal fun ReceiptScreen(
    controller: CheckoutController,
    reducedMotion: Boolean,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val palette = ZColors.palette()
    val context = LocalContext.current
    val receipt = controller.receipt

    var entered by remember { mutableStateOf(reducedMotion) }
    val checkScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.8f,
        animationSpec = tween(durationMillis = 250),
        label = "receiptCheck",
    )
    LaunchedEffect(Unit) { entered = true }

    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(
            title = "Receipt",
            onLeading = { onEvent(CheckoutEvent.CloseFromResult) },
            trailing = {
                IconButton(
                    onClick = { receipt?.let { shareReceipt(context, it) } },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share receipt",
                        tint = palette.text,
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZSpace.md)
                .padding(top = ZSpace.sm),
        ) {
            SurfaceCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ZSpace.md),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = palette.success,
                        modifier = Modifier
                            .padding(top = ZSpace.lg)
                            .size(56.dp)
                            .scale(checkScale),
                    )
                    Text(
                        text = "Payment successful",
                        color = palette.success,
                        style = tabularStyle(20.sp, ZType.bold, palette.success),
                        modifier = Modifier.testTag("zp.result.title"),
                    )
                    if (receipt != null) {
                        Text(
                            text = receiptTimestamp(receipt.timestampMillis),
                            color = palette.text2,
                            style = tabularStyle(14.sp, ZType.regular, palette.text2),
                        )
                        receipt.localMinorUnits?.let { minor ->
                            Text(
                                text = CurrencyDisplay.formatMinorWithLabel(minor, receipt.localCurrency),
                                color = palette.text,
                                style = tabularStyle(34.sp, ZType.bold, palette.text),
                                modifier = Modifier
                                    .padding(bottom = ZSpace.sm)
                                    .testTag("zp.receipt.amount"),
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ZSpace.md)) {
                            ReceiptRow("Merchant name", receipt.merchantName)
                            receipt.accountMasked?.let { ReceiptRow("Account number", it) }
                            ReceiptRow("Transaction ID", receipt.transactionId ?: receipt.intentId)
                            ReceiptRow("You paid exactly", CurrencyDisplay.formatUsdCents(receipt.usdCents))
                            if (receipt.purpose.isNotEmpty()) {
                                ReceiptRow("Purpose of payment", receipt.purpose)
                            }
                        }
                    }
                    Spacer(Modifier.height(ZSpace.lg))
                }
            }
        }
        ZPrimaryButton(
            label = "Done",
            testTag = "zp.receipt.done",
            modifier = Modifier.padding(horizontal = ZSpace.md).padding(bottom = ZSpace.md),
        ) {
            onEvent(CheckoutEvent.CloseFromResult)
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    val palette = ZColors.palette()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = ZSpace.sm),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            color = palette.text2,
            style = tabularStyle(13.sp, ZType.regular, palette.text2),
        )
        Text(
            text = value,
            color = palette.text,
            style = tabularStyle(16.sp, ZType.medium, palette.text),
        )
        HorizontalDivider(
            color = palette.border,
            thickness = 1.dp,
            modifier = Modifier.padding(top = ZSpace.sm),
        )
    }
}

/** Share the receipt facts as plain text via the Android share sheet. */
private fun shareReceipt(context: android.content.Context, r: CheckoutController.Receipt) {
    val lines = buildList {
        add("Payment successful")
        r.localMinorUnits?.let { add(CurrencyDisplay.formatMinorWithLabel(it, r.localCurrency)) }
        add("Paid: ${CurrencyDisplay.formatUsdCents(r.usdCents)}")
        add("Merchant: ${r.merchantName}")
        r.accountMasked?.let { add("Account: $it") }
        add("Transaction: ${r.transactionId ?: r.intentId}")
        if (r.purpose.isNotEmpty()) add("Purpose: ${r.purpose}")
        add("Date: ${receiptTimestamp(r.timestampMillis)}")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
    }
    context.startActivity(Intent.createChooser(send, "Share receipt"))
}

// ---- Failure -----------------------------------------------------------------

/**
 * Terminal failure: red icon on the failure-soft halo, a human reason, the
 * refund reassurance when the wallet was debited, and Try again / Done.
 */
@Composable
internal fun FailureScreen(
    controller: CheckoutController,
    error: ZennopayError,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val palette = ZColors.palette()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = ZSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZSpace.lg),
    ) {
        SheetHeader(title = "", onLeading = { onEvent(CheckoutEvent.CloseFromResult) })
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.size(84.dp).background(palette.failureSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = palette.failure,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = "Payment failed",
            color = palette.text,
            style = tabularStyle(24.sp, ZType.medium, palette.text),
            modifier = Modifier.testTag("zp.result.title"),
        )
        Text(
            text = failureReason(error),
            color = palette.text2,
            style = tabularStyle(14.sp, ZType.regular, palette.text2),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = ZSpace.lg),
        )
        if (controller.walletDebited) {
            Text(
                text = "If the payment does not complete, the money will be refunded " +
                    "back to your account.",
                color = palette.text2,
                style = tabularStyle(14.sp, ZType.regular, palette.text2),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ZSpace.lg),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.padding(bottom = ZSpace.md),
            verticalArrangement = Arrangement.spacedBy(ZSpace.sm),
        ) {
            if (canRetry(controller, error)) {
                ZPrimaryButton(label = "Try again", testTag = "zp.failure.retry") {
                    onEvent(CheckoutEvent.RetryFromFailure)
                }
            }
            ZTextAction(label = "Done") { onEvent(CheckoutEvent.CloseFromResult) }
        }
    }
}

/**
 * Retry re-fires confirm with the same idempotency key — only sensible when a
 * quote existed and the failure wasn't a session-level dead end (iOS parity).
 */
internal fun canRetry(controller: CheckoutController, error: ZennopayError): Boolean =
    when (error) {
        ZennopayError.Unauthorized, ZennopayError.SessionRefreshFailed,
        ZennopayError.JwtExpired, ZennopayError.InvalidJwt,
        ZennopayError.MalformedToken, ZennopayError.IntentMismatch,
        -> false
        else -> controller.hasRetryQuote
    }

// ---- Pending detail ----------------------------------------------------------

/**
 * Pending detail (reference f68): the payment is still processing after the
 * poll budget — status, the 30-minute promise + auto-refund reassurance, and
 * the known facts (rate, totals, date). Done delivers `PaymentResult.Pending`.
 */
@Composable
internal fun PendingDetailScreen(
    controller: CheckoutController,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val palette = ZColors.palette()
    val receipt = controller.receipt

    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(title = "Payment", onLeading = { onEvent(CheckoutEvent.CloseFromResult) })
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZSpace.md),
            verticalArrangement = Arrangement.spacedBy(ZSpace.md),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZSpace.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZSpace.sm),
            ) {
                Text(text = "🕓", style = tabularStyle(44.sp, ZType.regular, palette.pending))
                Text(
                    text = "Processing",
                    color = palette.text2,
                    style = tabularStyle(15.sp, ZType.medium, palette.text2),
                    modifier = Modifier.testTag("zp.result.title"),
                )
                if (receipt != null) {
                    Text(
                        text = CurrencyDisplay.formatUsdCents(receipt.usdCents),
                        color = palette.text,
                        style = tabularStyle(32.sp, ZType.bold, palette.text),
                    )
                    receipt.localMinorUnits?.let { minor ->
                        Text(
                            text = CurrencyDisplay.formatMinorWithLabel(minor, receipt.localCurrency),
                            color = palette.text2,
                            style = tabularStyle(15.sp, ZType.regular, palette.text2),
                        )
                    }
                }
            }
            DetailBlock("Status", "Processing")
            Text(
                text = "Payment is still processing. It may take up to 30 minutes. You can " +
                    "check with the merchant in the meantime. If the payment does not " +
                    "complete, the money will be refunded back to your account.",
                color = palette.text2,
                style = tabularStyle(14.sp, ZType.regular, palette.text2),
            )
            if (receipt != null) {
                CurrencyDisplay.exchangeRateLine(
                    usdCents = receipt.usdCents,
                    localMinorUnits = receipt.localMinorUnits,
                    localCurrency = receipt.localCurrency,
                )?.let { rate ->
                    HorizontalDivider(color = palette.border, thickness = 1.dp)
                    DetailBlock("Exchange rate", rate)
                }
                HorizontalDivider(color = palette.border, thickness = 1.dp)
                DetailBlock("You paid exactly", CurrencyDisplay.formatUsdCents(receipt.usdCents))
                HorizontalDivider(color = palette.border, thickness = 1.dp)
                DetailBlock("Date", receiptTimestamp(receipt.timestampMillis))
            }
        }
        ZPrimaryButton(
            label = "Done",
            testTag = "zp.pending.done",
            modifier = Modifier.padding(horizontal = ZSpace.md).padding(bottom = ZSpace.md),
        ) {
            onEvent(CheckoutEvent.CloseFromResult)
        }
    }
}

@Composable
private fun DetailBlock(label: String, value: String) {
    val palette = ZColors.palette()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            color = palette.text2,
            style = tabularStyle(13.sp, ZType.regular, palette.text2),
        )
        Text(
            text = value,
            color = palette.text,
            style = tabularStyle(16.sp, ZType.medium, palette.text),
        )
    }
}

// ---- User-facing copy --------------------------------------------------------

/**
 * Verbatim user-facing copy for the scanner / review inline banner (transient,
 * recoverable errors). iOS `humanMessage` parity.
 */
internal fun humanMessage(error: ZennopayError): String = when (error) {
    ZennopayError.ScanValidationFailed, ZennopayError.QrUndecodable ->
        "That code couldn't be read. Make sure it's a merchant payment QR and try again."
    ZennopayError.QuoteExpired ->
        "Rate refreshed, please review the new amount."
    ZennopayError.Unauthorized, ZennopayError.SessionRefreshFailed, ZennopayError.JwtExpired ->
        "Your session expired. Please return to the app and try again."
    ZennopayError.CameraPermissionDenied ->
        "Camera access is off. Allow camera in Settings, or paste the QR data instead."
    is ZennopayError.NetworkError ->
        "Couldn't get a rate. Try again in a moment."
    ZennopayError.PaymentDeclined ->
        "The payment couldn't be completed."
    else ->
        "That code couldn't be read. Make sure it's a merchant payment QR and try again."
}

/** Failure-reason copy for the terminal failure screen. iOS parity. */
internal fun failureReason(error: ZennopayError): String = when (error) {
    ZennopayError.PaymentDeclined ->
        "The payment couldn't be completed. Try again, or pay another way."
    is ZennopayError.NetworkError ->
        "Network issue. Check your connection and try again."
    ZennopayError.QuoteExpired, ZennopayError.QuoteMismatch, ZennopayError.QuoteSuperseded ->
        "The rate changed. Review the new amount and try again."
    ZennopayError.Unauthorized, ZennopayError.SessionRefreshFailed,
    ZennopayError.JwtExpired, ZennopayError.InvalidJwt,
    ZennopayError.MalformedToken, ZennopayError.IntentMismatch,
    ->
        "Something went wrong starting this payment. Please return to the app and try again."
    else ->
        "The payment couldn't be completed."
}
