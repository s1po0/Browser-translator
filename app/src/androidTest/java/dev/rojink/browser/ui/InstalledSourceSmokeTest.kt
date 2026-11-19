package dev.rojink.browser.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import dev.rojink.browser.extensions.*
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.DocumentFeatures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class InstalledSourceSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tappingAnInstalledApkOpensTheReaderAndCloseReturnsToTheSameSearch() {
        val activity = compose.activity
        val app = activity.application as RojInkApp
        val browser = activity.browser
        val fixture = fixture()
        val store = SavedExtensionStore("https://shortcut.fixture/index.json", "Shortcut catalog", checkNotNull(fixture.signer))
        val site = "https://single-source.fixture/"
        val served = AtomicBoolean(false)
        val savedFeatures = browser.features
        val prefs = runBlocking { app.settings.values.first() }
        val original = compose.runOnIdle { browser.selected }
        val entry = CatalogExtension("Renamed source", fixture.packageName, fixture.versionCode + 1, "1.6.8",
            CatalogWarning.SAFE, null, listOf(CatalogSource("Shortcut fixture", "en", site.toHttpUrl())))
        runBlocking {
            app.extensionStores.add(ExtensionCatalog(store, listOf(entry)))
            app.settings.update { it.copy(uiLanguage = "en") }
        }
        try {
            compose.runOnIdle {
                browser.features = object : DocumentFeatures {
                    override fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse {
                        val html = if (request.url.toString() == site) {
                            served.set(true)
                            "<html><body><h1>Source reader shortcut fixture</h1></body></html>"
                        } else ""
                        return WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                    }
                }
            }
            val menu = hasContentDescription("Browser menu")
            await(menu)
            compose.onNode(menu).activate()
            click("Extension stores")
            enterInstalled(fixture)
            val search = compose.onNode(hasSetTextAction())
            search.performTextReplacement("smoke extension")
            click(fixture.name)
            compose.waitUntil(15_000) { served.get() && browser.selected.address == site && browser.selected.progress == 100 }
            compose.onNodeWithTag("source-reader").assertIsDisplayed()
            compose.onNodeWithTag("source-reader-translate").assertIsEnabled()
            compose.onNodeWithContentDescription("Close source reader").activate()
            await(hasText(fixture.name) and hasClickAction())
            search.assertTextContains("smoke extension")
            compose.onNodeWithTag("source-reader").assertDoesNotExist()
            assertSame(original, compose.runOnIdle { browser.selected })
        } finally {
            compose.runOnIdle { browser.features = savedFeatures }
            runBlocking {
                app.extensionStores.remove(store)
                app.settings.update { prefs }
            }
        }
    }

    @Test fun missingCatalogOffersManualLinkAndCanBeApprovedWithoutLeavingTheList() {
        val activity = compose.activity
        val fixture = fixture()
        val store = SavedExtensionStore("https://choose-source.fixture/index.json", "Choice catalog", checkNotNull(fixture.signer))
        val second = "https://source-choice.fixture/en/"
        val fetched = AtomicInteger(0)
        val destinations = CopyOnWriteArrayList<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(store.indexUrl, chain.request().url.toString())
            fetched.incrementAndGet()
            val json = """{"name":"${store.name}","signingKey":"${store.signer}","extensionList":{"extensions":[
                {"name":"${fixture.name}","packageName":"${fixture.packageName}","versionCode":${fixture.versionCode},
                 "sources":[{"name":"First site","language":"en","homeUrl":"https://first-choice.fixture/"},
                 {"name":"Second site","language":"en","homeUrl":"$second"}]}
            ]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(json.toResponseBody()).build()
        }.build()
        val stores = ExtensionStores(activity, StoreCatalogReader(StoreHttp(client)))
        val installer = ExtensionInstaller(activity)
        compose.runOnUiThread {
            activity.setContent {
                RojInkTheme(BrowserSettings(uiLanguage = "en")) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        ExtensionStoresScreen(stores, installer, onBack = {}, onReadWebsite = { destinations.add(it) })
                    }
                }
            }
        }
        try {
            assertTrue(runBlocking { stores.saved.first().isEmpty() })
            enterInstalled(fixture)
            assertEquals(0, fetched.get())
            click(fixture.name)
            await(hasText("No catalogs are approved yet.", substring = true))
            click("Use my own link")
            assertEquals(listOf<String?>(null), destinations.toList())
            assertEquals(0, fetched.get())

            click(fixture.name)
            await(hasText("Add a store") and hasClickAction())
            click("Add a store")
            compose.onNodeWithTag("extension-store-address").performTextReplacement(store.indexUrl)
            assertEquals(0, fetched.get())
            click("Check address")
            await(hasText("Review extension store"))
            assertTrue(runBlocking { stores.saved.first().isEmpty() })
            click("Add store")
            await(hasText("Choose a website to open in Source reader."))
            assertEquals(listOf<String?>(null), destinations.toList())
            click(second)
            assertEquals(listOf(null, second), destinations.toList())
            assertEquals(listOf(store), runBlocking { stores.saved.first() })
            assertEquals("Reuse the approved preview; no APK or second catalog request", 1, fetched.get())
        } finally { runBlocking { stores.remove(store) } }
    }

    private fun fixture(): InstalledExtension = runBlocking {
        val packageName = InstrumentationRegistry.getInstrumentation().context.packageName
        checkNotNull(installedExtensions(compose.activity.packageManager)[packageName])
    }

    private fun enterInstalled(fixture: InstalledExtension) {
        await(hasText("Installed on this device"))
        compose.onNodeWithText("Installed on this device").performClick()
        await(hasText(fixture.name))
    }

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).activate()
    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(12_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    private fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
}
