package dev.rojink.browser.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity data class Bookmark(@PrimaryKey val url: String, val title: String, val savedAt: Long = System.currentTimeMillis())
@Entity data class Visit(@PrimaryKey val url: String, val title: String, val visitedAt: Long = System.currentTimeMillis())
@Entity data class Download(@PrimaryKey val id: Long, val name: String, val addedAt: Long = System.currentTimeMillis())
@Entity data class SearchPage(@PrimaryKey val url: String, val title: String, val summary: String, val body: String, val savedAt: Long = System.currentTimeMillis())
@Entity data class CachedText(@PrimaryKey val key: String, val value: String, val createdAt: Long = System.currentTimeMillis())

@Dao
interface BrowserDao {
    @Query("SELECT * FROM Bookmark ORDER BY savedAt DESC") fun bookmarks(): Flow<List<Bookmark>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBookmarks(entries: List<Bookmark>)
    @Query("DELETE FROM Bookmark WHERE url = :url") suspend fun removeBookmark(url: String)
    @Query("SELECT * FROM Visit ORDER BY visitedAt DESC LIMIT 1000") fun visits(): Flow<List<Visit>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putVisit(visit: Visit)
    @Query("DELETE FROM Visit WHERE url NOT IN (SELECT url FROM Visit ORDER BY visitedAt DESC LIMIT 1000)") suspend fun trimVisits()
    @Query("DELETE FROM Visit") suspend fun clearHistory()
    @Query("SELECT * FROM Download ORDER BY addedAt DESC") fun downloads(): Flow<List<Download>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDownload(entry: Download)
    @Query("DELETE FROM Download WHERE id = :id") suspend fun removeDownload(id: Long)
    @Query("SELECT * FROM SearchPage ORDER BY savedAt DESC LIMIT 150") fun searchPages(): Flow<List<SearchPage>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSearchPage(entry: SearchPage)
    @Query("DELETE FROM SearchPage WHERE url NOT IN (SELECT url FROM SearchPage ORDER BY savedAt DESC LIMIT 150)") suspend fun trimSearchPages()
    @Query("DELETE FROM SearchPage") suspend fun clearSearchPages()
    @Query("SELECT * FROM CachedText WHERE `key` IN (:keys)") suspend fun cached(keys: List<String>): List<CachedText>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCached(entries: List<CachedText>)
    @Query("DELETE FROM CachedText WHERE `key` NOT IN (SELECT `key` FROM CachedText ORDER BY createdAt DESC LIMIT 18000)") suspend fun trimCached()
    @Query("DELETE FROM CachedText") suspend fun clearTranslations()
}

@Database(entities = [Bookmark::class, Visit::class, Download::class, CachedText::class, SearchPage::class], version = 2)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun dao(): BrowserDao
    companion object {
        private val searchablePages = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `SearchPage` (`url` TEXT NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `body` TEXT NOT NULL, `savedAt` INTEGER NOT NULL)")
            }
        }
        fun create(context: Context) = create(context, "rojink-library.db")

        internal fun create(context: Context, name: String) = Room.databaseBuilder(context, BrowserDatabase::class.java, name)
            .addMigrations(searchablePages).build()
    }
}

data class BrowserSettings(
    val target: String = "ckb",
    val theme: String = "system",
    val uiLanguage: String = "system",
    val textSize: Int = 100,
    val translateText: Boolean = true,
    val translateImages: Boolean = true,
    val trackerBlocking: Boolean = true,
    val useVision: Boolean = false,
    val ocrHint: String = "auto",
    val enhancedOcr: Boolean = true,
)

private val Context.preferences by preferencesDataStore("rojink-settings")
private object Prefs {
    val target = stringPreferencesKey("target")
    val theme = stringPreferencesKey("theme")
    val ui = stringPreferencesKey("ui")
    val oldEngine = stringPreferencesKey("engine")
    val size = intPreferencesKey("text_size")
    val text = booleanPreferencesKey("page_text")
    val images = booleanPreferencesKey("images")
    val block = booleanPreferencesKey("trackers")
    val vision = booleanPreferencesKey("use_vision")
    val hint = stringPreferencesKey("ocr_hint")
    val enhanced = booleanPreferencesKey("enhanced_ocr")
}

class SettingsStore(context: Context) {
    private val store = context.applicationContext.preferences
    private fun decode(p: androidx.datastore.preferences.core.Preferences) = BrowserSettings(
        target = p[Prefs.target] ?: "ckb", theme = p[Prefs.theme] ?: "system", uiLanguage = p[Prefs.ui] ?: "system",
        textSize = (p[Prefs.size] ?: 100).coerceIn(75, 175), translateText = p[Prefs.text] ?: true,
        translateImages = p[Prefs.images] ?: true, trackerBlocking = p[Prefs.block] ?: true,
        useVision = p[Prefs.vision] ?: false, ocrHint = p[Prefs.hint] ?: "auto", enhancedOcr = p[Prefs.enhanced] ?: true,
    )
    val values = store.data.map(::decode)
    suspend fun update(transform: (BrowserSettings) -> BrowserSettings) = store.edit { p ->
        val settings = transform(decode(p))
        p[Prefs.target] = settings.target
        p[Prefs.theme] = settings.theme
        p[Prefs.ui] = settings.uiLanguage
        p.remove(Prefs.oldEngine)
        p[Prefs.size] = settings.textSize.coerceIn(75, 175)
        p[Prefs.text] = settings.translateText
        p[Prefs.images] = settings.translateImages
        p[Prefs.block] = settings.trackerBlocking
        p[Prefs.vision] = settings.useVision
        p[Prefs.hint] = settings.ocrHint
        p[Prefs.enhanced] = settings.enhancedOcr
    }
}

enum class ApiKey { TRANSLATE, VISION }

/** Keys are never written to Room or included in app backups. Only ciphertext is persisted. */
class KeyVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("provider_keys", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _enabled = MutableStateFlow(ApiKey.entries.filter { prefs.contains(it.name) }.toSet())
    val enabled = _enabled.asStateFlow()

    suspend fun read(kind: ApiKey): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val value = prefs.getString(kind.name, null) ?: return@synchronized null
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(kind.name.toByteArray())
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }

    suspend fun set(kind: ApiKey, value: String?) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) editor.remove(kind.name) else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD(kind.name.toByteArray())
                val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                val content = Base64.encodeToString(cipher.doFinal(value.trim().toByteArray()), Base64.NO_WRAP)
                editor.putString(kind.name, "$iv:$content")
            }
            check(editor.commit()) { "Could not save provider settings" }
            _enabled.value = ApiKey.entries.filter { prefs.contains(it.name) }.toSet()
        }
    }

    private fun key(): SecretKey {
        val alias = "rojink_api_keys_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
