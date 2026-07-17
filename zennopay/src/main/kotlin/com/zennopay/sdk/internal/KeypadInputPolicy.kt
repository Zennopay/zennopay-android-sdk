package com.zennopay.sdk.internal

/**
 * Pure input policy for the static-QR amount keypad, the Kotlin port of the iOS
 * SDK's `KeypadInputPolicy`. Keeps every rule unit-testable off the UI: leading
 * zeros are silently ignored, and any keypress that would push the amount past
 * the hard digit ceiling or the per-transaction VND cap is REFUSED (the UI
 * answers a refusal with a gentle shake + the limit copy — the hero can never
 * overflow).
 */
internal object KeypadInputPolicy {

    /**
     * Hard ceiling on entered major-unit digits regardless of currency
     * (999,999,999 major units) — keeps the hero legible at its
     * minimum-scale floor even for non-VND corridors with no client cap.
     */
    const val MAX_DIGITS = 9

    /** Outcome of applying one keypad key to the current digit string. */
    sealed class Outcome {
        /** The digits changed (or an ignorable no-op like a leading zero). */
        data class Accepted(val digits: String) : Outcome()

        /** The key would exceed a cap; digits unchanged. [hint] is the copy to surface. */
        data class Refused(val hint: Hint) : Outcome()
    }

    /** Why input was refused. */
    enum class Hint {
        /** Above the ₫5,000,000 per-payment cap (VND only). */
        VND_PER_TRANSACTION_LIMIT,

        /** Above the general digit ceiling. */
        MAX_LENGTH,
    }

    /** Apply a single digit ('0'–'9'). */
    fun appendingDigit(digits: String, d: Char, currencyNumeric: String?): Outcome {
        if (digits.isEmpty() && d == '0') return Outcome.Accepted(digits) // silent no-op
        return vet(candidate = digits + d, currencyNumeric = currencyNumeric)
    }

    /** Apply the "000" key. */
    fun appendingTripleZero(digits: String, currencyNumeric: String?): Outcome {
        if (digits.isEmpty()) return Outcome.Accepted(digits) // silent no-op
        return vet(candidate = digits + "000", currencyNumeric = currencyNumeric)
    }

    private fun vet(candidate: String, currencyNumeric: String?): Outcome {
        val major = candidate.toLongOrNull()
        if (candidate.length > MAX_DIGITS || major == null) {
            return Outcome.Refused(Hint.MAX_LENGTH)
        }
        // Major units → the backend's minor-unit convention (×100).
        val minorUnits = major * 100L
        if (DisbursementLimit.exceedsVndPerTransaction(minorUnits, currencyNumeric)) {
            return Outcome.Refused(Hint.VND_PER_TRANSACTION_LIMIT)
        }
        return Outcome.Accepted(candidate)
    }
}
