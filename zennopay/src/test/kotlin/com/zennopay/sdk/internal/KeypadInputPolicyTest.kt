package com.zennopay.sdk.internal

import com.zennopay.sdk.internal.KeypadInputPolicy.Hint
import com.zennopay.sdk.internal.KeypadInputPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keypad input policy (iOS `KeypadInputPolicy` parity): leading zeros are
 * silently ignored, and any keypress that would push the amount past the
 * 9-digit ceiling or the ₫5,000,000 per-transaction VND cap is REFUSED with
 * the right hint — so the hero can never overflow and the user is told why.
 */
class KeypadInputPolicyTest {

    private val vnd = "704"
    private val thb = "764"

    // ---- Leading zeros -------------------------------------------------------

    @Test
    fun `leading zero on empty digits is a silent no-op`() {
        assertEquals(
            Outcome.Accepted(""),
            KeypadInputPolicy.appendingDigit("", '0', vnd),
        )
    }

    @Test
    fun `triple zero on empty digits is a silent no-op`() {
        assertEquals(
            Outcome.Accepted(""),
            KeypadInputPolicy.appendingTripleZero("", vnd),
        )
    }

    @Test
    fun `zero after digits is accepted`() {
        assertEquals(
            Outcome.Accepted("10"),
            KeypadInputPolicy.appendingDigit("1", '0', vnd),
        )
    }

    // ---- VND per-transaction cap (₫5,000,000) --------------------------------

    @Test
    fun `amount at exactly 5M VND is accepted`() {
        assertEquals(
            Outcome.Accepted("5000000"),
            KeypadInputPolicy.appendingDigit("500000", '0', vnd),
        )
    }

    @Test
    fun `digit pushing past 5M VND is refused with the limit hint`() {
        assertEquals(
            Outcome.Refused(Hint.VND_PER_TRANSACTION_LIMIT),
            KeypadInputPolicy.appendingDigit("5000000", '1', vnd),
        )
    }

    @Test
    fun `triple zero pushing past 5M VND is refused with the limit hint`() {
        assertEquals(
            Outcome.Refused(Hint.VND_PER_TRANSACTION_LIMIT),
            KeypadInputPolicy.appendingTripleZero("500000", vnd),
        )
    }

    @Test
    fun `4,999,999 VND stays under the cap`() {
        assertEquals(
            Outcome.Accepted("4999999"),
            KeypadInputPolicy.appendingDigit("499999", '9', vnd),
        )
    }

    // ---- The general digit ceiling -------------------------------------------

    @Test
    fun `THB has no VND cap but hits the 9-digit ceiling`() {
        // 999,999,999 THB major units is fine to type…
        assertEquals(
            Outcome.Accepted("999999999"),
            KeypadInputPolicy.appendingDigit("99999999", '9', thb),
        )
        // …but a tenth digit is refused with the length hint.
        assertEquals(
            Outcome.Refused(Hint.MAX_LENGTH),
            KeypadInputPolicy.appendingDigit("999999999", '9', thb),
        )
    }

    @Test
    fun `triple zero past the ceiling is refused for THB`() {
        assertEquals(
            Outcome.Refused(Hint.MAX_LENGTH),
            KeypadInputPolicy.appendingTripleZero("99999999", thb),
        )
    }

    @Test
    fun `unknown currency gets the digit ceiling, not the VND cap`() {
        assertEquals(
            Outcome.Accepted("999999999"),
            KeypadInputPolicy.appendingDigit("99999999", '9', null),
        )
        assertEquals(
            Outcome.Refused(Hint.MAX_LENGTH),
            KeypadInputPolicy.appendingDigit("999999999", '1', null),
        )
    }

    @Test
    fun `VND cap fires before the digit ceiling`() {
        // 8 digits would be within MAX_DIGITS, but over ₫5M.
        assertEquals(
            Outcome.Refused(Hint.VND_PER_TRANSACTION_LIMIT),
            KeypadInputPolicy.appendingDigit("9999999", '9', vnd),
        )
    }
}
