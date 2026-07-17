package com.zennopay.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zennopay.sdk.internal.CurrencyDisplay
import com.zennopay.sdk.internal.net.Quote

/**
 * Tapping "You'll pay exactly" on the review screen opens the full cost
 * breakdown (iOS `FeeBreakdownSheet` mirror): what the merchant receives, the
 * locked FX rate, the subtotal, fees, and any offer applied. The staging quote
 * carries no fee model yet, so the fee line is $0.00 and the offer row renders
 * only while the fee is zero — the layout is already structured for both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeeBreakdownSheet(
    quote: Quote?,
    onDismiss: () -> Unit,
) {
    val palette = ZColors.palette()
    val shapes = LocalZShapes.current

    // Fee in USD cents. Comes from the quote the day the backend prices fees;
    // until then the total IS the wallet debit.
    val feeUsdCents = 0L
    // Offer/promo line. The offer slot renders only when present.
    val offerLabel: String? = if (feeUsdCents == 0L) "Zero-fee launch pricing" else null

    val usdCents = quote?.amountUsdCents ?: 0L
    val subtotal = CurrencyDisplay.formatUsdCents(usdCents)
    val merchantReceives = quote?.localAmountMinorUnits
        ?.let { CurrencyDisplay.formatMinorWithLabel(it, quote.localCurrency) }
        ?: subtotal
    val rate = CurrencyDisplay.exchangeRateLine(
        usdCents = usdCents,
        localMinorUnits = quote?.localAmountMinorUnits,
        localCurrency = quote?.localCurrency,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.bg,
        modifier = Modifier.testTag("zp.breakdown.sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZSpace.lg)
                .padding(bottom = ZSpace.xl),
            verticalArrangement = Arrangement.spacedBy(ZSpace.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Payment breakdown",
                    color = palette.text,
                    style = tabularStyle(17.sp, ZType.medium, palette.text),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Close breakdown" }
                        .testTag("zp.breakdown.close"),
                ) {
                    Box(
                        modifier = Modifier.size(32.dp).background(palette.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            tint = palette.text2,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, palette.border, RoundedCornerShape(shapes.card))
                    .padding(horizontal = ZSpace.md),
            ) {
                BreakdownRow("Merchant receives", merchantReceives, emphasized = false)
                HorizontalDivider(color = palette.border, thickness = 1.dp)
                rate?.let {
                    BreakdownRow("Exchange rate", it, emphasized = false)
                    HorizontalDivider(color = palette.border, thickness = 1.dp)
                }
                BreakdownRow("Subtotal", subtotal, emphasized = false)
                HorizontalDivider(color = palette.border, thickness = 1.dp)
                // Fee row: $0.00 in success-green while the zero-fee offer runs.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        // One TalkBack element: "Convenience fee, Zero-fee
                        // launch pricing, $0.00".
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Convenience fee",
                            color = palette.text2,
                            style = tabularStyle(14.sp, ZType.regular, palette.text2),
                        )
                        offerLabel?.let {
                            Text(
                                text = it,
                                color = palette.success,
                                style = tabularStyle(12.sp, ZType.medium, palette.success),
                            )
                        }
                    }
                    Text(
                        text = CurrencyDisplay.formatUsdCents(feeUsdCents),
                        color = if (feeUsdCents == 0L) palette.success else palette.text,
                        style = tabularStyle(
                            14.sp, ZType.regular,
                            if (feeUsdCents == 0L) palette.success else palette.text,
                        ),
                        maxLines = 1,
                        modifier = Modifier.testTag("zp.breakdown.fee"),
                    )
                }
                HorizontalDivider(color = palette.border, thickness = 1.dp)
                BreakdownRow(
                    "You'll pay exactly",
                    CurrencyDisplay.formatUsdCents(usdCents + feeUsdCents),
                    emphasized = true,
                )
            }

            Text(
                text = "The merchant always receives the exact QR amount in their currency. " +
                    "Your wallet is debited in USD at the locked rate above — no hidden " +
                    "margin is added to the rate.",
                color = palette.text3,
                style = tabularStyle(12.sp, ZType.regular, palette.text3),
            )
            Spacer(Modifier.size(ZSpace.sm))
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, emphasized: Boolean) {
    val palette = ZColors.palette()
    val labelColor = if (emphasized) palette.text else palette.text2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            // One TalkBack element per row: "Subtotal, $140.00" (iOS parity).
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = labelColor,
            style = tabularStyle(
                if (emphasized) 15.sp else 14.sp,
                if (emphasized) ZType.medium else ZType.regular,
                labelColor,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = palette.text,
            style = tabularStyle(
                if (emphasized) 16.sp else 14.sp,
                if (emphasized) ZType.medium else ZType.regular,
                palette.text,
            ),
            maxLines = 1,
        )
    }
}
