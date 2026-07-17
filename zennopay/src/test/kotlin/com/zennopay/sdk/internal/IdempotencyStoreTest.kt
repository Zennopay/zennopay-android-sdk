package com.zennopay.sdk.internal

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Idempotency-key persistence tests (T-CONFIRM-DURABILITY, D5=A). The critical
 * invariant: getOrCreateKey is STABLE per intent, so a retry (or a relaunch that
 * reads the key back) confirms under the SAME key and the backend yields exactly
 * one debit + one payout.
 */
class IdempotencyStoreTest {

    @Test
    fun `getOrCreateKey returns the same key on repeated calls for one intent`() = runTest {
        val store = InMemoryIdempotencyStore()
        val first = store.getOrCreateKey("pi_1")
        val second = store.getOrCreateKey("pi_1")
        assertEquals("stable key enables safe retry", first, second)
    }

    @Test
    fun `different intents get different keys`() = runTest {
        val store = InMemoryIdempotencyStore()
        assertNotEquals(store.getOrCreateKey("pi_1"), store.getOrCreateKey("pi_2"))
    }

    @Test
    fun `peekKey returns null before creation and the key after`() = runTest {
        val store = InMemoryIdempotencyStore()
        assertNull(store.peekKey("pi_1"))
        val key = store.getOrCreateKey("pi_1")
        assertEquals(key, store.peekKey("pi_1"))
    }

    @Test
    fun `clear removes the key so a fresh attempt mints a new one`() = runTest {
        val store = InMemoryIdempotencyStore()
        val key1 = store.getOrCreateKey("pi_1")
        store.clear("pi_1")
        assertNull(store.peekKey("pi_1"))
        val key2 = store.getOrCreateKey("pi_1")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `generated key is prefixed and within the backend 64-char limit`() = runTest {
        val store = InMemoryIdempotencyStore()
        // A UUID intentId is 36 chars — the key must NOT embed it, or the key
        // exceeds the backend's 64-char Idempotency-Key cap (validation_failed).
        val key = store.getOrCreateKey("e489d294-473f-40d5-b469-dcea8ac3e396")
        assertTrue("prefixed", key.startsWith("zp_idem_"))
        assertTrue("within 64 chars, was ${key.length}", key.length <= 64)
    }

    @Test
    fun `relaunch recovery reads back a previously persisted key`() = runTest {
        // Simulate: process A persists a key, then dies; process B (same store,
        // durable in prod) reads it back and reuses it.
        val store = InMemoryIdempotencyStore()
        val persisted = store.getOrCreateKey("pi_relaunch")
        val recovered = store.peekKey("pi_relaunch")
        assertNotNull(recovered)
        assertEquals(persisted, recovered)
        // A subsequent retry reuses the same key.
        assertEquals(persisted, store.getOrCreateKey("pi_relaunch"))
    }
}
