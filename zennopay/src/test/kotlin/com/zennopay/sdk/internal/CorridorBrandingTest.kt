package com.zennopay.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the iOS `CorridorBrandingTests`: the v1 registry entries (VN + TH),
 * case-insensitive lookup with null for unknown corridors (the UI hides the
 * row rather than guessing), and registry extensibility.
 */
class CorridorBrandingTest {

    @Test
    fun `vietnam entry carries the four scheme chips`() {
        val vn = CorridorBranding.entry("vn_vietqr")!!
        assertEquals("Vietnam", vn.countryName)
        assertEquals("VietQR", vn.schemeName)
        assertEquals(
            listOf("vietqr", "momo", "zalopay", "napas"),
            vn.chips.map { it.id },
        )
        // MoMo is the brand-colored stacked chip.
        val momo = vn.chips.first { it.id == "momo" }
        assertTrue(momo.stacked)
        assertEquals(0xFFA50064, momo.backgroundRgb)
    }

    @Test
    fun `thailand entry carries promptpay and truemoney`() {
        val th = CorridorBranding.entry("th_promptpay")!!
        assertEquals("Thailand", th.countryName)
        assertEquals("PromptPay", th.schemeName)
        assertEquals(listOf("promptpay", "truemoney"), th.chips.map { it.id })
    }

    @Test
    fun `lookup is case-insensitive and null for unknown`() {
        assertEquals(CorridorBranding.vietnam, CorridorBranding.entry("VN_VietQR"))
        assertNull(CorridorBranding.entry("ph_qrph"))
        assertNull(CorridorBranding.entry(null))
        assertNull(CorridorBranding.entry(""))
    }

    @Test
    fun `register extends the registry`() {
        val ph = CorridorBranding.Entry(
            corridor = "ph_qrph",
            countryName = "Philippines",
            schemeName = "QR Ph",
            chips = emptyList(),
            supportedQrHelp = "QR Ph merchant codes.",
        )
        CorridorBranding.register(ph)
        assertEquals(ph, CorridorBranding.entry("ph_qrph"))
    }
}
