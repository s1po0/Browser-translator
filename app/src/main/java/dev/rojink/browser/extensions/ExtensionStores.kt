package dev.rojink.browser.extensions

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.extensionStorePreferences by preferencesDataStore("extension-stores")
private val storesKey = stringPreferencesKey("trusted_stores")
private const val MAX_STORES = 20

/** Only the user's confirmed store addresses and signing fingerprints are saved across launches. */
internal class ExtensionStores(context: Context, private val reader: StoreCatalogReader = StoreCatalogReader()) {
    private val data = context.applicationContext.extensionStorePreferences
    val saved = data.data.map { decode(it[storesKey]) }.distinctUntilChanged()

    suspend fun preview(input: String): ExtensionCatalog = reader.load(input)
    suspend fun refresh(store: SavedExtensionStore): ExtensionCatalog = reader.load(store.indexUrl, saved = store)

    suspend fun add(store: SavedExtensionStore) {
        data.edit { prefs ->
            val existing = decode(prefs[storesKey])
            if (existing.any { it.indexUrl == store.indexUrl }) throw StoreException(StoreProblem.DUPLICATE)
            if (existing.size >= MAX_STORES) throw StoreException(StoreProblem.LIMIT)
            prefs[storesKey] = catalogJson.encodeToString(existing + store)
        }
    }

    suspend fun remove(store: SavedExtensionStore) {
        data.edit { prefs ->
            prefs[storesKey] = catalogJson.encodeToString(decode(prefs[storesKey]).filterNot { it.indexUrl == store.indexUrl })
        }
    }

    private fun decode(value: String?): List<SavedExtensionStore> =
        if (value == null) emptyList() else catalogJson.decodeFromString(value)
}
