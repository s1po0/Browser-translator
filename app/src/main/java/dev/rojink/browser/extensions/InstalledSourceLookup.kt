package dev.rojink.browser.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

internal data class InstalledSourceLink(
    val name: String,
    val language: String,
    val website: HttpUrl,
    val storeName: String,
)

internal enum class InstalledSourceIssue { NO_STORES, NO_SIGNER_MATCH, NO_WEBSITE, UNAVAILABLE, SIGNER_CHANGED }

internal data class InstalledSourceLookup(val links: List<InstalledSourceLink>, val issue: InstalledSourceIssue? = null) {
    val direct get() = if (issue == null) links.singleOrNull() else null
}

/** Look up links locally within approved catalogs, never send the package ID or load the APK. */
internal suspend fun findInstalledSources(
    extension: InstalledExtension,
    approved: List<SavedExtensionStore>,
    load: suspend (SavedExtensionStore) -> ExtensionCatalog,
): InstalledSourceLookup {
    if (approved.isEmpty()) return InstalledSourceLookup(emptyList(), InstalledSourceIssue.NO_STORES)
    val candidates = approved.filter { it.signer == extension.signer }
    if (candidates.isEmpty()) return InstalledSourceLookup(emptyList(), InstalledSourceIssue.NO_SIGNER_MATCH)
    val links = linkedMapOf<HttpUrl, InstalledSourceLink>()
    var unavailable = false
    var signerChanged = false
    // Bound the whole lookup even with several slow or offline catalogs. Keep any valid links already found.
    val completed = withTimeoutOrNull(25_000L) {
        for (record in candidates) {
            val catalog = try { load(record) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is StoreException && e.problem == StoreProblem.SIGNER) signerChanged = true else unavailable = true
                continue
            }
            if (catalog.store.signer != record.signer) {
                signerChanged = true
                continue
            }
            val entry = catalog.entries.firstOrNull { it.packageName == extension.packageName } ?: continue
            for (source in entry.sources) {
                val website = source.website ?: continue
                links.putIfAbsent(website, InstalledSourceLink(source.name, source.language, website, catalog.store.name))
            }
        }
    } != null
    val issue = when {
        signerChanged -> InstalledSourceIssue.SIGNER_CHANGED
        !completed || unavailable -> InstalledSourceIssue.UNAVAILABLE
        links.isEmpty() -> InstalledSourceIssue.NO_WEBSITE
        else -> null
    }
    return InstalledSourceLookup(links.values.toList(), issue)
}
