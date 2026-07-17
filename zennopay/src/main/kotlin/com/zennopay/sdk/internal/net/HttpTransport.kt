package com.zennopay.sdk.internal.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP transport seam. Abstracted so the REST client can be unit-tested
 * with a fake transport (no real sockets) while production uses
 * [HttpUrlConnectionTransport]. Keeping this an interface avoids pulling OkHttp.
 */
internal interface HttpTransport {
    /**
     * Execute a request and return the raw response. Throws [IOException] only on
     * transport failure (DNS, connect, read); non-2xx HTTP responses are returned
     * with their status code and body, not thrown.
     */
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

internal data class HttpRequest(
    val method: String,
    val url: String,
    val bearer: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMillis: Long = 20_000L,
)

internal data class HttpResponse(
    val code: Int,
    val body: String,
) {
    val isSuccess: Boolean get() = code in 200..299
}

/** Production transport backed by [HttpURLConnection]. No third-party deps. */
internal class HttpUrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = request.timeoutMillis.toInt()
            readTimeout = request.timeoutMillis.toInt()
            setRequestProperty("Authorization", "Bearer ${request.bearer}")
            setRequestProperty("Accept", "application/json")
            request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (request.body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (request.body != null) {
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
