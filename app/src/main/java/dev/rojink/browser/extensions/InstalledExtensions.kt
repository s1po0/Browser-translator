package dev.rojink.browser.extensions

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

private const val EXTENSION_FEATURE = "tachiyomi.extension"
private const val SOURCE_METADATA = "tachiyomi.extension.class"
private const val NAME_METADATA = "tachiyomix.name"

internal data class InstalledExtension(
    val packageName: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val signer: String?,
) {
    fun matches(term: String): Boolean = name.contains(term, ignoreCase = true) || packageName.contains(term, ignoreCase = true)
}

/** Read Android's shared packages, never another reader's private extension files or code. */
internal suspend fun installedExtensions(manager: PackageManager): Map<String, InstalledExtension> = withContext(Dispatchers.IO) {
    val flags = PackageManager.GET_CONFIGURATIONS
    @Suppress("DEPRECATION")
    val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else manager.getInstalledPackages(flags)
    // Only query signers and app labels for extension candidates, not the rest of the app inventory.
    packages.filter(PackageInfo::hasExtensionFeature).mapNotNull { candidate ->
        ensureActive()
        val pkg = manager.extensionPackage(candidate.packageName)?.takeIf(PackageInfo::hasExtensionManifest)
            ?: return@mapNotNull null
        val name = catalogLabel(pkg.applicationInfo?.metaData?.getString(NAME_METADATA).orEmpty()).ifBlank {
            catalogLabel(pkg.applicationInfo?.let(manager::getApplicationLabel)?.toString().orEmpty().removePrefix("Tachiyomi: "))
                .ifBlank { pkg.packageName }
        }
        val code = PackageInfoCompat.getLongVersionCode(pkg)
        InstalledExtension(pkg.packageName, name, catalogLabel(pkg.versionName.orEmpty(), 64).ifBlank { code.toString() },
            code, pkg.currentSignerSha256())
    }.sortedBy { it.name.lowercase(Locale.ROOT) }.associateBy { it.packageName }
}

internal fun extensionPackageFlags(): Int =
    PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or extensionSigningFlags()

@Suppress("DEPRECATION")
internal fun PackageManager.extensionPackage(packageName: String): PackageInfo? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(extensionPackageFlags().toLong()))
    } else getPackageInfo(packageName, extensionPackageFlags())
} catch (_: PackageManager.NameNotFoundException) {
    null // The package may have been removed since the initial list was read.
}

private fun PackageInfo.hasExtensionFeature(): Boolean = reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
internal fun PackageInfo.hasExtensionManifest(): Boolean = hasExtensionFeature() &&
    !applicationInfo?.metaData?.getString(SOURCE_METADATA).isNullOrBlank()

internal enum class ExtensionPresence {
    AVAILABLE, INSTALLED, UPDATE, CONFLICT;

    val canDownload: Boolean get() = this == AVAILABLE || this == UPDATE
}

internal fun CatalogExtension.presence(storeSigner: String, installed: InstalledExtension?): ExtensionPresence = when {
    installed == null -> ExtensionPresence.AVAILABLE
    installed.signer != storeSigner -> ExtensionPresence.CONFLICT
    installed.versionCode < versionCode -> ExtensionPresence.UPDATE
    else -> ExtensionPresence.INSTALLED
}

internal fun checkExtensionReplacement(manager: PackageManager, store: SavedExtensionStore, entry: CatalogExtension) {
    val existing = manager.extensionPackage(entry.packageName) ?: return
    if (!existing.hasExtensionManifest() || existing.currentSignerSha256() != store.signer) {
        throw StoreException(StoreProblem.INSTALLED_CONFLICT)
    }
    if (PackageInfoCompat.getLongVersionCode(existing) >= entry.versionCode) throw StoreException(StoreProblem.ALREADY_INSTALLED)
}
