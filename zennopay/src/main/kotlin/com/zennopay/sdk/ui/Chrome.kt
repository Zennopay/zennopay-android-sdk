package com.zennopay.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared sheet chrome for the themed checkout screens, the Compose mirror of
 * the iOS `SheetHeader` / `PrimaryButtonStyle` / `InlineError` components.
 */

/** Which glyph leads the header: a close X or a back chevron. */
internal enum class HeaderLeading { Close, Back }

/**
 * Header row used by the themed screens: leading close/back, centered title
 * (+ optional subtitle), optional trailing control.
 */
@Composable
internal fun SheetHeader(
    title: String,
    subtitle: String? = null,
    leading: HeaderLeading = HeaderLeading.Close,
    onLeading: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = ZColors.palette()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZSpace.xs),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = palette.text,
                style = tabularStyle(17.sp, ZType.medium, palette.text),
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = palette.text2,
                    style = tabularStyle(13.sp, ZType.regular, palette.text2),
                )
            }
        }
        IconButton(
            onClick = onLeading,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .semantics {
                    contentDescription = if (leading == HeaderLeading.Close) "Close" else "Back"
                },
        ) {
            Icon(
                imageVector = leadingIcon(leading),
                contentDescription = null,
                tint = palette.text,
            )
        }
        trailing?.let {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { it() }
        }
    }
}

private fun leadingIcon(leading: HeaderLeading): ImageVector = when (leading) {
    HeaderLeading.Close -> Icons.Filled.Close
    HeaderLeading.Back -> Icons.Filled.KeyboardArrowLeft
}

/**
 * The accent-fill primary action (Review / Done / Try again). Reads the partner
 * `appearance.primaryButton` (background, text color, clamped radius).
 */
@Composable
internal fun ZPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val appearance = LocalZennopayAppearance.current
    val button = appearance.primaryButton
    val background = Color(button.background)
    val textColor = Color(button.textColor)
    var m = modifier
        .fillMaxWidth()
        .height(52.dp)
        .background(
            if (enabled) background else background.copy(alpha = 0.4f),
            RoundedCornerShape(button.clampedCornerRadius),
        )
    if (testTag != null) m = m.testTag(testTag)
    TextButton(onClick = onClick, enabled = enabled, modifier = m) {
        Text(
            text = label,
            color = textColor,
            style = tabularStyle(16.sp, ZType.medium, textColor),
        )
    }
}

/** Plain secondary text action (Done under Try again, Cancel, etc.). */
@Composable
internal fun ZTextAction(label: String, onClick: () -> Unit) {
    val palette = ZColors.palette()
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = palette.text2,
            style = tabularStyle(16.sp, ZType.medium, palette.text2),
        )
    }
}

/** Inline recoverable-error banner (failure tint on the failure-soft halo). */
@Composable
internal fun InlineError(message: String, modifier: Modifier = Modifier) {
    val palette = ZColors.palette()
    val shapes = LocalZShapes.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.failureSoft, RoundedCornerShape(shapes.input))
            .padding(ZSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = palette.failure,
            style = tabularStyle(ZType.caption, ZType.regular, palette.failure),
        )
    }
}
