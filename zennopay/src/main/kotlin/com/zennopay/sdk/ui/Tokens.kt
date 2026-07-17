package com.zennopay.sdk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens transcribed from DESIGN.md. Kept as a Kotlin object rather than
 * Material theme overrides so every value is traceable to the doc and the
 * anti-slop rules (no color literals outside the palette, radius <= 12dp, no
 * gradients) are enforced by construction.
 *
 * The palette, radii, and typography are now DERIVED from a [ZennopayAppearance]
 * (threaded via [LocalZennopayAppearance]). `.Automatic` resolves to the
 * DESIGN.md tokens with system light/dark; explicit partner colors override
 * per-field and apply to both modes.
 *
 * "Felt as solid as a real bank" — subtractive, typography-led.
 */
internal object ZColors {
    // DESIGN.md light-mode tokens. These Longs also equal the ZennopayAppearance
    // .Colors() defaults, which is how the resolver detects "untouched" fields.
    private const val PRIMARY_L = 0xFF1B6B2F
    private const val BG_L = 0xFFFAFAF8
    private const val SURFACE_L = 0xFFFFFFFF
    private const val TEXT_L = 0xFF0A0F14
    private const val TEXT2_L = 0xFF5A6675
    // text3 tuned 2026-07-17 for WCAG AA on fine print (was 0xFF8A949F /
    // 0xFF6B7480, which measured 2.95:1 / 3.53:1 against surface): now ≥4.5:1
    // on both bg and surface in both modes while staying below text2 for
    // hierarchy. Kept in lockstep with the iOS `ZTokens.text3`.
    private const val TEXT3_L = 0xFF687280
    private const val BORDER_L = 0xFFE8E9EC
    private const val SUCCESS_L = 0xFF15803D
    private const val PENDING_L = 0xFF7C5E1A
    private const val FAILURE_L = 0xFFA53939

    // DESIGN.md dark-mode tokens (used when a field is untouched + dark mode).
    private val primaryDark = Color(0xFF4DA866)
    private val bgDark = Color(0xFF0F1217)
    private val surfaceDark = Color(0xFF1A1E25)
    private val textDark = Color(0xFFF0F2F4)
    private val text2Dark = Color(0xFFA0A8B3)
    private val text3Dark = Color(0xFF8A93A0)
    private val borderDark = Color(0xFF2A3038)
    private val successDark = Color(0xFF4DA866)
    private val pendingDark = Color(0xFFC9A24B)
    private val failureDark = Color(0xFFC26464)

    data class Palette(
        val bg: Color,
        val surface: Color,
        val text: Color,
        val text2: Color,
        val text3: Color,
        val border: Color,
        val accent: Color,
        val success: Color,
        val pending: Color,
        val failure: Color,
        val failureSoft: Color,
    )

    /**
     * Resolve a single color: if the partner left it at its DESIGN.md light
     * default, use the mode-appropriate DESIGN token (so untouched fields still
     * get a proper dark value); otherwise honor the override for both modes.
     */
    private fun pick(value: Long, defaultLight: Long, designDark: Color, dark: Boolean): Color =
        if (value == defaultLight) (if (dark) designDark else Color(value)) else Color(value)

    /** Derive the runtime palette from a [ZennopayAppearance] + effective mode. */
    fun resolve(appearance: ZennopayAppearance, dark: Boolean): Palette {
        val c = appearance.colors
        val failure = pick(c.failure, FAILURE_L, failureDark, dark)
        return Palette(
            bg = pick(c.background, BG_L, bgDark, dark),
            surface = pick(c.surface, SURFACE_L, surfaceDark, dark),
            text = pick(c.textPrimary, TEXT_L, textDark, dark),
            text2 = pick(c.textSecondary, TEXT2_L, text2Dark, dark),
            text3 = pick(c.textTertiary, TEXT3_L, text3Dark, dark),
            border = pick(c.border, BORDER_L, borderDark, dark),
            accent = pick(c.primary, PRIMARY_L, primaryDark, dark),
            success = pick(c.success, SUCCESS_L, successDark, dark),
            pending = pick(c.pending, PENDING_L, pendingDark, dark),
            failure = failure,
            // DESIGN.md: the failure halo is an 8% (light) / 12% (dark) tint of
            // the failure color — background only, never a text/surface fill.
            failureSoft = failure.copy(alpha = if (dark) 0.12f else 0.08f),
        )
    }

    @Composable
    @ReadOnlyComposable
    fun palette(): Palette {
        val appearance = LocalZennopayAppearance.current
        val dark = when (appearance.mode) {
            ZennopayAppearance.Mode.Light -> false
            ZennopayAppearance.Mode.Dark -> true
            ZennopayAppearance.Mode.Automatic -> isSystemInDarkTheme()
        }
        return resolve(appearance, dark)
    }
}

/** The active appearance, provided at the checkout root. */
internal val LocalZennopayAppearance =
    staticCompositionLocalOf { ZennopayAppearance.Automatic }

/** Clamped radii resolved from `appearance.shapes`, provided at the root. */
internal data class ZResolvedShapes(val input: Dp, val card: Dp, val slide: Dp)

internal val LocalZShapes = staticCompositionLocalOf {
    ZResolvedShapes(4.dp, 8.dp, 12.dp)
}

internal fun ZennopayAppearance.resolvedShapes(): ZResolvedShapes {
    val s = shapes.clamped()
    return ZResolvedShapes(s.input, s.card, s.slide)
}

/** The active typography (fontFamily + scale), provided at the root. */
internal val LocalZTypography =
    staticCompositionLocalOf { ZennopayAppearance.Typography() }

/** Spacing scale (4dp base) from DESIGN.md. */
internal object ZSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val xxxl = 64.dp
}

/**
 * Default border radius scale — NEVER > 12dp on rectangular surfaces. The live
 * radii come from [LocalZShapes] (partner-themable, clamped); these constants
 * are the DESIGN.md defaults and the fallback when no appearance is provided.
 */
internal object ZRadius {
    val input = 4.dp
    val card = 8.dp
    val slide = 12.dp
}

/** Type scale. General Sans is the intended face; falls back to platform sans. */
internal object ZType {
    val amount = 56.sp
    val heading = 24.sp
    val body = 16.sp
    val caption = 14.sp
    val micro = 12.sp

    val bold = FontWeight.Bold
    val medium = FontWeight.Medium
    val regular = FontWeight.Normal
}

/**
 * A numeric text style with mandatory `tabular-nums` (DESIGN.md: non-tabular
 * numerals are not acceptable on any amount / id / countdown). Applies the
 * partner font family + Dynamic-Type scale (capped at 1.5×).
 *
 * The SYSTEM font scale (sp) is additionally capped per [ZTypeScale] so
 * accessibility sizes stay usable without destroying the fixed-chrome layout:
 * regular text at ~1.65×, hero/display numerals ([hero] = true) at ~1.35×.
 */
@Composable
@ReadOnlyComposable
internal fun tabularStyle(
    size: TextUnit,
    weight: FontWeight,
    color: Color,
    hero: Boolean = false,
): TextStyle {
    val typo = LocalZTypography.current
    val scale = typo.scale.coerceIn(1f, 1.5f)
    val cap = ZTypeScale.capFactor(LocalDensity.current.fontScale, hero)
    return TextStyle(
        color = color,
        fontSize = size * scale * cap,
        fontWeight = weight,
        fontFamily = typo.fontFamily,
        fontFeatureSettings = "tnum",
    )
}
