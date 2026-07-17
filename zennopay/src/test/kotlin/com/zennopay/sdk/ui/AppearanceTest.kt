package com.zennopay.sdk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ZennopayAppearance guardrails (spec §5.2). The Appearance API lets partners
 * restyle WITHIN the DESIGN.md system; it cannot violate the anti-slop rules.
 * These assert the radius clamp (never > 12dp on rectangles) and that `.Automatic`
 * resolves to the DESIGN.md token defaults.
 */
class AppearanceTest {

    @Test
    fun `Automatic resolves to the DESIGN token defaults`() {
        val a = ZennopayAppearance.Automatic
        assertEquals(ZennopayAppearance.Mode.Automatic, a.mode)
        // Forest-green accent + warm near-white bg from DESIGN.md.
        assertEquals(0xFF1B6B2F, a.colors.primary)
        assertEquals(0xFFFAFAF8, a.colors.background)
        assertEquals(0xFFA53939, a.colors.failure)
        assertEquals(4.dp, a.shapes.input)
        assertEquals(8.dp, a.shapes.card)
        assertEquals(12.dp, a.shapes.slide)
        assertEquals(1f, a.typography.scale)
        assertNull(a.logo)
    }

    @Test
    fun `radii over 12dp are clamped to 12dp`() {
        val clamped = ZennopayAppearance.Shapes(input = 20.dp, card = 40.dp, slide = 999.dp).clamped()
        assertEquals(12.dp, clamped.input)
        assertEquals(12.dp, clamped.card)
        assertEquals(12.dp, clamped.slide)
    }

    @Test
    fun `radii under the ceiling pass through unchanged`() {
        val clamped = ZennopayAppearance.Shapes(input = 2.dp, card = 6.dp, slide = 10.dp).clamped()
        assertEquals(2.dp, clamped.input)
        assertEquals(6.dp, clamped.card)
        assertEquals(10.dp, clamped.slide)
    }

    @Test
    fun `primary button corner radius is clamped`() {
        assertEquals(12.dp, ZennopayAppearance.PrimaryButton(cornerRadius = 24.dp).clampedCornerRadius)
        assertEquals(8.dp, ZennopayAppearance.PrimaryButton().clampedCornerRadius)
    }

    @Test
    fun `resolve uses DESIGN dark token for an untouched field in dark mode`() {
        val light = ZColors.resolve(ZennopayAppearance.Automatic, dark = false)
        val dark = ZColors.resolve(ZennopayAppearance.Automatic, dark = true)
        // Untouched accent gets the lighter dark-mode green, not the light value.
        assertEquals(Color(0xFF1B6B2F), light.accent)
        assertEquals(Color(0xFF4DA866), dark.accent)
    }

    @Test
    fun `an explicit color override applies to both modes`() {
        val custom = ZennopayAppearance(colors = ZennopayAppearance.Colors(primary = 0xFF112233))
        val light = ZColors.resolve(custom, dark = false)
        val dark = ZColors.resolve(custom, dark = true)
        assertEquals(Color(0xFF112233), light.accent)
        assertEquals(Color(0xFF112233), dark.accent)
    }
}
