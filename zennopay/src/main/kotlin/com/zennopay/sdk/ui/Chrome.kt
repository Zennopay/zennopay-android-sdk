package com.zennopay.sdk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zennopay.sdk.R

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
                .size(48.dp)
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
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(
            text = label,
            color = palette.text2,
            style = tabularStyle(16.sp, ZType.medium, palette.text2),
        )
    }
}

/**
 * Trust footer rendered at the bottom of EVERY PaymentSheet screen (container
 * level, `CheckoutRoot`): "Powered by" + the Zennopay wordmark. Deliberately
 * NOT part of the partner appearance API — the payment surface is always
 * visibly Zennopay-operated. The wordmark asset has a light-surface (dark
 * text) and dark-surface (white text) variant; [darkSurface] (the scanner's
 * camera surface) forces the dark variant, and the app's dark theme selects it
 * elsewhere. TalkBack reads the row as one element: "Powered by Zennopay".
 */
@Composable
internal fun PoweredByZennopay(
    darkSurface: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = ZColors.palette()
    val appearance = LocalZennopayAppearance.current
    val darkTheme = when (appearance.mode) {
        ZennopayAppearance.Mode.Light -> false
        ZennopayAppearance.Mode.Dark -> true
        ZennopayAppearance.Mode.Automatic -> isSystemInDarkTheme()
    }
    val wordmark =
        if (darkSurface || darkTheme) R.drawable.zp_powered_dark else R.drawable.zp_powered_light
    val captionColor = if (darkSurface) Color.White.copy(alpha = 0.55f) else palette.text3

    Row(
        modifier = modifier.clearAndSetSemantics {
            testTag = "zp.poweredBy"
            contentDescription = "Powered by Zennopay"
        },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Powered by",
            color = captionColor,
            style = tabularStyle(13.sp, ZType.regular, captionColor),
        )
        Image(
            painter = painterResource(wordmark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // Pre-trimmed 284×73 artwork rendered ~18dp tall (iOS parity).
            modifier = Modifier.height(18.dp).width(70.dp),
        )
    }
}

/**
 * Single-line text that shrinks to fit its width instead of clipping — the
 * Compose mirror of the iOS heroes' `minimumScaleFactor`. Used on the amount
 * heroes so a defensive-overflow value (₫999,999,999) stays legible on a small
 * phone at large font scales. Steps the font down until the line fits, flooring
 * at [minScale] of the requested size.
 */
@Composable
internal fun AutoShrinkText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    minScale: Float = 0.4f,
) {
    var scale by remember(text, style.fontSize) { mutableFloatStateOf(1f) }
    Text(
        text = text,
        style = style.copy(fontSize = style.fontSize * scale),
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && scale > minScale) {
                scale = (scale - 0.05f).coerceAtLeast(minScale)
            }
        },
        modifier = modifier,
    )
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
