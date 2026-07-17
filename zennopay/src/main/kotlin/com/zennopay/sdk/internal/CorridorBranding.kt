package com.zennopay.sdk.internal

/**
 * Corridor-aware scanner branding: which country the user is paying into and
 * which scheme logos to surface under the reticle ("Look for these logos
 * before scanning"). Keyed by the backend corridor identifier carried in the
 * session JWT's `zennopay:corridor` claim (e.g. `vn_vietqr`, `th_promptpay`).
 *
 * The chips are STYLED TEXT approximations of the scheme wordmarks (VietQR
 * red/blue, MoMo pink square, ZaloPay blue, NAPAS red/blue) — we do NOT ship
 * trademark bitmaps. Rendering happens in the Compose layer; this registry is
 * pure data so it is unit-testable on the JVM and extensible as corridors
 * launch (add an [Entry] via [register]). Kotlin port of the iOS SDK's
 * `CorridorBranding.swift` — the two registries must stay in lockstep.
 */
internal object CorridorBranding {

    /**
     * A colored run of text inside a chip wordmark, e.g. ("Viet", red) +
     * ("QR", blue). Colors are packed 0xFFRRGGBB.
     */
    data class Segment(val text: String, val rgb: Long)

    /**
     * One scheme chip: a white (or brand-colored) rounded square with a
     * wordmark approximation.
     */
    data class SchemeChip(
        val id: String,
        /** Chip background, packed 0xFFRRGGBB. White for wordmark-on-light chips. */
        val backgroundRgb: Long = 0xFFFFFFFF,
        /** The wordmark runs. Rendered on one line, or stacked when [stacked]. */
        val segments: List<Segment>,
        /** Stack the segments vertically (MoMo's "mo / mo" block). */
        val stacked: Boolean = false,
    )

    /** The branding entry for one corridor. */
    data class Entry(
        /** Backend corridor id, e.g. "vn_vietqr". */
        val corridor: String,
        /** Destination-country display name ("Vietnam"). */
        val countryName: String,
        /** Human scheme label for captions ("VietQR"). */
        val schemeName: String,
        /** The logo chips to show under the reticle, in display order. */
        val chips: List<SchemeChip>,
        /** One-line help copy: which QRs this corridor accepts. */
        val supportedQrHelp: String,
    )

    // ---- Built-in corridors (v1 scope: VN + TH) ------------------------------

    val vietnam = Entry(
        corridor = "vn_vietqr",
        countryName = "Vietnam",
        schemeName = "VietQR",
        chips = listOf(
            SchemeChip(
                id = "vietqr",
                segments = listOf(
                    Segment("Viet", 0xFFDA251D),
                    Segment("QR", 0xFF00559F),
                ),
            ),
            SchemeChip(
                id = "momo",
                backgroundRgb = 0xFFA50064,
                segments = listOf(
                    Segment("mo", 0xFFFFFFFF),
                    Segment("mo", 0xFFFFFFFF),
                ),
                stacked = true,
            ),
            SchemeChip(
                id = "zalopay",
                segments = listOf(
                    Segment("Zalo", 0xFF0068FF),
                    Segment("pay", 0xFF00A85F),
                ),
                stacked = true,
            ),
            SchemeChip(
                id = "napas",
                segments = listOf(
                    Segment("na", 0xFFED1C24),
                    Segment("pas", 0xFF21409A),
                ),
            ),
        ),
        supportedQrHelp = "Vietnamese bank-transfer QRs on the NAPAS VietQR network — " +
            "including QRs shown in MoMo, ZaloPay, and bank apps.",
    )

    val thailand = Entry(
        corridor = "th_promptpay",
        countryName = "Thailand",
        schemeName = "PromptPay",
        chips = listOf(
            SchemeChip(
                id = "promptpay",
                segments = listOf(
                    Segment("Prompt", 0xFF113F67),
                    Segment("Pay", 0xFF1B9DD9),
                ),
                stacked = true,
            ),
            SchemeChip(
                id = "truemoney",
                segments = listOf(
                    Segment("True", 0xFFF05A22),
                    Segment("Money", 0xFF2B2B2B),
                ),
                stacked = true,
            ),
        ),
        supportedQrHelp = "Thai PromptPay merchant QRs — including QRs shown in " +
            "TrueMoney and Thai bank apps.",
    )

    /** The mutable registry. Seeded with the v1 corridors. */
    private val registry: MutableMap<String, Entry> = mutableMapOf(
        vietnam.corridor to vietnam,
        thailand.corridor to thailand,
    )

    /** Add or replace a corridor entry. */
    fun register(entry: Entry) {
        registry[entry.corridor] = entry
    }

    /**
     * Look up the branding for a corridor id (case-insensitive). Null when the
     * corridor is unknown — the UI hides the branding row rather than guessing.
     */
    fun entry(corridor: String?): Entry? {
        if (corridor.isNullOrEmpty()) return null
        return registry[corridor.lowercase()]
    }
}
