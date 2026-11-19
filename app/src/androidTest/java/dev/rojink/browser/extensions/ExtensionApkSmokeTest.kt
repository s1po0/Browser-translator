package dev.rojink.browser.extensions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.runBlocking
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExtensionApkSmokeTest {
    @Test fun signedFixturePassesAndIncorrectSignerPackageVersionOrManifestDoesNot() {
        val fixture = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = target.packageManager
        @Suppress("DEPRECATION")
        val info = fixture.packageManager.getPackageInfo(fixture.packageName, extensionSigningFlags())
        val store = SavedExtensionStore("https://test.example/index.pb", "Fixture", checkNotNull(info.currentSignerSha256()))
        val entry = CatalogExtension("Fixture", fixture.packageName, PackageInfoCompat.getLongVersionCode(info), "1.6.0",
            CatalogWarning.SAFE, null, emptyList())
        val directory = File(target.cacheDir, "extension-installs").apply { mkdirs() }
        val staged = File.createTempFile("fixture-", ".apk", directory)
        try {
            File(fixture.applicationInfo.sourceDir).copyTo(staged, overwrite = true)
            verifyExtensionApk(manager, staged, store, entry)
            val install = extensionInstallIntent(target, staged)
            assertEquals(Intent.ACTION_VIEW, install.action)
            assertEquals("application/vnd.android.package-archive", install.type)
            assertEquals("content", install.data?.scheme)
            assertEquals("${target.packageName}.files", install.data?.authority)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(staged.length(), target.contentResolver.openFileDescriptor(install.data!!, "r")!!.use { it.statSize })

            assertRejected { verifyExtensionApk(manager, staged, store.copy(signer = "0".repeat(64)), entry) }
            assertRejected { verifyExtensionApk(manager, staged, store, entry.copy(packageName = "unrelated.fixture")) }
            assertRejected { verifyExtensionApk(manager, staged, store, entry.copy(versionCode = entry.versionCode + 1)) }

            @Suppress("DEPRECATION")
            val appInfo = manager.getPackageInfo(target.packageName, extensionSigningFlags())
            val appRecord = store.copy(signer = checkNotNull(appInfo.currentSignerSha256()))
            val appEntry = entry.copy(packageName = target.packageName, versionCode = PackageInfoCompat.getLongVersionCode(appInfo))
            assertRejected { verifyExtensionApk(manager, File(target.applicationInfo.sourceDir), appRecord, appEntry) }
            staged.writeText("This is not an APK")
            assertRejected { verifyExtensionApk(manager, staged, store, entry) }
        } finally { staged.delete() }
    }

    @Test fun sharedInstalledExtensionsAreReadFromAndroidAndNotDownloadedAgain() = runBlocking {
        val fixture = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = target.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(PackageManager.PERMISSION_GRANTED, target.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES))
        }
        val installed = installedExtensions(manager)
        val stamp = checkNotNull(installed[fixture.packageName])
        assertEquals("Sipan smoke extension", stamp.name)
        assertNotNull(stamp.signer)
        assertFalse("Sipan is not an extension", installed.containsKey(target.packageName))
        assertTrue(installed.values.all { manager.extensionPackage(it.packageName)?.hasExtensionManifest() == true })

        val store = SavedExtensionStore("https://fixture.example/index.json", "Fixture", checkNotNull(stamp.signer))
        val entry = CatalogExtension(stamp.name, stamp.packageName, stamp.versionCode, stamp.version, CatalogWarning.SAFE, null, emptyList())
        fun checkBlocked(problem: StoreProblem, candidate: CatalogExtension, publisher: SavedExtensionStore = store) {
            assertEquals(problem, assertThrows(StoreException::class.java) { checkExtensionReplacement(manager, publisher, candidate) }.problem)
        }
        checkBlocked(StoreProblem.ALREADY_INSTALLED, entry)
        checkBlocked(StoreProblem.ALREADY_INSTALLED, entry.copy(versionCode = entry.versionCode - 1))
        checkBlocked(StoreProblem.INSTALLED_CONFLICT, entry, store.copy(signer = "01".repeat(32)))
        checkExtensionReplacement(manager, store, entry.copy(versionCode = entry.versionCode + 1))
    }

    private fun assertRejected(action: () -> Unit) {
        assertEquals(StoreProblem.APK, assertThrows(StoreException::class.java, action).problem)
    }
}
