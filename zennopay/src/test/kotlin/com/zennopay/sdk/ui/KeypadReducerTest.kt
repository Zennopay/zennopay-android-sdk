package com.zennopay.sdk.ui

import com.zennopay.sdk.internal.KeypadInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The static-QR keypad's pure entry reducer, now wired through
 * [KeypadInputPolicy] (iOS parity): digit ceiling + ₫5,000,000 VND cap with
 * refusal hints, no leading zero, "000" only after digits exist, backspace.
 */
class KeypadReducerTest {

    private val vnd = "704"
    private val thb = "764"

    @Test
    fun `digits append and backspace removes`() {
        var d = ""
        d = applyKey(d, KeypadKey.Digit('3'), vnd).digits
        d = applyKey(d, KeypadKey.Digit('5'), vnd).digits
        assertEquals("35", d)
        d = applyKey(d, KeypadKey.Backspace, vnd).digits
        assertEquals("3", d)
        d = applyKey(d, KeypadKey.Backspace, vnd).digits
        assertEquals("", d)
        // Backspace on empty is a no-op.
        assertEquals("", applyKey("", KeypadKey.Backspace, vnd).digits)
    }

    @Test
    fun `leading zero is rejected silently`() {
        val r = applyKey("", KeypadKey.Digit('0'), vnd)
        assertEquals("", r.digits)
        assertNull("a silent no-op is not a refusal", r.refusedHint)
        assertEquals("10", applyKey("1", KeypadKey.Digit('0'), vnd).digits)
    }

    @Test
    fun `triple zero requires existing digits`() {
        val r = applyKey("", KeypadKey.TripleZero, vnd)
        assertEquals("", r.digits)
        assertNull(r.refusedHint)
        assertEquals("35000", applyKey("35", KeypadKey.TripleZero, vnd).digits)
    }

    @Test
    fun `over-limit VND key is refused with the limit hint and unchanged digits`() {
        val r = applyKey("5000000", KeypadKey.Digit('0'), vnd)
        assertEquals("5000000", r.digits)
        assertEquals(KeypadInputPolicy.Hint.VND_PER_TRANSACTION_LIMIT, r.refusedHint)
    }

    @Test
    fun `triple zero past the VND cap is refused`() {
        val r = applyKey("500000", KeypadKey.TripleZero, vnd)
        assertEquals("500000", r.digits)
        assertEquals(KeypadInputPolicy.Hint.VND_PER_TRANSACTION_LIMIT, r.refusedHint)
    }

    @Test
    fun `THB hits the 9-digit ceiling with the length hint`() {
        assertEquals("999999999", applyKey("99999999", KeypadKey.Digit('9'), thb).digits)
        val r = applyKey("999999999", KeypadKey.Digit('9'), thb)
        assertEquals("999999999", r.digits)
        assertEquals(KeypadInputPolicy.Hint.MAX_LENGTH, r.refusedHint)
    }

    @Test
    fun `backspace clears any hint`() {
        val r = applyKey("5000000", KeypadKey.Backspace, vnd)
        assertEquals("500000", r.digits)
        assertNull(r.refusedHint)
    }

    @Test
    fun `mashing keys at the cap never moves the amount past 5M VND`() {
        var digits = "5000000"
        for (key in listOf(
            KeypadKey.Digit('9'), KeypadKey.TripleZero, KeypadKey.Digit('0'),
        )) {
            digits = applyKey(digits, key, vnd).digits
        }
        assertEquals("5000000", digits)
    }
}
