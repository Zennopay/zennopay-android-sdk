package com.zennopay.sdk.internal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * DataStore-backed [IdempotencyStore]. Durable across process death, which is
 * the whole point of T-CONFIRM-DURABILITY: a key written here before `/confirm`
 * survives a mid-confirm kill so relaunch can recover + safely retry.
 */
internal class DataStoreIdempotencyStore(context: Context) : IdempotencyStore {

    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> get() = appContext.zennopayIdempotencyDataStore

    private fun keyFor(intentId: String) = stringPreferencesKey("idem_$intentId")

    override suspend fun getOrCreateKey(intentId: String): String {
        val existing = peekKey(intentId)
        if (existing != null) return existing
        val fresh = IdempotencyKeys.generate(intentId)
        dataStore.edit { prefs -> prefs[keyFor(intentId)] = fresh }
        return fresh
    }

    override suspend fun peekKey(intentId: String): String? =
        dataStore.data.first()[keyFor(intentId)]

    override suspend fun clear(intentId: String) {
        dataStore.edit { prefs -> prefs.remove(keyFor(intentId)) }
    }

    private companion object {
        val Context.zennopayIdempotencyDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "zennopay_idempotency",
        )
    }
}
