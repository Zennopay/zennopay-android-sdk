package com.zennopay.sdk.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zennopay.sdk.internal.CheckoutEvent
import com.zennopay.sdk.internal.CheckoutState
import com.zennopay.sdk.internal.CurrencyDisplay
import com.zennopay.sdk.internal.DisbursementLimit
import com.zennopay.sdk.internal.KeypadInputPolicy
import kotlin.math.PI
import kotlin.math.sin

/**
 * Local-currency amount entry for STATIC QRs (no amount in the code, EMVCo tag
 * 54 absent per the on-device peek) — the Compose mirror of the iOS
 * `KeypadScreen`: huge local amount, Review button, and a large in-sheet
 * numeric keypad (1-9, 000, 0, backspace) with accent-colored glyphs.
 *
 * Input runs through [KeypadInputPolicy] (iOS parity): a keypress that would
 * push the amount past the ₫5,000,000 per-payment cap or the 9-digit ceiling
 * is REFUSED — the hero answers with a gentle shake (unless [reducedMotion])
 * and the limit copy, so the amount can never overflow the layout.
 */
@Composable
internal fun KeypadScreen(
    state: CheckoutState.AmountEntry,
    corridor: String?,
    reducedMotion: Boolean,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val palette = ZColors.palette()
    // Static QRs carry no currency until scanned; infer from the corridor.
    val currencyNumeric = if (corridor == "th_promptpay") "764" else "704"
    var digits by remember(state.rawQr) { mutableStateOf("") }
    var limitHint by remember(state.rawQr) { mutableStateOf<KeypadInputPolicy.Hint?>(null) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    val minorUnits = (digits.toLongOrNull() ?: 0L) * 100L
    val overLimit = DisbursementLimit.exceedsVndPerTransaction(minorUnits, currencyNumeric)

    // Horizontal shake on a refused keypress (3 oscillations, 6dp travel —
    // the iOS `ShakeEffect` mirror). Suppressed entirely under reduced motion:
    // the hint copy alone signals the refusal.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0 && !reducedMotion) {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(durationMillis = 350, easing = LinearEasing))
        }
    }
    val travelPx = with(LocalDensity.current) { 6.dp.toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(
            title = "Payment",
            leading = HeaderLeading.Back,
            onLeading = { onEvent(CheckoutEvent.BackToScanner) },
        )
        Spacer(Modifier.weight(1f))
        AmountHero(
            symbol = CurrencyDisplay.symbol(currencyNumeric),
            currencyLabel = CurrencyDisplay.label(currencyNumeric),
            digits = digits,
            modifier = Modifier.graphicsLayer {
                translationX = travelPx * sin(shake.value * PI.toFloat() * 6f)
            },
        )
        Spacer(Modifier.weight(1f))
        limitHint?.let { hint ->
            Text(
                text = hintCopy(hint),
                color = palette.pending,
                style = tabularStyle(13.sp, ZType.medium, palette.pending),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZSpace.md)
                    .padding(bottom = ZSpace.sm)
                    .testTag("zp.keypad.limitHint"),
            )
        }
        if (overLimit) {
            // Belt-and-braces: unreachable via the keypad (input past the cap
            // is refused), but kept for any restored/edge state.
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
            val result = applyKey(digits, key, currencyNumeric)
            digits = result.digits
            limitHint = when {
                result.refusedHint != null -> {
                    shakeTrigger += 1
                    result.refusedHint
                }
                else -> null // any accepted key (or backspace) clears the hint
            }
        }
    }
}

@Composable
private fun AmountHero(
    symbol: String,
    currencyLabel: String,
    digits: String,
    modifier: Modifier = Modifier,
) {
    val palette = ZColors.palette()
    val grouped =
        if (digits.isEmpty()) "0"
        else CurrencyDisplay.groupedNumber((digits.toLongOrNull() ?: 0L).toDouble(), 0)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = ZSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZSpace.md),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            // TalkBack reads the entered amount as one human element, e.g.
            // "Amount: 35,000 VND" (iOS parity).
            modifier = Modifier.clearAndSetSemantics {
                testTag = "zp.amount.entry"
                contentDescription =
                    if (digits.isEmpty()) "Amount, not yet entered"
                    else "Amount: $grouped $currencyLabel"
            },
        ) {
            Text(
                text = symbol,
                style = tabularStyle(34.sp, ZType.bold, palette.text, hero = true),
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = grouped,
                style = tabularStyle(
                    64.sp, ZType.bold,
                    if (digits.isEmpty()) palette.text3 else palette.text,
                    hero = true,
                ),
                maxLines = 1,
            )
        }
        Text(
            text = "Enter the amount from the merchant",
            color = palette.text3,
            style = tabularStyle(13.sp, ZType.regular, palette.text3),
        )
    }
}

private fun hintCopy(hint: KeypadInputPolicy.Hint): String = when (hint) {
    KeypadInputPolicy.Hint.VND_PER_TRANSACTION_LIMIT ->
        "The limit is ₫5,000,000 per payment."
    KeypadInputPolicy.Hint.MAX_LENGTH ->
        "That's the largest amount the keypad accepts."
}

/** Keypad keys: 1..9, 000, 0, backspace. */
internal sealed class KeypadKey {
    data class Digit(val d: Char) : KeypadKey()
    object TripleZero : KeypadKey()
    object Backspace : KeypadKey()
}

/** One reducer step: the (possibly unchanged) digits + why a key was refused. */
internal data class KeypadApplyResult(
    val digits: String,
    val refusedHint: KeypadInputPolicy.Hint?,
)

/**
 * Pure keypad-entry reducer (unit-tested): delegates to [KeypadInputPolicy]
 * (leading zeros ignored; anything past the 9-digit ceiling or the ₫5,000,000
 * VND per-payment cap is refused with a hint). Backspace always applies.
 */
internal fun applyKey(
    digits: String,
    key: KeypadKey,
    currencyNumeric: String?,
): KeypadApplyResult = when (key) {
    is KeypadKey.Digit ->
        KeypadInputPolicy.appendingDigit(digits, key.d, currencyNumeric).toResult(digits)
    KeypadKey.TripleZero ->
        KeypadInputPolicy.appendingTripleZero(digits, currencyNumeric).toResult(digits)
    KeypadKey.Backspace -> KeypadApplyResult(digits.dropLast(1), refusedHint = null)
}

private fun KeypadInputPolicy.Outcome.toResult(prior: String): KeypadApplyResult = when (this) {
    is KeypadInputPolicy.Outcome.Accepted -> KeypadApplyResult(digits, refusedHint = null)
    is KeypadInputPolicy.Outcome.Refused -> KeypadApplyResult(prior, refusedHint = hint)
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
                            .clickable(role = Role.Button) { onKey(key) }
                            .semantics { contentDescription = keyLabel(key) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (key) {
                            is KeypadKey.Digit -> Text(
                                text = key.d.toString(),
                                style = tabularStyle(30.sp, ZType.medium, palette.accent, hero = true),
                            )
                            KeypadKey.TripleZero -> Text(
                                text = "000",
                                style = tabularStyle(26.sp, ZType.medium, palette.accent, hero = true),
                            )
                            KeypadKey.Backspace -> Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null,
                                tint = palette.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun keyLabel(key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> key.d.toString()
    KeypadKey.TripleZero -> "triple zero"
    KeypadKey.Backspace -> "delete"
}
