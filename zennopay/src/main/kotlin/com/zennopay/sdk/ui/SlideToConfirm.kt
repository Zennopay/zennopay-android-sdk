package com.zennopay.sdk.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Slide-to-pay per the partner-approved reference (the Compose mirror of the
 * iOS `SlideToConfirm`): a filled accent pill track with a round white knob
 * carrying the accent chevron. Dragging past ~90% fires [onConfirm] exactly
 * once (the controller also guards single-fire); while [confirming] the knob
 * pins to the track end and becomes a spinner. Reduced-motion snaps instead of
 * springing back — "metal latch" mass-spring decay otherwise (DESIGN.md).
 */
@Composable
internal fun SlideToConfirm(
    label: String,
    enabled: Boolean = true,
    confirming: Boolean = false,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
) {
    val appearance = LocalZennopayAppearance.current
    val palette = ZColors.palette()
    val density = LocalDensity.current
    val trackHeight = 68.dp
    val knobSize = 56.dp
    val inset = 6.dp

    val trackColor =
        if (enabled) Color(appearance.primaryButton.background)
        else palette.text3.copy(alpha = 0.4f)
    val labelColor = Color(appearance.primaryButton.textColor)

    var fired by remember { mutableStateOf(false) }
    val spinning = confirming || fired
    LaunchedEffect(confirming) {
        if (!confirming) fired = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .alpha(if (enabled) 1f else 0.6f)
            // The slide track is the one sanctioned pill surface (reference).
            .background(trackColor, CircleShape)
            .testTag("zp.slide.track")
            // Sliding is not TalkBack-operable: the control is exposed as a
            // button whose activation (double-tap) confirms without the drag —
            // the iOS custom-accessibility-action mirror.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (spinning) "Processing payment" else label
                if (!enabled || spinning) {
                    disabled()
                } else {
                    onClick(label = "Confirm the payment") {
                        if (!fired) {
                            fired = true
                            onConfirm()
                        }
                        true
                    }
                }
            },
    ) {
        val insetPx = with(density) { inset.toPx() }
        val knobPx = with(density) { knobSize.toPx() }
        val trackWidthPx = with(density) { maxWidth.toPx() }
        val maxOffset = (trackWidthPx - knobPx - insetPx * 2).coerceAtLeast(0f)
        val commitThreshold = maxOffset * 0.9f

        val offset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()

        // Pin the knob to the end while the confirm+poll runs; snap home when
        // the control re-enables after a failed confirm ("Try again" path).
        LaunchedEffect(spinning, enabled) {
            if (spinning) offset.snapTo(maxOffset)
            else if (!enabled) offset.snapTo(0f)
        }

        if (!spinning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    color = labelColor,
                    style = tabularStyle(17.sp, ZType.medium, labelColor),
                    modifier = Modifier.alpha(
                        (1f - offset.value / commitThreshold.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offsetXpx { (insetPx + offset.value).roundToInt() }
                .size(knobSize)
                .background(Color.White, CircleShape)
                .testTag("zp.slide.handle")
                .pointerInput(enabled, spinning) {
                    if (!enabled || spinning) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (fired) return@detectHorizontalDragGestures
                            if (offset.value >= commitThreshold) {
                                fired = true
                                scope.launch { offset.snapTo(maxOffset) }
                                onConfirm()
                            } else {
                                scope.launch {
                                    if (reducedMotion) {
                                        offset.snapTo(0f)
                                    } else {
                                        offset.animateTo(
                                            0f,
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow,
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                    ) { _, dragAmount ->
                        if (fired) return@detectHorizontalDragGestures
                        scope.launch {
                            offset.snapTo((offset.value + dragAmount).coerceIn(0f, maxOffset))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (spinning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = palette.accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    // The merged track semantics carry the label; a second
                    // description here would double-announce.
                    contentDescription = null,
                    tint = palette.accent,
                )
            }
        }
    }
}

/** Applies an integer horizontal pixel offset without allocating IntOffset callers. */
private fun Modifier.offsetXpx(x: () -> Int): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(x(), 0)
            }
        },
    )
