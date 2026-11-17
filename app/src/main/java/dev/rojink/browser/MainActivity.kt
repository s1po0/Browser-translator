package dev.rojink.browser

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.ui.BrowserUi
import dev.rojink.browser.ui.RojInkTheme
import dev.rojink.browser.ui.TranslateSheet
import dev.rojink.browser.ui.TranslationBar
import dev.rojink.browser.translation.PageTranslator
import dev.rojink.browser.translation.ImagePipeline
import dev.rojink.browser.translation.GallerySession
import dev.rojink.browser.ui.GalleryUi
import dev.rojink.browser.translation.ProviderFactory
import dev.rojink.browser.web.Browser

class MainActivity : ComponentActivity() {
    private lateinit var browser: Browser
    private lateinit var images: ImagePipeline
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RojInkApp
        browser = Browser(this, app.dao, app.library)
        images = ImagePipeline(applicationContext)
        val providers = ProviderFactory(app.dao, app.keys)
        val translator = PageTranslator(this, browser, providers::get, images)
        browser.features = translator
        val gallery = GallerySession(this, browser, images, providers)
        setContent {
            val settings by app.settings.values.collectAsState(BrowserSettings())
            LaunchedEffect(settings) { browser.apply(settings) }
            val fullscreen = browser.fullScreen != null
            LaunchedEffect(fullscreen) {
                val insets = WindowCompat.getInsetsController(window, window.decorView)
                if (fullscreen) {
                    insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                } else insets.show(WindowInsetsCompat.Type.systemBars())
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) gallery.select(uri, browser.selected.incognito)
            }
            RojInkTheme(settings) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
                val protectPreview = browser.selected.incognito || (gallery.active && gallery.privateGallery)
                SideEffect {
                    if (protectPreview) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val bars = WindowCompat.getInsetsController(window, window.decorView)
                    bars.isAppearanceLightStatusBars = lightBars
                    bars.isAppearanceLightNavigationBars = lightBars
                }
                if (gallery.active) {
                    GalleryUi(gallery, settings)
                    return@RojInkTheme
                }
                val tab = browser.selected
                val status = translator.status(tab)
                var prompt by remember(tab.id) { mutableStateOf(false) }
                BrowserUi(browser, app, settings, onTranslate = { prompt = true },
                    onPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, clearTranslations = {
                    translator.stopAll(); browser.dao.clearTranslations(); images.clear(); gallery.clearShares()
                }) {
                    if (status != null) TranslationBar(status, { translator.toggle(tab) }, { translator.stop(tab) })
                }
                if (prompt) TranslateSheet(settings, status = status, dismiss = { prompt = false },
                    start = { options -> prompt = false; translator.start(tab, options) },
                    stop = { prompt = false; translator.stop(tab) })
            }
        }
    }
    override fun onResume() { super.onResume(); browser.setVisible(true) }
    override fun onPause() { browser.setVisible(false); super.onPause() }
    override fun onDestroy() { browser.destroy(); images.close(); super.onDestroy() }
}
