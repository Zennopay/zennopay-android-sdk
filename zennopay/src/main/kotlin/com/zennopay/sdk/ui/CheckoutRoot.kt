package com.zennopay.sdk.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.internal.CheckoutController
import com.zennopay.sdk.internal.CheckoutState

/**
 * Root of the native checkout. Observes the platform-neutral [CheckoutController]
 * state and renders exactly one screen per state — the Compose mirror of the iOS
 * `CheckoutContainerView`. All money/network logic lives in the controller; this
 * is pure presentation + event forwarding.
 *
 * The partner [appearance] is resolved once here (palette/radii/typography) and
 * provided down via CompositionLocals so every screen reads the SAME themed
 * tokens. The scanner is always chrome-on-black (a camera surface); every other
 * screen uses the themed background. A persistent SANDBOX pill is overlaid
 * whenever [sandbox] is true.
 */
@Composable
internal fun CheckoutRoot(
    controller: CheckoutController,
    appearance: ZennopayAppearance = ZennopayAppearance.Automatic,
    sandbox: Boolean = false,
) {
    val shapes = remember(appearance) { appearance.resolvedShapes() }

    CompositionLocalProvider(
        LocalZennopayAppearance provides appearance,
        LocalZShapes provides shapes,
        LocalZTypography provides appearance.typography,
    ) {
        CheckoutRootContent(controller, sandbox)
    }
}

@Composable
private fun CheckoutRootContent(controller: CheckoutController, sandbox: Boolean) {
    val state by controller.state.collectAsStateWithLifecycle()
    val corridor by controller.corridor.collectAsStateWithLifecycle()
    val palette = ZColors.palette()
    // Honor reduced-motion (Settings > Accessibility > Remove animations) by
    // snapping instead of springing on the slide + entrance animations.
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    val onScanner = state is CheckoutState.Scanning ||
        (state as? CheckoutState.SubmittingScan)?.fromKeypad == false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (onScanner) Color.Black else palette.bg),
    ) {
        when (val s = state) {
            is CheckoutState.Scanning -> ScannerScreen(
                scanning = s,
                checking = false,
                corridor = corridor,
                onEvent = controller::dispatch,
            )
            is CheckoutState.SubmittingScan ->
                if (s.fromKeypad) {
                    QuoteLoadingScreen()
                } else {
                    // Keep the scanner chrome up with the "Checking…" pill.
                    ScannerScreen(
                        scanning = null,
                        checking = true,
                        corridor = corridor,
                        onEvent = controller::dispatch,
                    )
                }
            is CheckoutState.AmountEntry -> KeypadScreen(
                state = s,
                corridor = corridor,
                onEvent = controller::dispatch,
            )
            is CheckoutState.Quoted -> ReviewScreen(
                controller = controller,
                scan = s.scan,
                confirming = false,
                refreshingQuote = s.refreshingQuote,
                transientError = s.transientError?.let { humanMessage(it) },
                reducedMotion = reducedMotion,
                onEvent = controller::dispatch,
            )
            is CheckoutState.Confirming -> ReviewScreen(
                controller = controller,
                scan = s.scan,
                confirming = true,
                refreshingQuote = false,
                transientError = null,
                reducedMotion = reducedMotion,
                onEvent = controller::dispatch,
            )
            is CheckoutState.Processing -> ProcessingScreen(onEvent = controller::dispatch)
            is CheckoutState.Terminal -> when (val result = s.result) {
                is PaymentResult.Completed -> ReceiptScreen(
                    controller = controller,
                    reducedMotion = reducedMotion,
                    onEvent = controller::dispatch,
                )
                is PaymentResult.Failed -> FailureScreen(
                    controller = controller,
                    error = result.error,
                    onEvent = controller::dispatch,
                )
                is PaymentResult.Pending -> PendingDetailScreen(
                    controller = controller,
                    onEvent = controller::dispatch,
                )
                is PaymentResult.Canceled -> {
                    // No UI — the controller already delivered Canceled to the
                    // host, which tears the activity down.
                }
            }
        }

        if (sandbox && !onScanner) {
            SandboxPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(ZSpace.md),
            )
        }
    }
}

/**
 * Persistent, unobtrusive SANDBOX affordance (spec §6). text-3, hairline,
 * pinned to the header corner so it never covers the amount. Absent in
 * production (the caller only renders this when environment != production).
 */
@Composable
private fun SandboxPill(modifier: Modifier = Modifier) {
    val palette = ZColors.palette()
    Text(
        text = "SANDBOX",
        color = palette.text3,
        style = tabularStyle(ZType.micro, ZType.medium, palette.text3),
        modifier = modifier
            .border(1.dp, palette.border, RoundedCornerShape(ZRadius.card))
            .padding(horizontal = ZSpace.sm, vertical = ZSpace.xs),
    )
}
