package dev.rojink.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.net.Uri
import android.graphics.Bitmap
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.rojink.browser.R
import dev.rojink.browser.data.BrowserDao
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.data.Visit
import dev.rojink.browser.data.SearchPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class BrowserTab(val incognito: Boolean) {
    val id = UUID.randomUUID().toString()
    val profile = if (incognito) "rojink_tmp_$id" else null
    var address by mutableStateOf("")
        internal set
    var title by mutableStateOf("")
        internal set
    var progress by mutableIntStateOf(100)
        internal set
    var canBack by mutableStateOf(false)
        internal set
    var canForward by mutableStateOf(false)
        internal set
    var failed by mutableStateOf(false)
        internal set
    var desktop by mutableStateOf(false)
        internal set
    var find by mutableStateOf(false)
    var findText by mutableStateOf("")
    var findMatches by mutableIntStateOf(0)
        internal set
    var findIndex by mutableIntStateOf(0)
        internal set
    var blocked by mutableIntStateOf(0)
        internal set
    internal val blockedCount = AtomicInteger()
    var view: WebView? = null
        internal set
    internal var defaultAgent: String? = null
    var cookies: CookieManager? = null
        internal set
    var revision = 0L
        internal set
    @Volatile internal var destroyed = false
}

interface DocumentFeatures {
    fun invalidate(tab: BrowserTab) {}
    fun foreground(tab: BrowserTab, visible: Boolean) {}
    fun closed(tab: BrowserTab) {}
    fun allowImageMenu(tab: BrowserTab): Boolean = true
    fun intercept(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse? = null
}

class Browser(
    private val context: Context,
    val dao: BrowserDao,
    val library: Library,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val tabs = mutableStateListOf(BrowserTab(false))
    var selected by mutableStateOf(tabs.first())
        private set
    var fullScreen by mutableStateOf<View?>(null)
        private set
    var imageMenu by mutableStateOf<String?>(null)
    var notice by mutableIntStateOf(0)
    var settings by mutableStateOf(BrowserSettings())
        private set
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var visible = false
    private var attached = true
    @Volatile private var filterTrackers = true
    @Volatile var features: DocumentFeatures = object : DocumentFeatures {}
    private var blockCounterUpdateScheduled = false
    val supportsPrivate get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    init {
        if (supportsPrivate) runCatching {
            // A killed process may not have had time to delete its isolated profiles.
            val store = ProfileStore.getInstance()
            store.allProfileNames.filter { it.startsWith("rojink_tmp_") }.forEach { store.deleteProfile(it) }
        }
    }

    fun canScan(tab: BrowserTab): Boolean = visible && attached && selected === tab

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) selected.view?.onResume() else selected.view?.onPause()
        features.foreground(selected, value && attached)
    }

    fun apply(settings: BrowserSettings) {
        this.settings = settings
        filterTrackers = settings.trackerBlocking
        tabs.forEach { tab -> tab.view?.settings?.textZoom = settings.textSize }
    }

    fun setAttached(value: Boolean) {
        if (value == attached) return
        attached = value
        features.foreground(selected, visible && attached)
    }

    fun newTab(privateTab: Boolean = false): Boolean {
        if (privateTab && !supportsPrivate) { notice = R.string.private_unsupported; return false }
        if (tabs.size >= 12) { notice = R.string.tab_limit; return false }
        val tab = BrowserTab(privateTab)
        tabs.add(tab)
        select(tab)
        return true
    }

    fun select(tab: BrowserTab) {
        if (selected === tab || tab !in tabs) return
        closeVideo()
        features.foreground(selected, false)
        selected.view?.onPause()
        selected = tab
        if (visible) tab.view?.onResume()
        features.foreground(tab, visible && attached)
    }

    fun close(tab: BrowserTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasSelected = selected === tab
        if (wasSelected) closeVideo()
        tabs.removeAt(idx)
        if (tabs.isEmpty()) tabs.add(BrowserTab(false))
        if (wasSelected) select(tabs[idx.coerceAtMost(tabs.lastIndex)])
        destroyTab(tab)
    }

    fun navigate(input: String): Boolean {
        val address = resolveLink(input) ?: return false.also { notice = R.string.address_error }
        val tab = selected
        val view = tab.view ?: try {
            makeView(tab)
        } catch (e: RuntimeException) {
            notice = if (tab.incognito) R.string.private_unsupported else R.string.webview_error
            return false
        }
        invalidate(tab)
        tab.address = address
        tab.progress = 0
        tab.failed = false
        view.loadUrl(address)
        return true
    }

    fun back(): Boolean {
        if (fullScreen != null) { closeVideo(); return true }
        if (selected.find) { find(false); return true }
        val view = selected.view ?: return false
        if (view.canGoBack()) {
            invalidate(selected)
            view.goBack()
            return true
        }
        return false
    }

    fun forward() { selected.view?.takeIf { it.canGoForward() }?.let { invalidate(selected); it.goForward() } }
    fun reload(tab: BrowserTab = selected) { tab.view?.let { invalidate(tab); it.reload() } }
    fun dismissImageMenu() { imageMenu = null }
    fun saveImage(url: String) {
        val tab = selected
        if (tab.incognito) { notice = R.string.private_no_save; return }
        val current = webUrl(url) ?: return
        if (current.host == "rojink.invalid") { notice = R.string.download_error; return }
        val ua = tab.view?.settings?.userAgentString ?: return
        val page = tab.address
        safely(R.string.download_error) { library.download(url, ua, page, null, null); notice = R.string.download_started }
    }

    fun stopLoading() { selected.view?.stopLoading(); selected.progress = 100 }

    fun home() {
        val tab = selected
        closeVideo()
        invalidate(tab)
        disposeView(tab)
        tab.address = ""
        tab.title = ""
        tab.progress = 100
        tab.canBack = false
        tab.canForward = false
        tab.failed = false
        tab.find = false
    }

    fun desktopMode() {
        val tab = selected
        tab.desktop = !tab.desktop
        tab.view?.let { configureDisplay(tab, it); reload(tab) }
    }

    fun find(show: Boolean) {
        val tab = selected
        tab.find = show
        if (!show) { tab.view?.clearMatches(); tab.findText = ""; tab.findMatches = 0; tab.findIndex = 0 }
    }

    fun queryFind(query: String) {
        val tab = selected
        tab.findText = query
        if (query.isEmpty()) { tab.view?.clearMatches(); tab.findMatches = 0; tab.findIndex = 0 }
        else tab.view?.findAllAsync(query)
    }
    fun nextFind(next: Boolean) { selected.view?.findNext(next) }

    fun bookmark() {
        val tab = selected
        val url = webUrl(tab.address)?.toString() ?: return
        if (tab.incognito) { notice = R.string.private_no_save; return }
        safely { dao.putBookmarks(listOf(dev.rojink.browser.data.Bookmark(url, tab.title.ifBlank { url }))); notice = R.string.bookmark_saved }
    }

    fun indexCurrentPage() {
        val tab = selected
        if (tab.incognito) { notice = R.string.private_no_save; return }
        val web = tab.view ?: return
        val revision = tab.revision
        val source = webUrl(tab.address)?.takeIf { it.query == null }?.newBuilder()?.fragment(null)?.build()
        if (source == null || tab.progress != 100 || tab.failed) { notice = R.string.index_page_unavailable; return }
        val script = runCatching { context.assets.open("index-page.js").bufferedReader().use { it.readText() } }.getOrNull()
        if (script == null) { notice = R.string.operation_failed; return }
        web.evaluateJavascript(script) { raw ->
            if (tab.destroyed || tab.revision != revision || selected !== tab || tab.view !== web) return@evaluateJavascript
            val data = raw?.takeIf { it.length <= 60_000 }?.let { runCatching { JSONObject(it) }.getOrNull() }
            val page = webUrl(data?.optString("url"))?.newBuilder()?.fragment(null)?.build()
            val title = data?.optString("title").orEmpty().trim().take(160).ifBlank { tab.title.take(160).ifBlank { source.host } }
            val text = data?.optString("body").orEmpty().trim().take(6000)
            if (page != source || text.isBlank()) { notice = R.string.index_page_unavailable; return@evaluateJavascript }
            val summary = data?.optString("summary").orEmpty().trim().take(300).ifBlank { text.take(260) }
            safely {
                dao.putSearchPage(SearchPage(source.toString(), title, summary, text))
                dao.trimSearchPages()
                notice = R.string.index_page_added
            }
        }
    }

    fun openLink(external: Boolean) {
        val url = webUrl(selected.address)?.toString() ?: return
        val intent = if (external) Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            else Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        if (runCatching { context.startActivity(Intent.createChooser(intent, null)) }.isFailure) notice = R.string.no_app
    }

    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            notice = R.string.cookies_cleared
        }
        WebStorage.getInstance().deleteAllData()
        tabs.filter { it.incognito }.forEach { it.cookies?.removeAllCookies(null) }
    }

    fun clearWebCache() { tabs.forEach { it.view?.clearCache(true) } }

    fun safely(onError: Int = R.string.operation_failed, action: suspend () -> Unit) {
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) { notice = onError }
        }
    }

    fun destroy() {
        closeVideo()
        tabs.forEach(::destroyTab)
        scope.cancel()
    }

    private fun invalidate(tab: BrowserTab) {
        tab.revision++
        features.invalidate(tab)
    }

    private fun destroyTab(tab: BrowserTab) {
        tab.destroyed = true
        features.closed(tab)
        val cookies = tab.cookies
        disposeView(tab)
        tab.profile?.let { name ->
            val removed = runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)
            if (!removed) {
                cookies?.removeAllCookies(null)
                scope.launch { delay(600); runCatching { ProfileStore.getInstance().deleteProfile(name) } }
            }
        }
    }

    private fun disposeView(tab: BrowserTab) {
        tab.view?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.destroy()
        }
        tab.view = null
        tab.cookies = null
    }

    fun closeVideo() {
        val old = fullScreenCallback
        fullScreenCallback = null
        fullScreen?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullScreen = null
        old?.onCustomViewHidden()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun makeView(tab: BrowserTab): WebView {
        val night = when (settings.theme) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val web = WebView(ContextThemeWrapper(context, if (night) R.style.Theme_RojInk_Dark else R.style.Theme_RojInk))
        try {
            tab.profile?.let { WebViewCompat.setProfile(web, it) }
            tab.cookies = if (tab.incognito) WebViewCompat.getProfile(web).cookieManager else CookieManager.getInstance()
            tab.cookies?.setAcceptThirdPartyCookies(web, false)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = !tab.incognito
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                safeBrowsingEnabled = true
                textZoom = settings.textSize
            }
            tab.defaultAgent = web.settings.userAgentString
            configureDisplay(tab, web)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
            }
            web.webViewClient = Navigation(tab)
            web.webChromeClient = Chrome(tab)
            web.setFindListener { active, count, complete ->
                if (complete && !tab.destroyed && tab.find) { tab.findMatches = count; tab.findIndex = if (count == 0) 0 else active + 1 }
            }
            web.setOnLongClickListener {
                val hit = web.hitTestResult
                val image = hit.extra?.takeIf { hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE }
                if (tab === selected && !tab.incognito && features.allowImageMenu(tab) && webUrl(image) != null) {
                    imageMenu = image
                    true
                } else false
            }
            web.setDownloadListener { url, ua, disposition, mime, _ ->
                if (tab.incognito) notice = R.string.private_no_save
                else if (webUrl(url) != null) safely(R.string.download_error) {
                    library.download(url, ua ?: web.settings.userAgentString, tab.address, disposition, mime)
                    notice = R.string.download_started
                } else notice = R.string.download_error
            }
            var startX = 0f
            var startY = 0f
            var tracking = false
            web.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; tracking = web.scrollY == 0 }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
                    MotionEvent.ACTION_UP -> {
                        val down = event.y - startY
                        if (tracking && down > 125 * web.resources.displayMetrics.density && down > 1.6 * abs(event.x - startX) && web.scrollY == 0) reload(tab)
                        tracking = false
                    }
                }
                false
            }
            tab.view = web
            return web
        } catch (e: RuntimeException) {
            web.destroy()
            throw e
        }
    }

    private fun configureDisplay(tab: BrowserTab, web: WebView) {
        web.settings.userAgentString = if (tab.desktop) {
            val version = Regex("Chrome/([0-9.]+)").find(tab.defaultAgent.orEmpty())?.groupValues?.get(1) ?: "140.0.0.0"
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        } else tab.defaultAgent.orEmpty()
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = tab.desktop
    }

    private inner class Navigation(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            val address = webUrl(target)
            if (address != null) {
                if (request.isForMainFrame && address.toString().substringBefore('#') != tab.address.substringBefore('#')) invalidate(tab)
                return false
            }
            // Websites cannot start a native component without a tap; no arbitrary intent:// extras.
            if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("mailto", "tel", "sms")) {
                if (runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }.isFailure) notice = R.string.no_app
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (tab.destroyed) return
            invalidate(tab)
            tab.failed = false
            tab.progress = 0
            url?.takeIf { webUrl(it) != null }?.let { tab.address = it }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (tab.destroyed) return
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
            val resolved = webUrl(url)?.toString() ?: return
            if (tab.address != resolved && webUrl(tab.address)?.origin() != webUrl(resolved)?.origin()) invalidate(tab)
            tab.address = resolved
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (tab.destroyed || url != view.url) return
            tab.title = view.title.orEmpty()
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
            tab.progress = 100
            if (tab.incognito || tab.failed) return
            val current = webUrl(url)?.toString() ?: return
            val title = tab.title
            scope.launch {
                try { dao.putVisit(Visit(current, title.ifBlank { current })); dao.trimVisits() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame && !tab.destroyed && request.url.toString() == tab.address) {
                tab.failed = true
                tab.progress = 100
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            if (tab.view === view) {
                invalidate(tab)
                disposeView(tab)
                tab.failed = true
                tab.progress = 100
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            features.intercept(tab, request)?.let { return it }
            if (!request.isForMainFrame && filterTrackers && request.url.host?.let(::isTracker) == true) {
                tab.blockedCount.incrementAndGet()
                view.post {
                    if (!blockCounterUpdateScheduled && !tab.destroyed) {
                        blockCounterUpdateScheduled = true
                        scope.launch {
                            delay(250)
                            tabs.forEach { it.blocked = it.blockedCount.get() }
                            blockCounterUpdateScheduled = false
                        }
                    }
                }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))
            }
            return null
        }
    }

    private inner class Chrome(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { if (!tab.failed && !tab.destroyed) tab.progress = newProgress }
        override fun onReceivedTitle(view: WebView, title: String?) { if (!tab.destroyed) tab.title = title.orEmpty() }
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (selected !== tab || fullScreen != null) { callback.onCustomViewHidden(); return }
            fullScreenCallback = callback
            fullScreen = view
        }
        override fun onHideCustomView() { if (selected === tab) closeVideo() }
    }
}
