package dev.rojink.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.ui.BrowserUi
import dev.rojink.browser.ui.RojInkTheme
import dev.rojink.browser.web.Browser

class MainActivity : ComponentActivity() {
    private lateinit var browser: Browser
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RojInkApp
        browser = Browser(this, app.dao, app.library)
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
            RojInkTheme(settings) { BrowserUi(browser, app, settings) }
        }
    }
    override fun onResume() { super.onResume(); browser.setVisible(true) }
    override fun onPause() { browser.setVisible(false); super.onPause() }
    override fun onDestroy() { browser.destroy(); super.onDestroy() }
}
