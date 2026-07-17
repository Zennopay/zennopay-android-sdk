package com.zennopay.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the iOS `CurrencyDisplayTests`: symbols/labels/flags keyed by
 * numeric ISO-4217, Western-thousands grouping (never lakhs), VND with no
 * fractional part, THB with satang, the receipt-hero label format, the implied
 * exchange-rate line, and the VND per-transaction disbursement cap.
 */
class CurrencyDisplayTest {

    @Test
    fun `symbols labels flags keyed by numeric ISO codes`() {
        assertEquals("฿", CurrencyDisplay.symbol("764"))
        assertEquals("₫", CurrencyDisplay.symbol("704"))
        assertEquals("$", CurrencyDisplay.symbol("840"))
        assertEquals("THB", CurrencyDisplay.label("764"))
        assertEquals("VND", CurrencyDisplay.label("704"))
        assertEquals("USD", CurrencyDisplay.label("840"))
        assertEquals("🇹🇭", CurrencyDisplay.flag("764"))
        assertEquals("🇻🇳", CurrencyDisplay.flag("704"))
        assertEquals("🇺🇸", CurrencyDisplay.flag("840"))
    }

    @Test
    fun `formatMinor VND has thousands separators and no decimals`() {
        // 3,500,000 VND arrives as 350,000,000 minor units (hundredths).
        assertEquals("₫3,500,000", CurrencyDisplay.formatMinor(350_000_000L, "704"))
        assertEquals("₫5,000,000", CurrencyDisplay.formatMinor(500_000_000L, "704"))
    }

    @Test
    fun `formatMinor THB keeps two decimal places`() {
        assertEquals("฿120.75", CurrencyDisplay.formatMinor(12_075L, "764"))
        assertEquals("฿1,120.00", CurrencyDisplay.formatMinor(112_000L, "764"))
    }

    @Test
    fun `formatMinorWithLabel is the receipt hero format`() {
        assertEquals("3,500,000 VND", CurrencyDisplay.formatMinorWithLabel(350_000_000L, "704"))
        assertEquals("120.75 THB", CurrencyDisplay.formatMinorWithLabel(12_075L, "764"))
    }

    @Test
    fun `formatUsdCents groups with two places`() {
        assertEquals("$140.00", CurrencyDisplay.formatUsdCents(14_000L))
        assertEquals("$1,140.25", CurrencyDisplay.formatUsdCents(114_025L))
    }

    @Test
    fun `exchangeRateLine implied from the quote`() {
        // $140.00 → ₫3,500,000 ⇒ 1 USD = 25,000.00 VND.
        assertEquals(
            "1 USD = 25,000.00 VND",
            CurrencyDisplay.exchangeRateLine(14_000L, 350_000_000L, "704"),
        )
        assertNull(CurrencyDisplay.exchangeRateLine(0L, 350_000_000L, "704"))
        assertNull(CurrencyDisplay.exchangeRateLine(14_000L, null, "704"))
    }

    @Test
    fun `disbursement limit VND per transaction boundary`() {
        assertFalse(
            DisbursementLimit.exceedsVndPerTransaction(500_000_000L, "704"),
        )
        assertTrue(
            DisbursementLimit.exceedsVndPerTransaction(500_000_001L, "704"),
        )
        // The cap only applies to VND.
        assertFalse(
            DisbursementLimit.exceedsVndPerTransaction(999_999_999L, "764"),
        )
    }
}
