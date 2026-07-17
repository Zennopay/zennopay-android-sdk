package com.zennopay.sdk.internal

import java.util.UUID

/**
 * Durable store for confirm idempotency keys (T-CONFIRM-DURABILITY, D5=A).
 *
 * Contract: the SDK persists `{intent_id -> idempotency_key}` BEFORE it fires
 * `POST /confirm`. If the process dies mid-confirm, on relaunch the SDK reads the
 * key back, re-GETs the intent to recover the real terminal status, and — if it
 * must retry — reuses the SAME key so the backend's single-flight guarantee
 * yields exactly one debit + one payout.
 *
 * Abstracted behind an interface so unit tests can use an in-memory fake with no
 * Android/DataStore dependency; production uses [DataStoreIdempotencyStore].
 */
internal interface IdempotencyStore {
    /**
     * Return the existing key for [intentId], or generate + persist a new one and
     * return it. Idempotent: repeated calls for the same intent return the same
     * key, which is exactly what makes retries safe.
     */
    suspend fun getOrCreateKey(intentId: String): String

    /** Peek without creating — used on relaunch recovery. Null if none stored. */
    suspend fun peekKey(intentId: String): String?

    /** Clear the key once the intent reaches a terminal state. */
    suspend fun clear(intentId: String)
}

/**
 * Generates the stable idempotency key. Extracted so it can be swapped in tests.
 */
internal object IdempotencyKeys {
    /**
     * UUIDv4, prefixed so it's greppable in server logs. Must stay within the
     * backend's `MAX_IDEMPOTENCY_KEY_LENGTH` (64). `zp_idem_` (8) + UUID (36) =
     * 44 chars. The intentId is NOT embedded — the key is already stored per
     * intent, and embedding a 36-char UUID intentId pushed the key to 81 chars,
     * over the limit (backend rejected /confirm with validation_failed).
     */
    fun generate(intentId: String): String = "zp_idem_${UUID.randomUUID()}"
}

/**
 * In-memory implementation. Not durable across process death — used by tests and
 * as a safe fallback if DataStore init fails. Thread-safe via a synchronized lock
 * (a plain lock, not @Synchronized, since these are suspend functions).
 */
internal class InMemoryIdempotencyStore : IdempotencyStore {
    private val map = HashMap<String, String>()
    private val lock = Any()

    override suspend fun getOrCreateKey(intentId: String): String =
        synchronized(lock) { map.getOrPut(intentId) { IdempotencyKeys.generate(intentId) } }

    override suspend fun peekKey(intentId: String): String? =
        synchronized(lock) { map[intentId] }

    override suspend fun clear(intentId: String) {
        synchronized(lock) { map.remove(intentId) }
    }
}
