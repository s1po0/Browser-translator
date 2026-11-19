package dev.rojink.browser.extensions

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.extensionStorePreferences by preferencesDataStore("extension-stores")
private val storesKey = stringPreferencesKey("trusted_stores")
private const val MAX_STORES = 20
private const val RECENT_CATALOG_MS = 5 * 60_000L

/** Only the user's confirmed store addresses and signing fingerprints are saved across launches. */
internal class ExtensionStores(context: Context, private val reader: StoreCatalogReader = StoreCatalogReader()) {
    private data class RecentCatalog(val catalog: ExtensionCatalog, val loadedAt: Long)
    private val recent = LruCache<SavedExtensionStore, RecentCatalog>(2)
    private val data = context.applicationContext.extensionStorePreferences
    val saved = data.data.map { decode(it[storesKey]) }.distinctUntilChanged()

    suspend fun preview(input: String): ExtensionCatalog = reader.load(input)

    suspend fun refresh(store: SavedExtensionStore): ExtensionCatalog {
        recent.remove(store)
        return reader.load(store.indexUrl, saved = store).also { rememberCatalog(store, it) }
    }

    suspend fun lookupInstalled(extension: InstalledExtension, retry: Boolean): InstalledSourceLookup =
        findInstalledSources(extension, saved.first()) { store ->
            val cached = recent.get(store)?.takeIf { !retry && SystemClock.elapsedRealtime() - it.loadedAt < RECENT_CATALOG_MS }
            cached?.catalog ?: refresh(store)
        }

    suspend fun add(catalog: ExtensionCatalog) {
        add(catalog.store)
        rememberCatalog(catalog.store, catalog)
    }

    private fun rememberCatalog(store: SavedExtensionStore, catalog: ExtensionCatalog) {
        recent.put(store, RecentCatalog(catalog, SystemClock.elapsedRealtime()))
    }

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
        recent.remove(store)
    }

    private fun decode(value: String?): List<SavedExtensionStore> =
        if (value == null) emptyList() else catalogJson.decodeFromString(value)
}
