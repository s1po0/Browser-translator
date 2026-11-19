package dev.rojink.browser.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rojink.browser.MainActivity
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.extensions.ExtensionInstaller
import dev.rojink.browser.extensions.ExtensionStores
import dev.rojink.browser.extensions.StoreCatalogReader
import dev.rojink.browser.extensions.StoreHttp
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
                        ExtensionStoresScreen(stores, installer, onBack = {}, onOpenWebsite = {})
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
            compose.onNodeWithText("Show mixed, adult, and unrated entries").performClick()
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

    private fun awaitText(text: String) = compose.waitUntil(8_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}
