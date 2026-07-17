package com.zennopay.sdk.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Partner-facing theming for the PaymentSheet (mirrors Stripe's
 * `PaymentSheet.Appearance`). A partner sets colors, corner radius, font, logo,
 * and light/dark so the sheet reads as part of their app while still honoring
 * DESIGN.md's structural rules.
 *
 * Guardrails (anti-slop, non-overridable):
 *  - Rectangular corner radii are clamped to [MAX_RADIUS] (12dp) — a value
 *    outside the allowed range is quietly clamped, never applied.
 *  - The accent/primary is a STATE signal only; gradients are impossible here
 *    (colors are flat Longs).
 *
 * The default [Automatic] resolves to the DESIGN.md tokens with system
 * light/dark — a partner who passes nothing gets the bank-solid Zennopay look.
 * Default color Longs below intentionally equal the DESIGN.md LIGHT tokens so
 * the resolver can tell "untouched" fields (which get the proper dark token in
 * dark mode) from partner overrides (which apply to both modes).
 */
data class ZennopayAppearance(
    val mode: Mode = Mode.Automatic,
    val colors: Colors = Colors(),
    val shapes: Shapes = Shapes(),
    val typography: Typography = Typography(),
    val primaryButton: PrimaryButton = PrimaryButton(),
    @DrawableRes val logo: Int? = null,
) {
    enum class Mode { Automatic, Light, Dark }

    data class Colors(
        val primary: Long = 0xFF1B6B2F,
        val background: Long = 0xFFFAFAF8,
        val surface: Long = 0xFFFFFFFF,
        val textPrimary: Long = 0xFF0A0F14,
        val textSecondary: Long = 0xFF5A6675,
        val textTertiary: Long = 0xFF8A949F,
        val border: Long = 0xFFE8E9EC,
        val success: Long = 0xFF15803D,
        val pending: Long = 0xFF7C5E1A,
        val failure: Long = 0xFFA53939,
        /* darkOverrides: Colors? = null — deferred; a single value applies to both modes. */
    )

    data class Shapes(
        val input: Dp = 4.dp,
        val card: Dp = 8.dp,
        val slide: Dp = 12.dp,
    ) {
        /**
         * The radii actually applied: each clamped into the DESIGN.md-legal
         * range — capped at [MAX_RADIUS], floored at 0 — so a partner can never
         * push a rectangular surface past the ceiling (or pass a negative).
         */
        fun clamped(): Shapes = Shapes(
            input = input.coerceIn(MIN_RADIUS, MAX_RADIUS),
            card = card.coerceIn(MIN_RADIUS, MAX_RADIUS),
            slide = slide.coerceIn(MIN_RADIUS, MAX_RADIUS),
        )
    }

    data class Typography(
        /** Default null → the platform sans (General Sans when bundled by the host). */
        val fontFamily: FontFamily? = null,
        /** Honors Dynamic Type up to 1.5×; clamped by the renderer. */
        val scale: Float = 1f,
    )

    data class PrimaryButton(
        val background: Long = 0xFF1B6B2F,
        val textColor: Long = 0xFFFFFFFF,
        val cornerRadius: Dp = 8.dp,
    ) {
        /** Corner radius clamped into the DESIGN.md-legal range (0..12dp). */
        val clampedCornerRadius: Dp get() = cornerRadius.coerceIn(MIN_RADIUS, MAX_RADIUS)
    }

    companion object {
        /** Anti-slop guardrail: rectangular corner radii never exceed 12dp. */
        val MAX_RADIUS: Dp = 12.dp

        /** Radii floor: a negative radius is floored at 0. */
        val MIN_RADIUS: Dp = 0.dp

        /** The default Zennopay look: DESIGN.md tokens, follows system light/dark. */
        val Automatic: ZennopayAppearance = ZennopayAppearance()

        /**
         * Alias for [Automatic] (Stripe-style spelling), mirroring the iOS
         * SDK's `ZennopayAppearance.default`. A partner who passes nothing
         * gets the bank-solid Zennopay appearance.
         */
        val Default: ZennopayAppearance = Automatic
    }
}
