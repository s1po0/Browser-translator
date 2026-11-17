package dev.rojink.browser.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    @Test fun upgradeToSipanPreservesTheExistingLibrary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sipan-migration-${UUID.randomUUID()}.db"
        val oldUrl = "https://example.org/old-book"
        try {
            context.openOrCreateDatabase(name, 0, null).use { v1 ->
                // The table layouts in the signed RojInk 1.1.0 release, without Room's metadata.
                v1.execSQL("CREATE TABLE Bookmark (url TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, savedAt INTEGER NOT NULL)")
                v1.execSQL("CREATE TABLE Visit (url TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, visitedAt INTEGER NOT NULL)")
                v1.execSQL("CREATE TABLE Download (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, addedAt INTEGER NOT NULL)")
                v1.execSQL("CREATE TABLE CachedText (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                v1.execSQL("INSERT INTO Bookmark VALUES (?, 'My book', 1)", arrayOf<Any>(oldUrl))
                v1.execSQL("INSERT INTO Visit VALUES (?, 'My book', 2)", arrayOf<Any>(oldUrl))
                v1.execSQL("INSERT INTO Download VALUES (3, 'book.pdf', 4)")
                v1.execSQL("INSERT INTO CachedText VALUES ('cached', 'کوردی', 5)")
                v1.version = 1
            }
            val db = BrowserDatabase.create(context, name)
            try {
                val dao = db.dao()
                assertEquals(Bookmark(oldUrl, "My book", 1), dao.bookmarks().first().single())
                assertEquals(Visit(oldUrl, "My book", 2), dao.visits().first().single())
                assertEquals(Download(3, "book.pdf", 4), dao.downloads().first().single())
                assertEquals(CachedText("cached", "کوردی", 5), dao.cached(listOf("cached")).single())
                assertTrue(dao.searchPages().first().isEmpty())
                val page = SearchPage(oldUrl, "My book", "An excerpt", "Searchable body", 6)
                dao.putSearchPage(page)
                assertEquals(page, dao.searchPages().first().single())
                dao.clearSearchPages()
                assertTrue(dao.searchPages().first().isEmpty())
                assertEquals(oldUrl, dao.bookmarks().first().single().url)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
        Unit
    }
}
