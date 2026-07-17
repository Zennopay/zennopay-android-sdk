package com.zennopay.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zennopay.sdk.internal.CheckoutController
import com.zennopay.sdk.internal.CheckoutEvent
import com.zennopay.sdk.internal.CorridorBranding
import com.zennopay.sdk.internal.CurrencyDisplay
import com.zennopay.sdk.internal.net.ScanResult
import kotlinx.coroutines.delay

/**
 * The quote/review screen per the partner-approved reference (iOS
 * `ReviewScreen` mirror): merchant card with flag avatar + bank/account +
 * verified badge, the LOCAL amount as the hero, the USD amount as a secondary
 * chip, a fees row, an optional purpose field, and slide-to-pay whose knob
 * becomes a spinner while the confirm+poll runs.
 */
@Composable
internal fun ReviewScreen(
    controller: CheckoutController,
    scan: ScanResult,
    confirming: Boolean,
    refreshingQuote: Boolean,
    transientError: String?,
    reducedMotion: Boolean,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val palette = ZColors.palette()
    val corridor by controller.corridor.collectAsState()
    val quote = scan.quote

    // Silent re-quote when the quote's validity window lapses (never while the
    // confirm is in flight). The controller de-dupes overlapping refreshes.
    LaunchedEffect(quote?.quoteId, quote?.expiresAt, confirming) {
        val expiresAt = quote?.expiresAt ?: return@LaunchedEffect
        if (confirming) return@LaunchedEffect
        while (true) {
            val remaining = expiresAt - System.currentTimeMillis()
            if (remaining <= 0L) {
                onEvent(CheckoutEvent.RequoteRequested)
                break
            }
            delay(remaining.coerceAtMost(1_000L))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(title = "Payment", onLeading = { onEvent(CheckoutEvent.Cancel) })
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZSpace.md),
            verticalArrangement = Arrangement.spacedBy(ZSpace.lg),
        ) {
            Spacer(Modifier.size(ZSpace.xs))
            MerchantCard(controller, scan, corridor)
            AmountHeroSection(scan)
            DetailRowsCard(scan)
            PurposeField(controller, enabled = !confirming)
            transientError?.let { InlineError(message = it) }
            Spacer(Modifier.size(ZSpace.xs))
        }
        SlideToConfirm(
            label = "Slide to pay",
            enabled = !refreshingQuote,
            confirming = confirming,
            reducedMotion = reducedMotion,
            modifier = Modifier.padding(horizontal = ZSpace.md).padding(bottom = ZSpace.md),
        ) {
            onEvent(CheckoutEvent.SlideConfirmed)
        }
    }
}

/**
 * Merchant card: flag avatar, merchant name (corridor-aware fallback), the
 * "VIETCOMBANK • 10230…0000" bank line from the display-only QR peek, the
 * green "Vietnam (VietQR Payout)" caption, and the verified badge.
 */
@Composable
private fun MerchantCard(
    controller: CheckoutController,
    scan: ScanResult,
    corridor: String?,
) {
    val palette = ZColors.palette()
    val branding = CorridorBranding.entry(corridor)
    val peek = controller.qrPeek

    val bankLine: String? = run {
        val bank = peek?.bankName
        val account = peek?.accountMasked
        when {
            bank != null && account != null -> "$bank • $account"
            bank != null -> bank
            account != null -> account
            else -> scan.merchantCity
        }
    }
    val schemeName = when (scan.merchant?.scheme?.lowercase()) {
        "promptpay" -> "PromptPay"
        "vietqr" -> "VietQR"
        else -> scan.merchant?.scheme?.takeIf { it.isNotEmpty() }?.uppercase()
            ?: branding?.schemeName
    }

    SurfaceCard {
        Row(
            modifier = Modifier.padding(ZSpace.md),
            horizontalArrangement = Arrangement.spacedBy(ZSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFE94B4B).copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = CurrencyDisplay.flag(scan.localCurrency),
                    style = tabularStyle(26.sp, ZType.regular, palette.text),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = controller.displayMerchantName,
                    color = palette.text,
                    style = tabularStyle(16.sp, ZType.medium, palette.text),
                    modifier = Modifier.testTag("zp.review.merchant"),
                )
                bankLine?.let {
                    Text(
                        text = it,
                        color = palette.text2,
                        style = tabularStyle(13.sp, ZType.regular, palette.text2),
                    )
                }
                branding?.let {
                    Text(
                        text = "${it.countryName} (${it.schemeName} Payout)",
                        color = palette.success,
                        style = tabularStyle(12.sp, ZType.medium, palette.success),
                    )
                }
                schemeName?.let {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = palette.success,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Verified on $it",
                            color = palette.success,
                            style = tabularStyle(12.sp, ZType.medium, palette.success),
                        )
                    }
                }
            }
        }
    }
}

/** LOCAL-currency-primary amount hero + the secondary USD chip. */
@Composable
private fun AmountHeroSection(scan: ScanResult) {
    val palette = ZColors.palette()
    val quote = scan.quote
    val usd = CurrencyDisplay.formatUsdCents(quote?.amountUsdCents ?: 0L)
    val localHeadline = quote?.localAmountMinorUnits
        ?.let { CurrencyDisplay.formatMinor(it, quote.localCurrency) }
        ?: usd

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZSpace.md),
    ) {
        // Shrinks to fit rather than clipping (iOS minimumScaleFactor parity)
        // so a defensive-overflow amount stays on one legible line.
        AutoShrinkText(
            text = localHeadline,
            style = tabularStyle(ZType.amount, ZType.bold, palette.text, hero = true),
            modifier = Modifier.testTag("zp.amount.local"),
        )
        Row(
            modifier = Modifier
                .background(palette.surface, CircleShape)
                .border(1.dp, palette.border, CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "🇺🇸", style = tabularStyle(14.sp, ZType.regular, palette.text))
            Text(
                text = usd,
                color = palette.text,
                style = tabularStyle(15.sp, ZType.medium, palette.text),
                modifier = Modifier
                    .semantics {
                        // Human-readable money label (iOS parity).
                        contentDescription = "US dollar equivalent $usd"
                    }
                    .testTag("zp.amount.usd"),
            )
        }
    }
}

/** "You'll pay exactly / Total with fees" + the implied exchange-rate row. */
@Composable
private fun DetailRowsCard(scan: ScanResult) {
    val palette = ZColors.palette()
    val quote = scan.quote
    val usd = CurrencyDisplay.formatUsdCents(quote?.amountUsdCents ?: 0L)
    var showBreakdown by remember { mutableStateOf(false) }

    if (showBreakdown) {
        FeeBreakdownSheet(quote = quote, onDismiss = { showBreakdown = false })
    }

    SurfaceCard {
        Column(modifier = Modifier.padding(horizontal = ZSpace.md)) {
            // The whole row is a button: tapping opens the full payment
            // breakdown (fees + locked exchange rate) as a bottom sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Show the full payment breakdown, " +
                            "including fees and the exchange rate",
                    ) { showBreakdown = true }
                    .padding(vertical = 14.dp)
                    .testTag("zp.review.breakdown"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "You'll pay exactly",
                        color = palette.text,
                        style = tabularStyle(15.sp, ZType.medium, palette.text),
                    )
                    Text(
                        text = "Total with fees",
                        color = palette.text2,
                        style = tabularStyle(13.sp, ZType.regular, palette.text2),
                    )
                }
                Text(
                    text = usd,
                    color = palette.text,
                    style = tabularStyle(15.sp, ZType.medium, palette.text),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = palette.text3,
                    modifier = Modifier.size(16.dp),
                )
            }
            CurrencyDisplay.exchangeRateLine(
                usdCents = quote?.amountUsdCents ?: 0L,
                localMinorUnits = quote?.localAmountMinorUnits,
                localCurrency = quote?.localCurrency,
            )?.let { rate ->
                HorizontalDivider(color = palette.border, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                        // One TalkBack element: "Exchange rate, 1 USD = …".
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Exchange rate",
                        color = palette.text2,
                        style = tabularStyle(13.sp, ZType.regular, palette.text2),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = rate,
                        color = palette.text2,
                        style = tabularStyle(13.sp, ZType.regular, palette.text2),
                    )
                }
            }
        }
    }
}

/**
 * "Purpose of payment (optional)": client-side only — echoed on the receipt,
 * never transmitted (no backend field exists yet).
 */
@Composable
private fun PurposeField(controller: CheckoutController, enabled: Boolean) {
    val palette = ZColors.palette()
    val shapes = LocalZShapes.current
    val purpose by controller.purposeText.collectAsState()
    SurfaceCard {
        TextField(
            value = purpose,
            onValueChange = { controller.purposeText.value = it },
            enabled = enabled,
            placeholder = {
                Text(
                    text = "Purpose of payment (optional)",
                    color = palette.text3,
                    style = tabularStyle(15.sp, ZType.regular, palette.text3),
                )
            },
            textStyle = tabularStyle(15.sp, ZType.regular, palette.text),
            singleLine = true,
            shape = RoundedCornerShape(shapes.slide),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = palette.surface,
                unfocusedContainerColor = palette.surface,
                disabledContainerColor = palette.surface,
                focusedTextColor = palette.text,
                unfocusedTextColor = palette.text,
                cursorColor = palette.accent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().testTag("zp.review.purpose"),
        )
    }
}

/** Rounded surface card with the themed hairline border. */
@Composable
internal fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = ZColors.palette()
    val shapes = LocalZShapes.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface, RoundedCornerShape(shapes.slide))
            .border(1.dp, palette.border, RoundedCornerShape(shapes.slide)),
    ) {
        content()
    }
}
