package dev.rojink.browser.ui

import android.app.DownloadManager
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.rojink.browser.R
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.search.AddressSearchMode
import dev.rojink.browser.search.WebSearchCategory
import dev.rojink.browser.search.WebSearchFilters
import dev.rojink.browser.search.WebSearchRequest
import dev.rojink.browser.search.forTab
import dev.rojink.browser.web.AddressInput
import dev.rojink.browser.web.addressInput
import dev.rojink.browser.web.Browser
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.resolveLink
import dev.rojink.browser.web.webUrl
import kotlinx.coroutines.delay

private enum class Screen(val title: Int) {
    Browser(R.string.app_name), Search(R.string.sipan_search), Tabs(R.string.tabs), Bookmarks(R.string.bookmarks), History(R.string.history),
    Downloads(R.string.downloads), Settings(R.string.settings), Privacy(R.string.privacy), About(R.string.about),
    ExtensionStores(R.string.extension_stores), SourceReader(R.string.source_reader),
}

@Composable fun BrowserUi(
    browser: Browser,
    app: RojInkApp,
    settings: BrowserSettings,
    onTranslate: (() -> Unit)? = null,
    onPhoto: (() -> Unit)? = null,
    clearTranslations: suspend () -> Unit = { browser.dao.clearTranslations() },
    translationActive: Boolean = false,
    translationBar: @Composable () -> Unit = {},
) {
    var screen by remember { mutableStateOf(Screen.Browser) }
    var storesBack by remember { mutableStateOf(Screen.Browser) }
    var readerBack by remember { mutableStateOf(Screen.Browser) }
    val bookmarksFlow = remember(browser) { browser.dao.bookmarks() }
    val bookmarks by bookmarksFlow.collectAsState(emptyList())
    val snackbar = remember { SnackbarHostState() }
    val noticeId = browser.notice
    val noticeText = if (noticeId != 0) tr(noticeId) else ""
    LaunchedEffect(noticeId) {
        if (noticeId != 0) {
            snackbar.currentSnackbarData?.dismiss()
            try { snackbar.showSnackbar(noticeText) } finally { if (browser.notice == noticeId) browser.notice = 0 }
        }
    }
    val tab = browser.selected
    var searchLaunch by remember(tab.id) { mutableStateOf(SearchLaunch()) }
    var searchChoice by remember(tab.id) { mutableStateOf<String?>(null) }
    var showSearchFilters by remember(tab.id) { mutableStateOf(false) }
    val searchMode = settings.addressSearch.forTab(tab.incognito)
    val webFilters = if (tab.incognito) tab.privateSearchFilters else settings.webSearchFilters
    val webSearch = tab.displayedWebSearch()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val video = browser.fullScreen
    fun hideKeyboard() { focusManager.clearFocus(); keyboard?.hide() }
    fun navigate(input: String) {
        if (browser.navigate(input)) { hideKeyboard(); screen = Screen.Browser }
    }
    fun showSearch(request: WebSearchRequest, onDevice: Boolean = false) {
        browser.cancelPendingSearch()
        searchLaunch = SearchLaunch(request, onDevice)
        hideKeyboard()
        screen = Screen.Search
    }
    fun searchDevice(input: String) = showSearch(WebSearchRequest(input), onDevice = true)
    fun searchWeb(request: WebSearchRequest, filters: WebSearchFilters = webFilters) {
        hideKeyboard()
        browser.safely(R.string.search_filters_failed) {
            if (browser.searchWeb(request, filters)) screen = Screen.Browser
        }
    }
    fun configureSearch() {
        browser.cancelPendingSearch()
        hideKeyboard()
        showSearchFilters = true
    }
    fun openReader(url: String? = null) {
        if (browser.openSourceReader(url)) {
            readerBack = screen
            hideKeyboard()
            screen = Screen.SourceReader
        }
    }
    fun open(target: Screen) {
        if (target == Screen.SourceReader) openReader()
        else if (target == Screen.Search) showSearch(webSearch ?: WebSearchRequest(""), onDevice = tab.incognito)
        else {
            browser.cancelPendingSearch()
            if (target == Screen.ExtensionStores) storesBack = screen
            screen = target
        }
    }
    fun submit(input: String) {
        when (val target = addressInput(input)) {
            is AddressInput.Link -> navigate(target.url)
            is AddressInput.Query -> when (searchMode) {
                AddressSearchMode.ASK -> {
                    searchDevice(target.text)
                    searchChoice = target.text
                }
                AddressSearchMode.DEVICE -> searchDevice(target.text)
                AddressSearchMode.WEB -> searchWeb(WebSearchRequest(target.text))
            }
            null -> browser.notice = R.string.address_error
        }
    }
    fun finishScreen() {
        browser.cancelPendingSearch()
        screen = when (screen) {
            Screen.About, Screen.Privacy -> Screen.Settings
            Screen.ExtensionStores -> storesBack
            Screen.SourceReader -> { browser.closeSourceReader(); readerBack }
            else -> Screen.Browser
        }
    }
    BackHandler(enabled = video != null || screen != Screen.Browser || tab.find || tab.canBack) {
        when {
            video != null -> browser.closeVideo()
            screen == Screen.SourceReader -> if (!browser.back()) finishScreen()
            screen != Screen.Browser -> finishScreen()
            else -> browser.back()
        }
    }
    DisposableEffect(screen, video) {
        browser.setAttached((screen == Screen.Browser || screen == Screen.SourceReader) && video == null)
        onDispose { browser.setAttached(false) }
    }
    if (video != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            tab.view?.let { view -> key(tab.id, view) { NativeView(view, Modifier.fillMaxSize()) } }
            key(video) { NativeView(video, Modifier.fillMaxSize().background(Color.Black)) }
        }
        return
    }
    browser.imageMenu?.let { url ->
        AlertDialog(onDismissRequest = browser::dismissImageMenu, title = { Text(tr(R.string.image_options)) },
            text = {
                Column {
                    Text(webUrl(url)?.host.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                    if (!tab.incognito) TextButton(onClick = browser::saveImage, modifier = Modifier.testTag("reader-save-image")) {
                        Text(tr(R.string.save_original_image))
                    }
                }
            },
            confirmButton = {
                Button(onClick = browser::openImage, modifier = Modifier.testTag("reader-open-image")) { Text(tr(R.string.open_image)) }
            },
            dismissButton = { TextButton(onClick = browser::dismissImageMenu) { Text(tr(R.string.cancel)) } })
    }
    searchChoice?.let { query ->
        SearchModeDialog(onDismiss = { searchChoice = null }) { mode ->
            searchChoice = null
            browser.safely { app.settings.update { it.copy(addressSearch = mode) } }
            if (mode == AddressSearchMode.WEB) searchWeb(WebSearchRequest(query))
        }
    }
    if (showSearchFilters) WebFiltersDialog(webFilters, tab.incognito, onClose = { showSearchFilters = false }) { filters ->
        showSearchFilters = false
        if (filters != webFilters) {
            if (tab.incognito) tab.privateSearchFilters = filters
            else browser.safely { app.settings.update { it.copy(webSearchFilters = filters) } }
            if (screen == Screen.Browser && webSearch != null) searchWeb(webSearch, filters)
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { inset ->
        if (screen == Screen.Browser) {
            val focus = remember { FocusRequester() }
            val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
            val home = tab.address.isEmpty()
            val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            fun pasteLink() {
                // Never read the clipboard in the background or submit it as a search term.
                val clip = runCatching { clipboard?.primaryClip }.getOrNull()
                val link = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let(::resolveLink)
                if (link != null) navigate(link) else {
                    browser.notice = R.string.paste_missing
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
            Column(Modifier.fillMaxSize().padding(inset).consumeWindowInsets(inset).imePadding()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        if (home) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 5.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(Modifier.weight(1f))
                            val sorani = IsSorani.current
                            FilledTonalButton(onClick = { browser.safely { app.settings.update { it.copy(uiLanguage = if (sorani) "en" else "ckb") } } },
                                modifier = Modifier.testTag("reader-ui-language"), shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
                                Text(if (sorani) "English" else "کوردی", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                            BrowserMenu(browser, ::open, onPhoto, ::configureSearch)
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = if (home) 16.dp else 4.dp, top = 3.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AddressField(tab, focus, searchMode, Modifier.weight(1f), onReload = {
                                if (tab.progress < 100) browser.stopLoading() else if (tab.view == null) navigate(tab.address) else browser.reload()
                            }, submit = ::submit)
                            if (!home) BrowserMenu(browser, ::open, onPhoto, ::configureSearch)
                        }
                    }
                }
                if (webSearch != null) WebSearchBar(webSearch, webFilters, ::configureSearch,
                    onEdit = { showSearch(webSearch) }, onCategory = { searchWeb(webSearch.inCategory(it)) },
                    onDevice = { searchDevice(webSearch.query) }, directImageMode = tab.directImagePage == tab.address,
                    onDirectImages = if (webSearch.category == WebSearchCategory.IMAGES && !tab.failed) ({
                        hideKeyboard()
                        browser.toggleDirectSearchImages()
                    }) else null)
                if (tab.progress < 100) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
                if (tab.find) FindBar(browser, tab)
                translationBar()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val view = tab.view
                    when {
                        tab.failed -> EmptyState(if (webSearch == null) R.string.page_error else R.string.search_web_failed,
                            if (webSearch == null) null else R.string.search_web_failed_detail, Modifier.align(Alignment.Center), button = R.string.try_again) {
                            if (view == null) browser.navigate(tab.address) else browser.reload()
                        }
                        view != null && tab.address.isNotEmpty() -> key(tab.id, view) { NativeView(view, Modifier.fillMaxSize()) }
                        else -> ReaderHome(tab, bookmarks, settings.target, onPaste = ::pasteLink, onPhoto = onPhoto,
                            onDemo = { navigate("https://rojink.invalid/demo/") }, onSaved = { screen = Screen.Bookmarks }, onBookmark = ::navigate,
                            onSearch = { showSearch(WebSearchRequest(it), onDevice = tab.incognito) })
                    }
                }
                if (!imeOpen) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Dock(browser, { screen = it }, onTranslate, translationActive)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(inset).consumeWindowInsets(inset).imePadding()) {
                if (screen != Screen.ExtensionStores && screen != Screen.SourceReader) ScreenHeader(screen.title, ::finishScreen)
                when (screen) {
                    Screen.Search -> key(tab.id) {
                        SipanSearchScreen(browser.dao, searchLaunch, tab.incognito, webFilters, ::configureSearch,
                            onOpen = ::navigate, onWeb = { searchWeb(it) })
                    }
                    Screen.Tabs -> TabGrid(browser, { browser.select(it); screen = Screen.Browser }) { privateTab -> if (browser.newTab(privateTab)) screen = Screen.Browser }
                    Screen.Bookmarks -> SavedPages(bookmarks.map { SavedPage(it.url, it.title) }, R.string.no_bookmarks, onOpen = { if (browser.navigate(it)) screen = Screen.Browser },
                        onRemove = { url -> browser.safely { browser.dao.removeBookmark(url) } })
                    Screen.History -> {
                        val flow = remember(browser) { browser.dao.visits() }
                        val history by flow.collectAsState(emptyList())
                        SavedPages(history.map { SavedPage(it.url, it.title) }, R.string.no_history, { if (browser.navigate(it)) screen = Screen.Browser })
                    }
                    Screen.Downloads -> DownloadScreen(browser)
                    Screen.Settings -> SettingsScreen(browser, app, settings, bookmarks, clearTranslations,
                        { screen = Screen.Privacy }, { screen = Screen.About }, { open(Screen.ExtensionStores) })
                    Screen.ExtensionStores -> ExtensionStoresScreen(app.extensionStores, app.extensionInstaller, ::finishScreen, ::openReader)
                    Screen.SourceReader -> SourceReaderPage(browser, ::finishScreen, onTranslate, translationActive, translationBar)
                    Screen.Privacy -> InfoScreen(R.string.privacy_intro, R.string.privacy_body)
                    Screen.About -> InfoScreen(R.string.app_name, R.string.about_body)
                    else -> Unit
                }
            }
        }
    }
}

@Composable private fun BrowserMenu(browser: Browser, open: (Screen) -> Unit, onPhoto: (() -> Unit)?, configureSearch: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val tab = browser.selected
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr(R.string.menu)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            @Composable fun item(id: Int, enabled: Boolean = true, action: () -> Unit) {
                DropdownMenuItem(text = { Text(tr(id)) }, enabled = enabled, onClick = { menu = false; action() })
            }
            item(R.string.sipan_search) { open(Screen.Search) }
            item(R.string.search_filters, action = configureSearch)
            item(R.string.new_tab) { browser.newTab() }
            item(R.string.private_tab) { browser.newTab(true) }
            if (tab.address.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 5.dp))
                item(R.string.home) { browser.home() }
                item(R.string.bookmark, !tab.incognito) { browser.bookmark() }
                if (!tab.incognito) item(R.string.index_page, tab.progress == 100 && !tab.failed && webUrl(tab.address)?.query == null) { browser.indexCurrentPage() }
                item(R.string.find) { browser.find(true) }
                item(if (tab.desktop) R.string.mobile else R.string.desktop) { browser.desktopMode() }
                item(R.string.share) { browser.openLink(false) }
                item(R.string.external) { browser.openLink(true) }
                if (browser.settings.trackerBlocking) Text(tr(R.string.blocked, tab.blocked), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 5.dp))
            item(R.string.bookmarks) { open(Screen.Bookmarks) }
            item(R.string.history) { open(Screen.History) }
            item(R.string.downloads) { open(Screen.Downloads) }
            item(R.string.extension_stores) { open(Screen.ExtensionStores) }
            item(R.string.source_reader) { open(Screen.SourceReader) }
            if (onPhoto != null) item(R.string.photo, action = onPhoto)
            item(R.string.settings) { open(Screen.Settings) }
        }
    }
}

@Composable private fun AddressField(tab: BrowserTab, focus: FocusRequester, searchMode: AddressSearchMode,
    modifier: Modifier, onReload: () -> Unit, submit: (String) -> Unit,
) {
    var focused by remember(tab.id) { mutableStateOf(false) }
    var query by remember(tab.id) { mutableStateOf(tab.address) }
    LaunchedEffect(tab.address, focused) { if (!focused) query = tab.address }
    val hint = when (searchMode) {
        AddressSearchMode.WEB -> R.string.address_web
        AddressSearchMode.DEVICE -> R.string.address_device
        AddressSearchMode.ASK -> R.string.address
    }
    OutlinedTextField(value = query, onValueChange = { query = it },
        modifier = modifier.testTag("reader-address").focusRequester(focus).onFocusChanged { focused = it.isFocused },
        singleLine = true, shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
        placeholder = { Text(tr(hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            if (tab.incognito) ReaderGlyph(R.drawable.ic_reader_privacy, Modifier.size(21.dp), tr(R.string.private_tab), MaterialTheme.colorScheme.primary)
            else Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingIcon = when {
            focused && query.isNotBlank() -> ({
                Row {
                    if (query != tab.address) IconButton(onClick = { submit(query) }) {
                        if (addressInput(query) is AddressInput.Query) Icon(Icons.Default.Search,
                            tr(if (searchMode == AddressSearchMode.WEB) R.string.search_web_action else R.string.sipan_search))
                        else Icon(Icons.AutoMirrored.Filled.ArrowForward, tr(R.string.go))
                    }
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, tr(R.string.clear)) }
                }
            })
            !focused && tab.address.isNotEmpty() -> ({
                IconButton(onReload) {
                    if (tab.progress < 100) Icon(Icons.Default.Close, tr(R.string.stop)) else Icon(Icons.Default.Refresh, tr(R.string.reload))
                }
            })
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit(query) }))
}

@Composable private fun FindBar(browser: Browser, tab: BrowserTab) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(tab.findText, browser::queryFind, Modifier.weight(1f), singleLine = true, placeholder = { Text(tr(R.string.find)) })
        if (tab.findText.isNotBlank()) Text(tr(R.string.matches, tab.findIndex, tab.findMatches), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium)
        IconButton({ browser.nextFind(false) }, enabled = tab.findMatches > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) }
        IconButton({ browser.nextFind(true) }, enabled = tab.findMatches > 0) { Icon(Icons.AutoMirrored.Filled.ArrowForward, tr(R.string.forward)) }
        IconButton({ browser.find(false) }) { Icon(Icons.Default.Close, tr(R.string.close)) }
    }
}

@Composable private fun Dock(browser: Browser, open: (Screen) -> Unit, onTranslate: (() -> Unit)?, translationActive: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val tab = browser.selected
    Surface(color = scheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.address.isEmpty()) {
                DockShortcut(R.string.tabs, Modifier.weight(1f), { open(Screen.Tabs) }) { TabCounter(browser.tabs.size) }
                DockShortcut(R.string.bookmarks, Modifier.weight(1f), { open(Screen.Bookmarks) }) { Icon(Icons.Default.Star, null, Modifier.size(21.dp)) }
                DockShortcut(R.string.settings, Modifier.weight(1f), { open(Screen.Settings) }) { Icon(Icons.Default.Settings, null, Modifier.size(21.dp)) }
            } else {
                IconButton({ browser.back() }, enabled = tab.canBack || tab.find) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) }
                IconButton(browser::forward, enabled = tab.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, tr(R.string.forward)) }
                val tabLabel = tr(R.string.tabs)
                IconButton({ open(Screen.Tabs) }, Modifier.semantics { contentDescription = "$tabLabel: ${browser.tabs.size}" }) { TabCounter(browser.tabs.size) }
                val colors = if (translationActive) ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)
                    else ButtonDefaults.buttonColors()
                Button(onClick = { onTranslate?.invoke() }, enabled = onTranslate != null && tab.view != null && !tab.failed && tab.progress == 100,
                    shape = RoundedCornerShape(16.dp), colors = colors, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reader-translate"),
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)) {
                    ReaderGlyph(R.drawable.ic_reader_translate, Modifier.size(22.dp))
                    Spacer(Modifier.width(7.dp)); Text(tr(R.string.translate), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable private fun DockShortcut(label: Int, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { icon() }
        Text(tr(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun TabCounter(count: Int) {
    val color = LocalContentColor.current
    Box(Modifier.size(24.dp).border(1.35.dp, color, RoundedCornerShape(7.dp)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(count.toString(), color = color, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun ScreenHeader(title: Int, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FilledTonalIconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) }
            Text(tr(title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun TabGrid(browser: Browser, onSelect: (BrowserTab) -> Unit, onAdd: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onAdd(false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(4.dp)); Text(tr(R.string.new_tab))
        }
        FilledTonalButton(onClick = { onAdd(true) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            ReaderGlyph(R.drawable.ic_reader_privacy, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(tr(R.string.private_tab))
        }
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(147.dp), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(browser.tabs, key = { it.id }) { tab ->
            val current = tab === browser.selected
            val host = if (tab.incognito) "" else webUrl(tab.address)?.host.orEmpty()
            Surface(onClick = { onSelect(tab) }, modifier = Modifier.semantics { selected = current }, shape = RoundedCornerShape(20.dp),
                color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tab.incognito) "◈" else host.firstOrNull()?.uppercaseChar()?.toString() ?: "↗", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { browser.close(tab) }) { Icon(Icons.Default.Close, tr(R.string.close)) }
                    }
                    Text(if (tab.incognito) tr(R.string.private_tab) else tab.title.ifBlank { tr(R.string.new_tab) }, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (host.isNotBlank()) Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (current) Text(tr(R.string.tab_selected), Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

private data class SavedPage(val url: String, val title: String)
@Composable private fun SavedPages(entries: List<SavedPage>, empty: Int, onOpen: (String) -> Unit, onRemove: ((String) -> Unit)? = null) {
    if (entries.isEmpty()) { EmptyState(empty); return }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        items(entries, key = { it.url }) { page ->
            ListItem(headlineContent = { Text(page.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(webUrl(page.url)?.host ?: page.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = onRemove?.let { action -> { IconButton(onClick = { action(page.url) }) { Icon(Icons.Default.Close, tr(R.string.remove)) } } },
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(page.url) })
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable private fun DownloadScreen(browser: Browser) {
    val downloads by browser.library.downloads.collectAsState(emptyList())
    val context = LocalContext.current
    if (downloads.isEmpty()) { EmptyState(R.string.no_downloads); return }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        items(downloads, key = { it.id }) { item ->
            val status by produceState(DownloadManager.STATUS_PENDING, item.id) {
                do {
                    value = runCatching { browser.library.status(item.id) }.getOrDefault(0)
                    if (value == 0 || value == DownloadManager.STATUS_SUCCESSFUL || value == DownloadManager.STATUS_FAILED) break
                    delay(1250)
                } while (true)
            }
            val ready = status == DownloadManager.STATUS_SUCCESSFUL
            val label = if (ready) R.string.download_complete else if (status == 0 || status == DownloadManager.STATUS_FAILED) R.string.download_failed else R.string.download_pending
            ListItem(headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(tr(label)) },
                trailingContent = { IconButton({ browser.safely { browser.library.removeDownload(item.id) } }) { Icon(Icons.Default.Close, tr(R.string.remove)) } },
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = ready) { browser.safely(R.string.no_app) { context.startActivity(browser.library.openDownload(item.id)) } })
        }
    }
}

@Composable internal fun EmptyState(title: Int, body: Int? = null, modifier: Modifier = Modifier, button: Int? = null, action: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr(title), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) Text(tr(body), style = MaterialTheme.typography.bodyMedium)
        if (button != null && action != null) Button(onClick = action) { Text(tr(button)) }
    }
}

@Composable internal fun NativeView(view: View, modifier: Modifier) {
    AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = modifier)
}

@Composable private fun InfoScreen(title: Int, body: Int) {
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Text(tr(title), style = MaterialTheme.typography.headlineMedium) }
        item { Text(tr(body), style = MaterialTheme.typography.bodyLarge) }
    }
}
