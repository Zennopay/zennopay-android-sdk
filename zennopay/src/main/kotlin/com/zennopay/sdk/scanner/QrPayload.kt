package com.zennopay.sdk.scanner

/**
 * A raw QR string captured on-device. The SDK NEVER interprets this for money
 * movement — it is submitted verbatim to `POST /v1/payment_intents/{id}/scan`,
 * which parses the EMVCo TLV authoritatively (D4=A). This class only does the
 * minimal, display-only sanity checks needed to decide whether a decoded frame
 * is worth submitting (avoid round-tripping obviously-junk frames).
 */
data class QrPayload(val raw: String) {

    /** Detected corridor family, display-only. Backend is authoritative. */
    enum class Format { PROMPTPAY_TH, VIETQR_VN, UNKNOWN }

    val format: Format by lazy { detectFormat(raw) }

    companion object {
        /** EMVCo payloads start with the Payload Format Indicator tag "00". */
        private const val EMV_PAYLOAD_FORMAT_INDICATOR = "00"

        /** Max sane length for a merchant QR; anything larger is rejected pre-submit. */
        const val MAX_LENGTH = 1024

        /**
         * Decide whether a decoded frame is plausibly an EMVCo merchant QR worth
         * submitting to the backend. This is a cheap gate, NOT validation:
         *   - non-blank
         *   - within a sane length bound
         *   - starts with the EMVCo Payload Format Indicator ("00" tag)
         *
         * Returns null for frames that are clearly not merchant QR (URLs, wifi
         * configs, vCards) so the scanner keeps looking instead of round-tripping.
         */
        fun fromDecoded(raw: String?): QrPayload? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.length > MAX_LENGTH) return null
            // EMVCo QR always begins with tag "00" + 2-digit length "01" + "01"/"02".
            if (!trimmed.startsWith(EMV_PAYLOAD_FORMAT_INDICATOR)) return null
            // Must be printable ASCII-ish; EMVCo is a numeric/alnum TLV string.
            if (trimmed.any { it.code < 0x20 }) return null
            return QrPayload(trimmed)
        }

        /**
         * Display-only format detection. TH PromptPay merchant QR carries the
         * PromptPay AID under Merchant Account Info tag 29/30; VietQR uses tag 38
         * with the "A000000727" (NAPAS) AID. We do a light substring sniff — the
         * backend does the authoritative TLV parse.
         */
        internal fun detectFormat(raw: String): Format {
            // NAPAS AID for VietQR.
            if (raw.contains("A000000727")) return Format.VIETQR_VN
            // PromptPay AID / country code TH (tag 58 = "TH").
            if (raw.contains("A000000677") || raw.contains("5802TH")) return Format.PROMPTPAY_TH
            return Format.UNKNOWN
        }

        /**
         * Corridor hint from the raw payload, for the scanner's corridor
         * branding row. A heuristic HINT only — the backend re-parses.
         * Returns `"th_promptpay"`, `"vn_vietqr"`, or null when undetermined.
         */
        fun corridorHint(raw: String): String? = when (detectFormat(raw)) {
            Format.PROMPTPAY_TH -> "th_promptpay"
            Format.VIETQR_VN -> "vn_vietqr"
            Format.UNKNOWN -> null
        }

        /** Well-known Vietnamese acquirer BINs (NAPAS). Display-only. */
        val vietnamBankNames: Map<String, String> = mapOf(
            "970436" to "VIETCOMBANK",
            "970415" to "VIETINBANK",
            "970418" to "BIDV",
            "970405" to "AGRIBANK",
            "970407" to "TECHCOMBANK",
            "970422" to "MB BANK",
            "970416" to "ACB",
            "970432" to "VPBANK",
            "970423" to "TPBANK",
            "970403" to "SACOMBANK",
        )

        /**
         * Peek display-only facts from a raw EMVCo payload. Returns a
         * best-effort result — a malformed TLV yields `Peek(isStatic = false)`
         * so the flow falls through to the authoritative backend scan.
         */
        fun peek(raw: String): Peek {
            val fields = parseTlv(raw.trim())
                ?: return Peek(isStatic = false, bankBin = null, accountNumber = null)
            val isStatic = fields["54"] == null
            // NAPAS VietQR merchant account template lives in tag 38:
            //   00 = AID (A000000727), 01 = nested { 00 = acquirer BIN, 01 = account }.
            var bin: String? = null
            var account: String? = null
            val napas = fields["38"]
            if (napas != null && napas.contains("A000000727")) {
                val sub = parseTlv(napas)
                val beneficiary = sub?.get("01")
                val inner = beneficiary?.let { parseTlv(it) }
                if (inner != null) {
                    bin = inner["00"]
                    account = inner["01"]
                }
            }
            return Peek(isStatic = isStatic, bankBin = bin, accountNumber = account)
        }

        /**
         * Minimal EMVCo TLV parse: repeated `tag(2) length(2) value(length)`.
         * Returns null when the string doesn't cleanly tokenize.
         */
        fun parseTlv(s: String): Map<String, String>? {
            val fields = mutableMapOf<String, String>()
            var idx = 0
            while (idx < s.length) {
                if (idx + 4 > s.length) return null
                val tag = s.substring(idx, idx + 2)
                val length = s.substring(idx + 2, idx + 4).toIntOrNull() ?: return null
                val valEnd = idx + 4 + length
                if (valEnd > s.length) return null
                fields[tag] = s.substring(idx + 4, valEnd)
                idx = valEnd
            }
            return fields.ifEmpty { null }
        }
    }

    /**
     * Display-only facts peeked from the raw EMVCo TLV (D4=A: NEVER trusted
     * for money movement). Used to (a) route a STATIC QR to the amount keypad
     * before the network scan, and (b) surface the beneficiary bank + masked
     * account on the review/receipt screens. The backend re-parses the raw
     * payload authoritatively on `/scan`.
     */
    data class Peek(
        /** Tag 54 (transaction amount) absent → static QR: keypad first. */
        val isStatic: Boolean,
        /** VietQR (NAPAS tag 38): the 6-digit acquirer BIN, e.g. "970436". */
        val bankBin: String?,
        /** VietQR: the beneficiary account/card number. */
        val accountNumber: String?,
    ) {
        /** Display name for [bankBin] when known (small client-side map). */
        val bankName: String?
            get() = bankBin?.let { vietnamBankNames[it] }

        /**
         * The account with all but the leading 5 / trailing 4 digits elided,
         * e.g. `10230…0000`. Null when no account was peeked.
         */
        val accountMasked: String?
            get() {
                val acct = accountNumber ?: return null
                if (acct.length <= 9) return acct
                return acct.take(5) + "…" + acct.takeLast(4)
            }
    }
}
