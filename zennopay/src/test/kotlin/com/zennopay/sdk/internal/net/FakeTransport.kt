package com.zennopay.sdk.internal.net

/**
 * Scripted [HttpTransport] for unit tests. Each entry matches on (method, path
 * suffix) and returns a canned [HttpResponse]. Records the Bearer token seen on
 * each call so tests can assert the refreshed JWT was used on retry.
 */
internal class FakeTransport : HttpTransport {

    data class Recorded(val method: String, val url: String, val bearer: String, val body: String?)

    val calls = mutableListOf<Recorded>()

    /** method+pathSuffix -> queue of responses (popped per call, last repeats). */
    private val scripts = mutableMapOf<String, ArrayDeque<HttpResponse>>()

    fun on(method: String, pathSuffix: String, vararg responses: HttpResponse): FakeTransport {
        scripts.getOrPut(key(method, pathSuffix)) { ArrayDeque() }
            .addAll(responses.toList())
        return this
    }

    override fun execute(request: HttpRequest): HttpResponse {
        calls += Recorded(request.method, request.url, request.bearer, request.body)
        val match = scripts.entries.firstOrNull { (k, _) ->
            val (m, suffix) = k.split(' ', limit = 2)
            request.method == m && request.url.contains(suffix)
        } ?: error("No scripted response for ${request.method} ${request.url}")
        val queue = match.value
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    private fun key(method: String, suffix: String) = "$method $suffix"

    companion object {
        fun ok(body: String) = HttpResponse(200, body)
        fun status(code: Int, body: String = "") = HttpResponse(code, body)
    }
}
