package com.zennopay.sdk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Font-scale caps (iOS `ZTypeScale` parity): regular text stops growing at
 * ~1.65× and hero numerals at ~1.35×, so a 2.0× system font scale cannot push
 * the fixed chrome (slide, keypad, hero) off screen.
 */
class TypeScaleTest {

    @Test
    fun `within the cap nothing shrinks`() {
        assertEquals(1f, ZTypeScale.capFactor(1.0f, hero = false), 1e-5f)
        assertEquals(1f, ZTypeScale.capFactor(1.3f, hero = false), 1e-5f)
        assertEquals(1f, ZTypeScale.capFactor(1.0f, hero = true), 1e-5f)
        assertEquals(1f, ZTypeScale.capFactor(1.3f, hero = true), 1e-5f)
    }

    @Test
    fun `regular text caps at about 1_65x`() {
        val factor = ZTypeScale.capFactor(2.0f, hero = false)
        // Effective scale = 2.0 × factor = the cap.
        assertEquals(ZTypeScale.REGULAR_MAX_MULTIPLIER, 2.0f * factor, 1e-4f)
    }

    @Test
    fun `hero numerals cap earlier at about 1_35x`() {
        val factor = ZTypeScale.capFactor(2.0f, hero = true)
        assertEquals(ZTypeScale.HERO_MAX_MULTIPLIER, 2.0f * factor, 1e-4f)
    }

    @Test
    fun `hero cap is tighter than the regular cap`() {
        assert(ZTypeScale.HERO_MAX_MULTIPLIER < ZTypeScale.REGULAR_MAX_MULTIPLIER)
    }

    @Test
    fun `degenerate scales never divide by zero`() {
        assertEquals(1f, ZTypeScale.capFactor(0f, hero = false), 1e-5f)
        assertEquals(1f, ZTypeScale.capFactor(-1f, hero = true), 1e-5f)
    }

    @Test
    fun `caps match the iOS multipliers`() {
        assertEquals(28f / 17f, ZTypeScale.REGULAR_MAX_MULTIPLIER, 1e-5f)
        assertEquals(23f / 17f, ZTypeScale.HERO_MAX_MULTIPLIER, 1e-5f)
    }
}
