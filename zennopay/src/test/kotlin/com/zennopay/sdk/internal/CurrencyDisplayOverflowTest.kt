package com.zennopay.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defensive-overflow probes: the formatters must never crash or emit garbage
 * for absurd backend values (the QA matrix's ₫999,999,999 / ฿999,999.99 /
 * $99,999.99 cases and a near-Long.MAX_VALUE canary). The keypad cap prevents
 * USER-entered overflow; these guard against a hostile/buggy quote.
 */
class CurrencyDisplayOverflowTest {

    @Test
    fun `vnd 999,999,999 formats with grouping`() {
        assertEquals("₫999,999,999", CurrencyDisplay.formatMinor(99_999_999_900L, "704"))
        assertEquals(
            "999,999,999 VND",
            CurrencyDisplay.formatMinorWithLabel(99_999_999_900L, "704"),
        )
    }

    @Test
    fun `thb 999,999_99 formats with satang`() {
        assertEquals("฿999,999.99", CurrencyDisplay.formatMinor(99_999_999L, "764"))
    }

    @Test
    fun `usd 99,999_99 formats`() {
        assertEquals("$99,999.99", CurrencyDisplay.formatUsdCents(9_999_999L))
    }

    @Test
    fun `near Long MAX_VALUE does not crash any formatter`() {
        val huge = Long.MAX_VALUE - 7L
        for (currency in listOf("704", "764", "840", null, "999")) {
            val minor = CurrencyDisplay.formatMinor(huge, currency)
            val labeled = CurrencyDisplay.formatMinorWithLabel(huge, currency)
            assertNotNull(minor)
            assertNotNull(labeled)
            assertFalse("no scientific notation leaks to users", minor.contains("E"))
            assertFalse(labeled.contains("E"))
        }
        assertNotNull(CurrencyDisplay.formatUsdCents(huge))
        assertNotNull(
            CurrencyDisplay.exchangeRateLine(
                usdCents = huge, localMinorUnits = huge, localCurrency = "704",
            ),
        )
    }

    @Test
    fun `negative values do not crash`() {
        // Should never occur (backend-validated) but must not throw.
        assertNotNull(CurrencyDisplay.formatMinor(-1L, "704"))
        assertNotNull(CurrencyDisplay.formatUsdCents(Long.MIN_VALUE + 1))
    }

    @Test
    fun `grouped output keeps western thousands under huge magnitudes`() {
        val out = CurrencyDisplay.formatMinor(99_999_999_900L, "704")
        assertTrue(out.contains(","))
        assertFalse("no lakh grouping", out.contains(",99,"))
    }
}
