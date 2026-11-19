package dev.rojink.browser.extensions

import org.junit.Assert.*
import org.junit.Test

class InstalledCatalogTest {
    private val signer = "2b".repeat(32)
    private val downloaded = InstalledExtension("ext.sample.one", "Old comics name", "1.6.2", 2, signer)
    private val renamed = entry("Z Renamed", downloaded.packageName, CatalogWarning.MIXED)

    @Test fun installedPackagesComeFirstEvenWhenNewDownloadsAreFiltered() {
        val available = entry("A available", "ext.sample.available")
        val hidden = entry("A hidden", "ext.sample.hidden", CatalogWarning.NSFW)
        val sameNameNotInstalled = entry(downloaded.name, "ext.sample.other", CatalogWarning.MIXED)
        val inventory = mapOf(downloaded.packageName to downloaded)
        val entries = listOf(available, hidden, sameNameNotInstalled, renamed)
        val sections = entries.sections("", false, inventory)
        assertEquals(listOf(renamed), sections.installed)
        assertEquals(listOf(available), sections.available)
        assertEquals(listOf(renamed), entries.sections("old en", false, inventory).installed)
        assertEquals(listOf(renamed), entries.sections("ext.sample.one", false, inventory).installed)
        assertEquals(listOf(available, hidden, sameNameNotInstalled), entries.sections("", true, inventory).available)
    }

    @Test fun onlyAnOlderVersionFromTheTrustedSignerCanOfferAnUpdate() {
        assertEquals(ExtensionPresence.AVAILABLE, renamed.presence(signer, null))
        assertEquals(ExtensionPresence.INSTALLED, renamed.presence(signer, downloaded))
        assertEquals(ExtensionPresence.INSTALLED, renamed.presence(signer, downloaded.copy(versionCode = 5)))
        assertEquals(ExtensionPresence.UPDATE, renamed.presence(signer, downloaded.copy(versionCode = 1)))
        assertEquals(ExtensionPresence.CONFLICT, renamed.presence(signer, downloaded.copy(signer = null)))
        assertEquals(ExtensionPresence.CONFLICT, renamed.presence("3a".repeat(32), downloaded))
        assertFalse(ExtensionPresence.INSTALLED.canDownload)
        assertFalse(ExtensionPresence.CONFLICT.canDownload)
        assertTrue(ExtensionPresence.UPDATE.canDownload)
    }

    private fun entry(name: String, pkg: String, warning: CatalogWarning = CatalogWarning.SAFE) =
        CatalogExtension(name, pkg, 2, "1.6.2", warning, null, listOf(CatalogSource(name, "en", null)))
}
