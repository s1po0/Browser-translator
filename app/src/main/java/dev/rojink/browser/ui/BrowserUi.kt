package dev.rojink.browser.ui

import android.app.DownloadManager
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.rojink.browser.R
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.data.Bookmark
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.web.Browser
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.webUrl
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private enum class Screen(val title: Int) {
    Browser(R.string.app_name), Tabs(R.string.tabs), Bookmarks(R.string.bookmarks), History(R.string.history),
    Downloads(R.string.downloads), Settings(R.string.settings), Privacy(R.string.privacy), About(R.string.about),
}

@Composable fun BrowserUi(
    browser: Browser,
    app: RojInkApp,
    settings: BrowserSettings,
    onTranslate: (() -> Unit)? = null,
    onPhoto: (() -> Unit)? = null,
    clearTranslations: suspend () -> Unit = { browser.dao.clearTranslations() },
    translationBar: @Composable () -> Unit = {},
) {
    var screen by remember { mutableStateOf(Screen.Browser) }
    var menu by remember { mutableStateOf(false) }
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
    val video = browser.fullScreen
    fun finishScreen() {
        screen = if (screen == Screen.About || screen == Screen.Privacy) Screen.Settings else Screen.Browser
    }
    BackHandler(enabled = video != null || screen != Screen.Browser || tab.find || tab.canBack) {
        if (video != null) browser.closeVideo() else if (screen != Screen.Browser) finishScreen() else browser.back()
    }
    LaunchedEffect(screen, video) { browser.setAttached(screen == Screen.Browser && video == null) }
    if (video != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { key(video) { NativeView(video, Modifier.fillMaxSize()) } }
        return
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { inset ->
        if (screen == Screen.Browser) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            Column(Modifier.fillMaxSize().padding(inset)) {
                if (tab.address.isEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 8.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_rojink), null, Modifier.size(32.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { browser.safely { app.settings.update { it.copy(uiLanguage = if (settings.uiLanguage == "ckb") "en" else "ckb") } } }) {
                            Text(if (IsSorani.current) "English" else "کوردی", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AddressField(tab, focus, Modifier.weight(1f)) {
                        if (browser.navigate(it)) { focusManager.clearFocus(); keyboard?.hide() }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr(R.string.menu)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            @Composable fun item(id: Int, enabled: Boolean = true, action: () -> Unit) {
                                DropdownMenuItem(text = { Text(tr(id)) }, enabled = enabled, onClick = { menu = false; action() })
                            }
                            val hasPage = tab.address.isNotEmpty()
                            item(R.string.new_tab) { browser.newTab() }
                            item(R.string.private_tab) { browser.newTab(true) }
                            item(R.string.home, hasPage) { browser.home() }
                            item(if (tab.progress < 100) R.string.stop else R.string.reload, hasPage) { if (tab.progress < 100) browser.stopLoading() else browser.reload() }
                            item(R.string.bookmark, hasPage && !tab.incognito) { browser.bookmark() }
                            item(R.string.bookmarks) { screen = Screen.Bookmarks }
                            item(R.string.history) { screen = Screen.History }
                            item(R.string.downloads) { screen = Screen.Downloads }
                            item(R.string.photo, onPhoto != null) { onPhoto?.invoke() }
                            item(R.string.find, hasPage) { browser.find(true) }
                            item(if (tab.desktop) R.string.mobile else R.string.desktop) { browser.desktopMode() }
                            item(R.string.share, hasPage) { browser.openLink(false) }
                            item(R.string.external, hasPage) { browser.openLink(true) }
                            item(R.string.settings) { screen = Screen.Settings }
                        }
                    }
                }
                if (tab.progress < 100) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
                if (tab.find) FindBar(browser, tab)
                translationBar()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val view = tab.view
                    when {
                        tab.failed -> EmptyState(R.string.page_error, null, Modifier.align(Alignment.Center), button = R.string.try_again) {
                            if (view == null) browser.navigate(tab.address) else browser.reload()
                        }
                        view != null && tab.address.isNotEmpty() -> key(tab.id, view) { NativeView(view, Modifier.fillMaxSize()) }
                        else -> HomeContent(tab, bookmarks, onLink = { focus.requestFocus(); keyboard?.show() }, onPhoto = onPhoto,
                            onDemo = { browser.navigate("https://rojink.invalid/demo/") }, onBookmark = { browser.navigate(it) })
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Dock(browser, onTabs = { screen = Screen.Tabs }, onTranslate = onTranslate)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(inset)) {
                ScreenHeader(screen.title, ::finishScreen)
                when (screen) {
                    Screen.Tabs -> TabGrid(browser, { browser.select(it); screen = Screen.Browser }) { privateTab -> if (browser.newTab(privateTab)) screen = Screen.Browser }
                    Screen.Bookmarks -> SavedPages(bookmarks.map { SavedPage(it.url, it.title) }, R.string.no_bookmarks, onOpen = { if (browser.navigate(it)) screen = Screen.Browser },
                        onRemove = { url -> browser.safely { browser.dao.removeBookmark(url) } })
                    Screen.History -> {
                        val flow = remember(browser) { browser.dao.visits() }
                        val history by flow.collectAsState(emptyList())
                        SavedPages(history.map { SavedPage(it.url, it.title) }, R.string.no_history, { if (browser.navigate(it)) screen = Screen.Browser })
                    }
                    Screen.Downloads -> DownloadScreen(browser)
                    Screen.Settings -> SettingsScreen(browser, app, settings, bookmarks, clearTranslations, { screen = Screen.Privacy }, { screen = Screen.About })
                    Screen.Privacy -> InfoScreen(R.string.privacy_intro, R.string.privacy_body)
                    Screen.About -> InfoScreen(R.string.app_name, R.string.about_body)
                    else -> Unit
                }
            }
        }
    }
}

@Composable private fun AddressField(tab: BrowserTab, focus: FocusRequester, modifier: Modifier, submit: (String) -> Unit) {
    var focused by remember(tab.id) { mutableStateOf(false) }
    var query by remember(tab.id) { mutableStateOf(tab.address) }
    LaunchedEffect(tab.address, focused) { if (!focused) query = tab.address }
    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused },
        singleLine = true, shape = RoundedCornerShape(19.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
        placeholder = { Text(tr(R.string.address), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
        trailingIcon = if (focused && query.isNotBlank()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, tr(R.string.clear)) } } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit(query) }))
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

@Composable private fun Dock(browser: Browser, onTabs: () -> Unit, onTranslate: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            val tab = browser.selected
            IconButton(browser::backAsUnit, enabled = tab.canBack || tab.find) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) }
            IconButton(browser::forward, enabled = tab.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, tr(R.string.forward)) }
            val tabLabel = tr(R.string.tabs)
            IconButton(onTabs, Modifier.semantics { contentDescription = "$tabLabel: ${browser.tabs.size}" }) {
                Box(Modifier.size(26.dp).border(1.4.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(browser.tabs.size.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onTranslate?.invoke() }, enabled = onTranslate != null && tab.view != null && !tab.failed && tab.progress == 100, shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp)) {
                Text("文", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)); Spacer(Modifier.width(7.dp)); Text(tr(R.string.translate), maxLines = 1)
            }
        }
    }
}
private fun Browser.backAsUnit() { back() }

@Composable private fun HomeContent(tab: BrowserTab, bookmarks: List<Bookmark>, onLink: () -> Unit, onPhoto: (() -> Unit)?, onDemo: () -> Unit, onBookmark: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp), modifier = Modifier.fillMaxSize()) {
        if (tab.incognito) {
            item { Surface(shape = RoundedCornerShape(25.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr(R.string.private_header), style = MaterialTheme.typography.headlineMedium)
                    Text(tr(R.string.private_copy), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onLink) { Text(tr(R.string.open_link)) }
                }
            } }
        } else {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = Ink) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        BookArt(Modifier.align(Alignment.TopEnd).size((maxWidth * .49f).coerceAtMost(210.dp)).padding(6.dp))
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Surface(color = Color(0xFF295B48), shape = RoundedCornerShape(50)) {
                                Text(tr(R.string.manga_badge), Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Lime, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(tr(R.string.hero_title), style = MaterialTheme.typography.displaySmall, color = Lime)
                            Text(tr(R.string.hero_body), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD3E6D0), modifier = Modifier.widthIn(max = 264.dp))
                            FilledTonalButton(onClick = onLink, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Apricot, contentColor = Ink)) {
                                Text(tr(R.string.open_link)); Spacer(Modifier.width(7.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (onPhoto != null) item { HomeAction("▧", R.string.photo, R.string.photo_tip, onPhoto) }
            item { HomeAction("↗", R.string.demo_title, R.string.demo_tip, onDemo) }
            item { Text(tr(R.string.quick_access), Modifier.padding(top = 7.dp), style = MaterialTheme.typography.titleLarge) }
            if (bookmarks.isEmpty()) item { Text(tr(R.string.no_bookmarks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(bookmarks.take(6), key = { it.url }) { page -> HomeAction("★", page.title, webUrl(page.url)?.host ?: page.url) { onBookmark(page.url) } }
        }
        item { Text(tr(R.string.blocked, tab.blocked), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun HomeAction(mark: String, title: Int, description: Int, action: () -> Unit) = HomeAction(mark, tr(title), tr(description), action)
@Composable private fun HomeAction(mark: String, title: String, description: String, action: () -> Unit) {
    Surface(onClick = action, shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(43.dp), contentAlignment = Alignment.Center) { Text(mark, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 23.sp) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun BookArt(modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 160f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val orbital = Color(0xFF6C9479)
        drawCircle(orbital, 58 * u, p(99f, 73f), style = Stroke(u))
        drawCircle(orbital.copy(alpha = .45f), 68 * u, p(99f, 73f), style = Stroke(u))
        for (i in 0 until 10) {
            val a = i * .62832
            drawCircle(Lime.copy(alpha = .47f), 1.65f * u, p(99f + 64 * cos(a).toFloat(), 73f + 64 * sin(a).toFloat()))
        }
        drawCircle(Apricot.copy(alpha = .95f), 15 * u, p(126f, 38f))
        val left = Path().apply {
            moveTo(53 * u, 65 * u); quadraticTo(76 * u, 58 * u, 98 * u, 78 * u); lineTo(98 * u, 117 * u)
            quadraticTo(75 * u, 100 * u, 53 * u, 106 * u); close()
        }
        val right = Path().apply {
            moveTo(101 * u, 78 * u); quadraticTo(122 * u, 59 * u, 144 * u, 65 * u); lineTo(144 * u, 106 * u)
            quadraticTo(122 * u, 100 * u, 101 * u, 117 * u); close()
        }
        drawPath(left, Lime); drawPath(right, Color(0xFFC3E5AE))
        listOf(76f, 83f, 90f).forEach { y ->
            drawLine(Moss, p(62f, y), p(90f, y + 8), 1.5f * u)
            drawLine(Moss, p(110f, y + 8), p(135f, y), 1.5f * u)
        }
        drawRoundRect(Color(0xFF46836A), p(40f, 36f), Size(38 * u, 23 * u), CornerRadius(7 * u))
        listOf(49f, 58f, 67f).forEach { drawCircle(Lime, 1.9f * u, p(it, 47f)) }
    }
}

@Composable internal fun ScreenHeader(title: Int, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) }
        Text(tr(title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 7.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun TabGrid(browser: Browser, onSelect: (BrowserTab) -> Unit, onAdd: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onAdd(false) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(tr(R.string.new_tab)) }
        OutlinedButton(onClick = { onAdd(true) }, modifier = Modifier.weight(1f)) { Text(tr(R.string.private_tab)) }
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(147.dp), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(browser.tabs, key = { it.id }) { tab ->
            val current = tab === browser.selected
            val host = webUrl(tab.address)?.host.orEmpty()
            Surface(onClick = { onSelect(tab) }, shape = RoundedCornerShape(20.dp),
                color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (current) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tab.incognito) "◈" else host.firstOrNull()?.uppercaseChar()?.toString() ?: "↗", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { browser.close(tab) }) { Icon(Icons.Default.Close, tr(R.string.close)) }
                    }
                    Text(tab.title.ifBlank { if (tab.incognito) tr(R.string.private_tab) else tr(R.string.new_tab) }, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (host.isNotBlank()) Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
