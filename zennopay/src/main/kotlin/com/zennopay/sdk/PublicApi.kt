package com.zennopay.sdk

/**
 * Terminal result of a native checkout, delivered to the host app via the
 * `onResult` callback passed to [Zennopay.presentCheckout]. Exactly one of
 * these is delivered, exactly once, per `presentCheckout` call.
 */
sealed class PaymentResult {
    /** The payment reached a terminal SUCCESS/CAPTURED state on the backend. */
    data class Completed(
        val intentId: String,
        /** Merchant display name from the validated scan, if known. */
        val merchantName: String?,
        /** Local amount paid, minor-unit-free display string (e.g. "120.00"). */
        val localAmount: String?,
        /** ISO-4217 local currency code (e.g. "THB"). */
        val localCurrency: String?,
        /** USD debited from the user's wallet, display string (e.g. "3.45"). */
        val usdDebited: String?,
        /** Backend transaction id, when surfaced. */
        val transactionId: String?,
        /**
         * Verifiable QR data for TH receipts. Null on
         * VN, which shows a static "Paid" stamp instead.
         */
        val verifiableQrData: String?,
    ) : PaymentResult()

    /** The payment reached a terminal FAILED state, or an unrecoverable error. */
    data class Failed(
        val intentId: String?,
        val error: ZennopayError,
    ) : PaymentResult()

    /**
     * The payment was confirmed but had not reached a terminal state when the
     * sheet closed — either the user chose to leave while it was processing
     * ("Done" on the processing screen) or status polling timed out. The
     * payment may still settle; the host should reconcile via webhook /
     * `GET /v1/payment_intents/:id`. If it does not complete, the money is
     * refunded to the wallet automatically.
     */
    data class Pending(val intentId: String) : PaymentResult()

    /** The user dismissed the flow before a terminal money state was reached. */
    data class Canceled(val intentId: String?) : PaymentResult()
}

/**
 * Configuration for the SDK. Injected via [Zennopay.configure] or passed to
 * [Zennopay.presentCheckout]. Mirrors Stripe's publishable-key ergonomic:
 * the environment is a VALUE, not a code path — one integration, and the
 * sandbox chrome + base URL follow from [environment].
 *
 * Use the [SANDBOX] / [PRODUCTION] companions for the canonical hosts, or
 * construct with a custom [apiBaseUrl] (+ `environment = Environment.CUSTOM`)
 * for local/pentest targets. Default is SANDBOX so a fresh integration never
 * accidentally points at production.
 */
data class ZennopayConfig(
    /** REST base, no trailing slash. Default: sandbox. */
    val apiBaseUrl: String = DEFAULT_SANDBOX_BASE_URL,
    /**
     * Which environment this config targets. Drives the base URL default and the
     * persistent SANDBOX affordance in the sheet header (present unless
     * [Environment.PRODUCTION]).
     */
    val environment: Environment = Environment.SANDBOX,
    /** Per-request network timeout. */
    val requestTimeoutMillis: Long = 20_000L,
) {
    /** True only for the production environment; gates the SANDBOX pill off. */
    val isProduction: Boolean get() = environment == Environment.PRODUCTION

    /**
     * SDK environment. Never hardcoded elsewhere; always derived from here.
     *
     * `STAGING` is a deprecated alias for [SANDBOX] retained for source
     * compatibility; new code should use [SANDBOX].
     */
    enum class Environment {
        SANDBOX,
        PRODUCTION,
        CUSTOM,

        @Deprecated("Use SANDBOX", ReplaceWith("Environment.SANDBOX"))
        STAGING,
    }

    companion object {
        /**
         * Sandbox REST base for the SDK-facing endpoints
         * (`/v1/payment_intents/...`). The environment partners integrate and
         * test against.
         */
        const val DEFAULT_SANDBOX_BASE_URL: String = "https://api.sandbox.zennopay.in"

        /** Production REST base. Selected by [PRODUCTION]. */
        const val DEFAULT_PRODUCTION_BASE_URL: String = "https://api.zennopay.in"

        /**
         * Deprecated alias for [DEFAULT_SANDBOX_BASE_URL]. Now points at the
         * sandbox host (`https://api.sandbox.zennopay.in`).
         */
        @Deprecated(
            "Use DEFAULT_SANDBOX_BASE_URL",
            ReplaceWith("ZennopayConfig.DEFAULT_SANDBOX_BASE_URL"),
        )
        const val DEFAULT_STAGING_BASE_URL: String = "https://api.sandbox.zennopay.in"

        /** Sandbox (default): sandbox host + SANDBOX pill. */
        val SANDBOX: ZennopayConfig =
            ZennopayConfig(DEFAULT_SANDBOX_BASE_URL, Environment.SANDBOX)

        /** Production: prod host, no SANDBOX pill. */
        val PRODUCTION: ZennopayConfig =
            ZennopayConfig(DEFAULT_PRODUCTION_BASE_URL, Environment.PRODUCTION)

        /**
         * Deprecated alias for [SANDBOX]. Retained so existing integrations keep
         * compiling; now resolves to the sandbox gateway
         * (`https://api.sandbox.zennopay.in`).
         */
        @Deprecated("Use SANDBOX", ReplaceWith("ZennopayConfig.SANDBOX"))
        val STAGING: ZennopayConfig = SANDBOX
    }
}
