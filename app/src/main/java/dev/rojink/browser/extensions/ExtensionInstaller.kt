package dev.rojink.browser.extensions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val EXTENSION_CACHE = "extension-installs"
private const val EXTENSION_FEATURE = "tachiyomi.extension"
private const val SOURCE_METADATA = "tachiyomi.extension.class"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal class ExtensionInstaller(context: Context, private val http: StoreHttp = StoreHttp()) {
    private val directory = File(context.applicationContext.cacheDir, EXTENSION_CACHE)

    suspend fun handOff(context: Context, store: SavedExtensionStore, entry: CatalogExtension) {
        val url = entry.apk ?: throw StoreException(StoreProblem.APK)
        val file = File(directory, "${UUID.randomUUID()}.apk")
        var handedOff = false
        try {
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot stage extension APK")
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            }
            http.apk(url, file)
            withContext(Dispatchers.IO) { verifyExtensionApk(context.packageManager, file, store, entry) }
            // The OS asks for confirmation and enforces update/downgrade and package-signing rules.
            context.startActivity(extensionInstallIntent(context, file))
            handedOff = true
        } finally {
            if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }
}

internal fun extensionInstallIntent(context: Context, apk: File): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", apk), APK_MIME_TYPE)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

@Suppress("DEPRECATION") // Android 8 still uses GET_SIGNATURES.
internal fun extensionSigningFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    PackageManager.GET_SIGNING_CERTIFICATES
} else {
    PackageManager.GET_SIGNATURES
}

@Suppress("DEPRECATION")
internal fun PackageInfo.currentSignerSha256(): String? {
    val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) signingInfo?.apkContentsSigners else signatures
    val signature = signers?.singleOrNull() ?: return null
    return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun verifyExtensionApk(manager: PackageManager, apk: File, store: SavedExtensionStore, entry: CatalogExtension) {
    val flags = extensionSigningFlags() or PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA
    @Suppress("DEPRECATION")
    val parsed = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw StoreException(StoreProblem.APK)
    if (parsed.packageName != entry.packageName || PackageInfoCompat.getLongVersionCode(parsed) != entry.versionCode ||
        parsed.reqFeatures.orEmpty().none { it.name == EXTENSION_FEATURE } ||
        parsed.applicationInfo?.metaData?.getString(SOURCE_METADATA).isNullOrBlank() ||
        parsed.currentSignerSha256() != store.signer) {
        throw StoreException(StoreProblem.APK)
    }
}
