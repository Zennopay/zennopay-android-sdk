package com.zennopay.sdk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The static-QR keypad's pure entry reducer: digit budget, no leading zero,
 * "000" only after digits exist, backspace.
 */
class KeypadReducerTest {

    @Test
    fun `digits append and backspace removes`() {
        var d = ""
        d = applyKey(d, KeypadKey.Digit('3'))
        d = applyKey(d, KeypadKey.Digit('5'))
        assertEquals("35", d)
        d = applyKey(d, KeypadKey.Backspace)
        assertEquals("3", d)
        d = applyKey(d, KeypadKey.Backspace)
        assertEquals("", d)
        // Backspace on empty is a no-op.
        assertEquals("", applyKey("", KeypadKey.Backspace))
    }

    @Test
    fun `leading zero is rejected`() {
        assertEquals("", applyKey("", KeypadKey.Digit('0')))
        assertEquals("10", applyKey("1", KeypadKey.Digit('0')))
    }

    @Test
    fun `triple zero requires existing digits and respects the budget`() {
        assertEquals("", applyKey("", KeypadKey.TripleZero))
        assertEquals("35000", applyKey("35", KeypadKey.TripleZero))
        // At 8+ digits a 000 would blow the 10-digit budget: rejected.
        assertEquals("12345678", applyKey("12345678", KeypadKey.TripleZero))
    }

    @Test
    fun `digit budget caps at ten`() {
        val ten = "1234567890"
        assertEquals(ten, applyKey(ten, KeypadKey.Digit('1')))
    }
}
