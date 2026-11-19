package dev.rojink.browser.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class InstalledSourceLookupTest {
    private val saved = SavedExtensionStore("https://approved.example/index.json", "Approved store", "24".repeat(32))
    private val apk = InstalledExtension("test.extension.reader", "Old name", "1.6.2", 2, saved.signer)
    private val manga = source("Manga", "https://manga.example/")
    private val webtoon = source("Webtoon", "https://webtoon.example/series")

    @Test fun opensOneExactPackageMatchEvenWhenTheNameAndVersionDiffer() = runBlocking {
        val result = findInstalledSources(apk, listOf(saved)) { record ->
            assertEquals(saved, record)
            val decoy = entry(manga).copy(name = apk.name, packageName = "test.extension.other", sources = listOf(webtoon))
            ExtensionCatalog(record, listOf(decoy, entry(manga)))
        }
        assertNull(result.issue)
        assertEquals(manga.website, result.direct?.website)
    }

    @Test fun neverFetchesAnUnapprovedStoreOrOneWithADifferentSigner() = runBlocking {
        val refused: suspend (SavedExtensionStore) -> ExtensionCatalog = { error("No catalog should have been fetched") }
        assertEquals(InstalledSourceIssue.NO_STORES, findInstalledSources(apk, emptyList(), refused).issue)
        val otherSigner = saved.copy(signer = "42".repeat(32))
        assertEquals(InstalledSourceIssue.NO_SIGNER_MATCH, findInstalledSources(apk, listOf(otherSigner), refused).issue)
        assertEquals(InstalledSourceIssue.NO_SIGNER_MATCH, findInstalledSources(apk.copy(signer = null), listOf(saved), refused).issue)
    }

    @Test fun multipleDistinctUrlsRequireAChoiceAndRepeatedUrlsCollapse() = runBlocking {
        val mirror = saved.copy(indexUrl = "https://mirror.example/index.pb")
        val result = findInstalledSources(apk, listOf(saved, mirror)) { record ->
            ExtensionCatalog(record, listOf(if (record == mirror) entry(manga, webtoon) else entry(manga)))
        }
        assertEquals(listOf(manga.website, webtoon.website), result.links.map { it.website })
        assertNull(result.direct)
        assertNull(result.issue)
    }

    @Test fun noWebsiteDoesNotGuessFromTheNameOrPackage() = runBlocking {
        val result = findInstalledSources(apk, listOf(saved)) { record ->
            ExtensionCatalog(record, listOf(entry(CatalogSource("Unavailable", "en", null)), entry(manga).copy(packageName = "elsewhere.reader")))
        }
        assertEquals(InstalledSourceIssue.NO_WEBSITE, result.issue)
        assertTrue(result.links.isEmpty())
    }

    @Test fun reportsOfflineAndChangedSignersAndDoesNotAutoOpenPartialResults() = runBlocking {
        val unreachable = saved.copy(indexUrl = "https://offline.example/index.json")
        val partial = findInstalledSources(apk, listOf(unreachable, saved)) { record ->
            if (record == unreachable) throw IOException("offline")
            ExtensionCatalog(record, listOf(entry(manga)))
        }
        assertEquals(InstalledSourceIssue.UNAVAILABLE, partial.issue)
        assertEquals(manga.website, partial.links.single().website)
        assertNull(partial.direct)
        val mismatched = findInstalledSources(apk, listOf(saved)) { record ->
            ExtensionCatalog(record.copy(signer = "4a".repeat(32)), listOf(entry(manga)))
        }
        assertEquals(InstalledSourceIssue.SIGNER_CHANGED, mismatched.issue)
        assertTrue(mismatched.links.isEmpty())
    }

    @Test fun dismissalPropagatesCancellationInsteadOfNavigating() {
        assertThrows(CancellationException::class.java) {
            runBlocking { findInstalledSources(apk, listOf(saved)) { throw CancellationException("dismissed") } }
        }
    }

    private fun entry(vararg sources: CatalogSource) =
        CatalogExtension("New name", apk.packageName, 8, "1.6.8", CatalogWarning.SAFE, null, sources.toList())

    private fun source(name: String, url: String) = CatalogSource(name, "en", url.toHttpUrl())
}
