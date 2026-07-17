package com.zennopay.sdk.ui

/**
 * Font-scale caps for the themed screens — the Kotlin mirror of the iOS SDK's
 * `ZTypeScale`.
 *
 * Compose text sized in `sp` already follows the user's font-scale setting
 * (Settings > Display > Font size). The scale is CAPPED so accessibility sizes
 * stay usable without destroying the fixed-chrome layout (slide-to-pay, keypad,
 * hero + card must all remain reachable):
 *  - regular text caps at ~1.65× (the iOS accessibility-medium multiplier,
 *    28pt / 17pt body)
 *  - hero / display numerals cap earlier at ~1.35× (the iOS XXXL multiplier,
 *    23pt / 17pt) — they are already the largest thing on screen.
 *
 * Implementation: the renderer reads the system font scale from
 * `LocalDensity.current.fontScale` and, when it exceeds the cap, multiplies the
 * DESIGN.md sp size by `cap / fontScale` so the *effective* scale equals the
 * cap. Pure math lives here so it is unit-testable on the JVM.
 */
internal object ZTypeScale {

    /** Multiplier ceiling for regular text (iOS accessibility-medium body). */
    const val REGULAR_MAX_MULTIPLIER = 28f / 17f

    /** Multiplier ceiling for hero/display numerals (iOS XXXL body). */
    const val HERO_MAX_MULTIPLIER = 23f / 17f

    /**
     * The factor to apply to an `sp` size so the effective font scale is
     * clamped at [maxMultiplier]: 1.0 while the system scale is within the cap,
     * shrinking proportionally beyond it. Never boosts (returns <= 1).
     */
    fun capFactor(systemFontScale: Float, maxMultiplier: Float): Float {
        if (systemFontScale <= 0f) return 1f
        return if (systemFontScale <= maxMultiplier) 1f else maxMultiplier / systemFontScale
    }

    /** [capFactor] for a text role: hero numerals cap earlier than chrome text. */
    fun capFactor(systemFontScale: Float, hero: Boolean): Float =
        capFactor(
            systemFontScale,
            if (hero) HERO_MAX_MULTIPLIER else REGULAR_MAX_MULTIPLIER,
        )
}
