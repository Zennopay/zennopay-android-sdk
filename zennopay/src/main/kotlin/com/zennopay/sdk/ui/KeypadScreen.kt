package com.zennopay.sdk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zennopay.sdk.internal.CheckoutEvent
import com.zennopay.sdk.internal.CheckoutState
import com.zennopay.sdk.internal.CurrencyDisplay
import com.zennopay.sdk.internal.DisbursementLimit

/**
 * Local-currency amount entry for STATIC QRs (no amount in the code, EMVCo tag
 * 54 absent per the on-device peek) — the Compose mirror of the iOS
 * `KeypadScreen`: huge local amount, Review button, and a large in-sheet
 * numeric keypad (1-9, 000, 0, backspace) with accent-colored glyphs.
 */
@Composable
internal fun KeypadScreen(
    state: CheckoutState.AmountEntry,
    corridor: String?,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val palette = ZColors.palette()
    // Static QRs carry no currency until scanned; infer from the corridor.
    val currencyNumeric = if (corridor == "th_promptpay") "764" else "704"
    var digits by remember(state.rawQr) { mutableStateOf("") }
    val minorUnits = (digits.toLongOrNull() ?: 0L) * 100L
    val overLimit = DisbursementLimit.exceedsVndPerTransaction(minorUnits, currencyNumeric)

    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(
            title = "Payment",
            leading = HeaderLeading.Back,
            onLeading = { onEvent(CheckoutEvent.BackToScanner) },
        )
        Spacer(Modifier.weight(1f))
        AmountHero(
            symbol = CurrencyDisplay.symbol(currencyNumeric),
            digits = digits,
        )
        Spacer(Modifier.weight(1f))
        if (overLimit) {
            InlineError(
                message = "This is above the ₫5,000,000 limit per payment. Enter a smaller amount.",
                modifier = Modifier.padding(horizontal = ZSpace.md).padding(bottom = ZSpace.sm),
            )
        }
        ZPrimaryButton(
            label = "Review",
            enabled = minorUnits > 0L && !overLimit,
            testTag = "zp.keypad.review",
            modifier = Modifier.padding(horizontal = ZSpace.md),
        ) {
            onEvent(CheckoutEvent.StaticAmountEntered(minorUnits))
        }
        NumericKeypad(
            modifier = Modifier
                .padding(horizontal = ZSpace.lg)
                .padding(top = ZSpace.md, bottom = ZSpace.md),
        ) { key ->
            digits = applyKey(digits, key)
        }
    }
}

@Composable
private fun AmountHero(symbol: String, digits: String) {
    val palette = ZColors.palette()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ZSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZSpace.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = symbol,
                style = tabularStyle(34.sp, ZType.bold, palette.text),
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = if (digits.isEmpty()) "0"
                else CurrencyDisplay.groupedNumber((digits.toLongOrNull() ?: 0L).toDouble(), 0),
                style = tabularStyle(
                    64.sp, ZType.bold,
                    if (digits.isEmpty()) palette.text3 else palette.text,
                ),
                maxLines = 1,
                modifier = Modifier.testTag("zp.amount.entry"),
            )
        }
        Text(
            text = "Enter the amount from the merchant",
            color = palette.text3,
            style = tabularStyle(13.sp, ZType.regular, palette.text3),
        )
    }
}

/** Keypad keys: 1..9, 000, 0, backspace. */
internal sealed class KeypadKey {
    data class Digit(val d: Char) : KeypadKey()
    object TripleZero : KeypadKey()
    object Backspace : KeypadKey()
}

/**
 * Pure keypad-entry reducer (unit-tested): appends within a 10-digit budget,
 * rejects a leading zero, and only allows "000" once digits exist.
 */
internal fun applyKey(digits: String, key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> when {
        digits.length >= 10 -> digits
        digits.isEmpty() && key.d == '0' -> digits
        else -> digits + key.d
    }
    KeypadKey.TripleZero ->
        if (digits.isEmpty() || digits.length >= 8) digits else digits + "000"
    KeypadKey.Backspace -> digits.dropLast(1)
}

/** The reference keypad grid: accent-colored glyphs, 56dp rows. */
@Composable
internal fun NumericKeypad(
    modifier: Modifier = Modifier,
    onKey: (KeypadKey) -> Unit,
) {
    val palette = ZColors.palette()
    val rows: List<List<KeypadKey>> = listOf(
        listOf(KeypadKey.Digit('1'), KeypadKey.Digit('2'), KeypadKey.Digit('3')),
        listOf(KeypadKey.Digit('4'), KeypadKey.Digit('5'), KeypadKey.Digit('6')),
        listOf(KeypadKey.Digit('7'), KeypadKey.Digit('8'), KeypadKey.Digit('9')),
        listOf(KeypadKey.TripleZero, KeypadKey.Digit('0'), KeypadKey.Backspace),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZSpace.sm)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZSpace.sm),
            ) {
                row.forEach { key ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clickable { onKey(key) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (key) {
                            is KeypadKey.Digit -> Text(
                                text = key.d.toString(),
                                style = tabularStyle(30.sp, ZType.medium, palette.accent),
                            )
                            KeypadKey.TripleZero -> Text(
                                text = "000",
                                style = tabularStyle(26.sp, ZType.medium, palette.accent),
                            )
                            KeypadKey.Backspace -> Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "delete",
                                tint = palette.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
