package com.zennopay.sdk.internal.net

import com.zennopay.sdk.ZennopayConfig
import com.zennopay.sdk.ZennopayError
import com.zennopay.sdk.internal.ErrorTaxonomy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Outcome of a REST call: either a parsed body or a typed error. Kept as a
 * sealed result (not exceptions) so the state machine can branch cleanly.
 */
internal sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val error: ZennopayError) : ApiResult<Nothing>()
}

/**
 * REST client for the SDK-facing surface (T-SDK-REST-CONTRACT):
 *   POST /v1/payment_intents/{id}/scan
 *   POST /v1/payment_intents/{id}/confirm
 *   GET  /v1/payment_intents/{id}
 *
 * Responsibilities:
 *  - Holds the session JWT IN MEMORY and sends it as `Authorization: Bearer`.
 *    Never placed in a URL (can't leak via history/Referer/logs).
 *  - On 401/expiry calls the host `refreshSession` hook (D3=A) once, swaps in the
 *    fresh JWT, and retries the request exactly once.
 *  - Bounded status polling with backoff.
 *  - Maps backend error codes to the typed taxonomy.
 *
 * Session-JWT single-use (D2=B): the backend burns the jti on `/confirm` ONLY.
 * `scan` and `GET /:id` can be called repeatedly on one session, so the SDK's
 * read/quote calls don't consume the money call's one-shot guarantee.
 */
internal class ZennopayRestClient(
    private val config: ZennopayConfig,
    private val transport: HttpTransport,
    initialSessionJwt: String,
    /**
     * Host-provided re-mint hook. Given the intentId, returns a fresh session
     * JWT for the SAME intent (preserving quote/QR/confirm-idempotency state on
     * the backend) or null if it cannot refresh.
     */
    private val refreshSession: (suspend (String) -> String?)?,
    /**
     * Dispatcher the blocking HttpURLConnection I/O runs on. Defaults to
     * [Dispatchers.IO] in production; tests inject the test scheduler's
     * dispatcher so `runTest` virtual time controls the network hop.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var sessionJwt: String = initialSessionJwt

    /** Serializes refreshes so a burst of 401s triggers at most one re-mint. */
    private val refreshMutex = Mutex()

    fun currentJwt(): String = sessionJwt

    // ---- Endpoints -----------------------------------------------------------

    /**
     * `POST /v1/payment_intents/{id}/scan` — submit the RAW decoded QR string.
     *
     * Canonical body (docs/sdk-rest-contract.md): `{ "qr_payload": <raw EMVCo>,
     * "local_amount_minor_units": <int, OPTIONAL — REQUIRED for static QR> }`.
     * [localAmountMinorUnits] is sent only when the caller supplies it (a
     * user-entered amount for a static QR); a dynamic QR omits it.
     */
    suspend fun scan(
        intentId: String,
        rawQr: String,
        localAmountMinorUnits: Long? = null,
    ): ApiResult<ScanResult> {
        val body = JSONObject().apply {
            put("qr_payload", rawQr)
            if (localAmountMinorUnits != null) {
                put("local_amount_minor_units", localAmountMinorUnits)
            }
        }.toString()
        return request(intentId, "POST", "$intentId/scan", body) { json ->
            ScanResult.parse(json)
        }
    }

    /**
     * `POST /v1/payment_intents/{id}/confirm` — fires the wallet debit + payout.
     * Idempotency-keyed: the same [idempotencyKey] is safe to retry (server-side
     * single-flight guarantees one debit + one payout per intent, D5=A).
     * The session jti is BURNED here (D2=B), so a second confirm with a stale
     * jti fails as [ZennopayError.JtiReplay] — but a refreshed session for the
     * same intent + same key resumes cleanly.
     */
    suspend fun confirm(
        intentId: String,
        idempotencyKey: String,
        quoteId: String?,
        quoteVersion: Int?,
    ): ApiResult<IntentStatus> {
        // Canonical confirm body (docs/sdk-rest-contract.md): { quote_id,
        // quote_version } — binds to the FX quote produced at scan. No amount is
        // sent (dynamic amount is immutable; static amount was bound at scan).
        val body = JSONObject().apply {
            if (quoteId != null) put("quote_id", quoteId)
            if (quoteVersion != null) put("quote_version", quoteVersion)
        }.toString()
        return request(
            intentId = intentId,
            method = "POST",
            path = "$intentId/confirm",
            body = body,
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey),
        ) { json -> IntentStatus.parse(intentId, json) }
    }

    /** `GET /v1/payment_intents/{id}` — single status read. */
    suspend fun getStatus(intentId: String): ApiResult<IntentStatus> =
        request(intentId, "GET", intentId, null) { json ->
            IntentStatus.parse(intentId, json)
        }

    /**
     * Bounded status polling with linear-then-capped backoff. Polls until the
     * intent reaches a terminal state or the budget is exhausted, at which point
     * it returns [ZennopayError.PollingTimeout]. Transient network errors during
     * polling are tolerated up to [maxTransientErrors] before giving up.
     */
    suspend fun pollUntilTerminal(
        intentId: String,
        maxAttempts: Int = 40,
        initialDelayMillis: Long = 1_000L,
        maxDelayMillis: Long = 3_000L,
        maxTransientErrors: Int = 5,
    ): ApiResult<IntentStatus> {
        var transientErrors = 0
        var delayMillis = initialDelayMillis
        repeat(maxAttempts) {
            when (val r = getStatus(intentId)) {
                is ApiResult.Ok -> {
                    if (r.value.status.terminal) return r
                    transientErrors = 0
                }
                is ApiResult.Err -> {
                    // Auth/state errors are fatal; network blips are tolerated.
                    if (r.error is ZennopayError.NetworkError) {
                        if (++transientErrors > maxTransientErrors) return r
                    } else {
                        return r
                    }
                }
            }
            delay(delayMillis)
            delayMillis = (delayMillis + 500L).coerceAtMost(maxDelayMillis)
        }
        return ApiResult.Err(ZennopayError.PollingTimeout)
    }

    // ---- Core request with refresh-on-401-retry ------------------------------

    private suspend fun <T> request(
        intentId: String,
        method: String,
        path: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        parse: (JSONObject) -> T,
    ): ApiResult<T> {
        val url = "${config.apiBaseUrl.trimEnd('/')}/v1/payment_intents/$path"

        val first = send(method, url, body, extraHeaders)
        val firstResult = interpret(first, parse)
        if (!needsRefresh(firstResult, first)) return firstResult

        // 401 / auth error: try exactly one host refresh, then one retry.
        val refreshed = tryRefresh(intentId)
            ?: return ApiResult.Err(ZennopayError.SessionRefreshFailed)
        sessionJwt = refreshed

        val second = send(method, url, body, extraHeaders)
        val secondResult = interpret(second, parse)
        // If still unauthorized after refresh, surface Unauthorized, not a loop.
        if (needsRefresh(secondResult, second)) {
            return ApiResult.Err(ZennopayError.Unauthorized)
        }
        return secondResult
    }

    // Blocking HttpURLConnection I/O MUST run off the caller's thread: the
    // checkout UI drives this from the Compose (Main) dispatcher, so without the
    // IO switch a real request throws NetworkOnMainThreadException. `send` is
    // suspend + withContext(IO) so every call site is safe regardless of dispatcher.
    private suspend fun send(
        method: String,
        url: String,
        body: String?,
        extraHeaders: Map<String, String>,
    ): HttpOutcome = withContext(ioDispatcher) {
        try {
            val resp = transport.execute(
                HttpRequest(
                    method = method,
                    url = url,
                    bearer = sessionJwt,
                    headers = extraHeaders,
                    body = body,
                    timeoutMillis = config.requestTimeoutMillis,
                ),
            )
            HttpOutcome.Response(resp)
        } catch (e: IOException) {
            HttpOutcome.Failure(e)
        }
    }

    private fun <T> interpret(outcome: HttpOutcome, parse: (JSONObject) -> T): ApiResult<T> =
        when (outcome) {
            is HttpOutcome.Failure -> ApiResult.Err(ZennopayError.NetworkError(outcome.cause))
            is HttpOutcome.Response -> {
                val resp = outcome.response
                if (resp.isSuccess) {
                    try {
                        ApiResult.Ok(parse(JSONObject(resp.body)))
                    } catch (e: Exception) {
                        ApiResult.Err(ZennopayError.Unknown("response.unparseable"))
                    }
                } else {
                    ApiResult.Err(mapErrorBody(resp))
                }
            }
        }

    private fun mapErrorBody(resp: HttpResponse): ZennopayError {
        val code = extractErrorCode(resp.body)
        // A bare 401 (no body/code) means "unauthorized" → refreshSession + retry.
        if (code == null && resp.code == 401) return ZennopayError.Unauthorized
        // Status-aware mapping: the live wire carries the generic envelope code,
        // so disambiguate by HTTP status; a specific dotted reason (fixtures /
        // future revs) still maps exactly.
        return ErrorTaxonomy.fromEnvelope(resp.code, code)
    }

    private fun extractErrorCode(body: String): String? = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optStringOrNull("code")
            ?: json.optStringOrNull("code")
    } catch (e: Exception) {
        null
    }

    /** True when the outcome is a 401 or a mapped auth error worth refreshing. */
    private fun <T> needsRefresh(result: ApiResult<T>, outcome: HttpOutcome): Boolean {
        val is401 = outcome is HttpOutcome.Response && outcome.response.code == 401
        val isAuthErr = result is ApiResult.Err &&
            (result.error is ZennopayError.Unauthorized)
        return is401 || isAuthErr
    }

    private suspend fun tryRefresh(intentId: String): String? {
        val hook = refreshSession ?: return null
        return refreshMutex.withLock { hook(intentId) }
    }

    private sealed class HttpOutcome {
        data class Response(val response: HttpResponse) : HttpOutcome()
        data class Failure(val cause: Throwable) : HttpOutcome()
    }
}
