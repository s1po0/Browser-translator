package dev.rojink.browser.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.extensions.ExtensionInstaller
import dev.rojink.browser.extensions.ExtensionStores
import dev.rojink.browser.extensions.StoreCatalogReader
import dev.rojink.browser.extensions.StoreHttp
import dev.rojink.browser.extensions.SavedExtensionStore
import dev.rojink.browser.extensions.installedExtensions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ExtensionStoresSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun browserMenuOffersTheKeiyoushiPresetWithoutDownloadingOrAddingIt() {
        val app = compose.activity.application as RojInkApp
        val prefs = runBlocking { app.settings.values.first() }
        runBlocking { app.settings.update { it.copy(uiLanguage = "en") } }
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithContentDescription("Browser menu").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Browser menu").performClick()
            compose.onNodeWithText("Extension stores").performClick()
            awaitText("Add Keiyoushi")
            compose.onNodeWithText("Add Keiyoushi").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("https://github.com/keiyoushi/extensions/raw/repo/index.pb")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("No stores added yet").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()

            compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            val extensionSettings = hasText("Extension stores") and hasClickAction()
            compose.onNodeWithTag("reader-settings-list").performScrollToNode(extensionSettings)
            compose.onNode(extensionSettings).performClick()
            awaitText("Add Keiyoushi")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("reader-settings-list").assertIsDisplayed()
        } finally { runBlocking { app.settings.update { prefs } } }
    }

    @Test fun reviewSearchRefreshTrustAndRemovalUseOnePersistedStore() {
        val activity = compose.activity
        val address = "https://fixture.example/index.json"
        val signer = AtomicReference("64".repeat(32))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = """{"name":"Fixture store","signingKey":"${signer.get()}","extensionList":{"extensions":[
                {"name":"River Comics","packageName":"test.extension.river","versionCode":"6","versionName":"1.6.6",
                "contentWarning":"CONTENT_WARNING_SAFE","resources":{"apkUrl":"https://fixture.example/download.apk"},
                "sources":[{"name":"Forest reader","language":"en","homeUrl":"https://forest.example"}]},
                {"name":"Other Comics","packageName":"test.extension.other","versionCode":"7","contentWarning":"CONTENT_WARNING_MIXED"}]}}"""
            assertEquals(address, chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val stores = ExtensionStores(activity, StoreCatalogReader(StoreHttp(client)))
        val installer = ExtensionInstaller(activity)
        compose.runOnUiThread {
            activity.setContent {
                RojInkTheme(BrowserSettings(uiLanguage = "en")) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        ExtensionStoresScreen(stores, installer, onBack = {}, onReadWebsite = {})
                    }
                }
            }
        }
        try {
            awaitText("Add a store")
            compose.onNodeWithText("Add a store").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("http://fixture.example/index.json")
            compose.onNodeWithText("Check address").performClick()
            awaitText("Enter a valid HTTPS catalog address or add-store link.")
            compose.onNode(hasSetTextAction()).performTextReplacement(address)
            compose.onNodeWithText("Check address").performClick()
            awaitText("Review extension store")
            compose.onNodeWithText("Add store").performClick()
            awaitText("River Comics")
            compose.onNodeWithText("1 shown · 2 in catalog").assertIsDisplayed()
            compose.onNodeWithText("Show mixed, adult, and unrated downloads").performClick()
            compose.onNodeWithText("2 shown · 2 in catalog").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextReplacement("forest en")
            compose.onNodeWithText("1 shown · 2 in catalog").assertIsDisplayed()
            compose.onNodeWithText("Other Comics").assertDoesNotExist()
            val saved = runBlocking { withTimeout(8_000) { ExtensionStores(activity).saved.first { it.any { r -> r.indexUrl == address } } } }
            assertEquals(signer.get(), saved.single { it.indexUrl == address }.signer)

            signer.set("12".repeat(32))
            compose.onNodeWithText("Refresh").performClick()
            awaitText("The signing fingerprint does not match the trusted value. The catalog was blocked.")
            compose.onNodeWithText("River Comics").assertDoesNotExist()
            compose.onNodeWithText("Remove store").performClick()
            compose.onNodeWithText("Remove", substring = false).performClick()
            awaitText("No stores added yet")
            assertEquals(emptyList<Any>(), runBlocking { stores.saved.first() })
        } finally {
            runBlocking { stores.saved.first().filter { it.indexUrl == address }.forEach { stores.remove(it) } }
        }
    }

    @Test fun sharedApkAppearsOfflineAndAtTheTopOfItsCatalogWithAReaderLink() {
        val activity = compose.activity
        val packageName = InstrumentationRegistry.getInstrumentation().context.packageName
        val fixture = runBlocking { checkNotNull(installedExtensions(activity.packageManager)[packageName]) }
        org.junit.Assert.assertTrue("Fixture APK must have a catalog-compatible version code: ${fixture.versionCode}", fixture.versionCode > 0)
        val record = SavedExtensionStore("https://device-fixture.example/index.json", "Device fixture store", checkNotNull(fixture.signer))
        val openedUrl = AtomicReference<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = """{"name":"${record.name}","signingKey":"${record.signer}","extensionList":{"extensions":[
                {"name":"A available","packageName":"test.extension.available","versionCode":1,"contentWarning":"CONTENT_WARNING_SAFE"},
                {"name":"Z catalog fixture","packageName":"$packageName","versionCode":${fixture.versionCode},
                 "contentWarning":"CONTENT_WARNING_MIXED","resources":{"apkUrl":"https://device-fixture.example/extension.apk"},
                 "sources":[{"name":"Reader fixture","language":"en","homeUrl":"https://read.fixture"}]}]}}"""
            assertEquals(record.indexUrl, chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val stores = ExtensionStores(activity, StoreCatalogReader(StoreHttp(client)))
        val installer = ExtensionInstaller(activity)
        runBlocking { stores.add(record) }
        try {
            compose.runOnUiThread {
                activity.setContent {
                    RojInkTheme(BrowserSettings(uiLanguage = "en")) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            ExtensionStoresScreen(stores, installer, onBack = {}, onReadWebsite = openedUrl::set)
                        }
                    }
                }
            }
            awaitText("Installed on this device")
            compose.onNodeWithText("Installed on this device").performClick()
            awaitText("Sipan smoke extension")
            compose.onNodeWithText(packageName).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            awaitText(record.name)
            compose.onNodeWithText(record.name).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { click ->
                org.junit.Assert.assertTrue(click())
            }
            try { awaitText("Z catalog fixture") } catch (e: ComposeTimeoutException) {
                val rendered = compose.onRoot().printToString().lineSequence().filter { "Text =" in it }.joinToString("; ").take(580)
                throw AssertionError("Version ${fixture.versionCode} fixture is missing from the catalog: $rendered", e)
            }
            val installedY = compose.onNodeWithText("Z catalog fixture").fetchSemanticsNode().boundsInRoot.top
            val availableY = compose.onNodeWithText("A available").fetchSemanticsNode().boundsInRoot.top
            org.junit.Assert.assertTrue(installedY < availableY)
            compose.onNodeWithText("Installed", substring = false).assertIsDisplayed()
            compose.onNodeWithText("Z catalog fixture").performClick()
            compose.onNodeWithText("This version or a newer one is already installed. No download needed.").assertIsDisplayed()
            compose.onNodeWithText("Verify and install APK").assertDoesNotExist()
            compose.onNodeWithText("Verify and update APK").assertDoesNotExist()
            compose.onNodeWithText("Read in Source reader: read.fixture").performClick()
            assertEquals("https://read.fixture/", openedUrl.get())
        } finally { runBlocking { stores.remove(record) } }
    }

    private fun awaitText(text: String) = compose.waitUntil(8_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}
