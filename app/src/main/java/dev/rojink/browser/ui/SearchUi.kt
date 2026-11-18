package dev.rojink.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.data.BrowserDao
import dev.rojink.browser.search.AddressSearchMode
import dev.rojink.browser.search.SearchEntry
import dev.rojink.browser.search.SearchSource
import dev.rojink.browser.search.WebSearchCategory
import dev.rojink.browser.search.WebSearchFilters
import dev.rojink.browser.search.WebSearchRequest
import dev.rojink.browser.search.findInSipan
import dev.rojink.browser.search.readSipanIndex
import dev.rojink.browser.search.searchDraft
import dev.rojink.browser.search.searchQuery
import dev.rojink.browser.web.webUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class SearchLaunch(val request: WebSearchRequest = WebSearchRequest(""), val onDevice: Boolean = false)

@Composable internal fun SipanSearchScreen(dao: BrowserDao, initial: SearchLaunch, privateTab: Boolean,
    filters: WebSearchFilters, onFilters: () -> Unit, onOpen: (String) -> Unit, onWeb: (WebSearchRequest) -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val palette = MaterialTheme.colorScheme
    var input by remember(initial) { mutableStateOf(initial.request.query) }
    var category by remember(initial) { mutableStateOf(if (initial.onDevice) null else initial.request.category) }
    var refinements by remember(initial) { mutableStateOf(initial.request.refinements) }
    var refine by remember(initial) { mutableStateOf(false) }
    val term = searchQuery(input)
    val local = category == null
    val hits = if (local && term.isNotEmpty()) localMatches(dao, term, privateTab) else null
    fun hideKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun submitWeb(target: WebSearchCategory) {
        if (term.isEmpty()) return
        hideKeyboard()
        onWeb(WebSearchRequest(term, refinements = refinements).inCategory(target))
    }
    if (refine) WebRefinementsDialog(refinements, onClose = { refine = false }) {
        refinements = it
        refine = false
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 710.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(input, { input = searchDraft(it) },
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp).testTag("sipan-query"),
                singleLine = true, shape = RoundedCornerShape(17.dp),
                placeholder = { Text(tr(if (local) R.string.search_hint else R.string.search_online_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { category?.let(::submitWeb) ?: hideKeyboard() }),
                leadingIcon = { Icon(Icons.Default.Search, null, tint = palette.primary) },
                trailingIcon = if (input.isNotEmpty()) ({
                    IconButton(onClick = { input = "" }) { Icon(Icons.Default.Close, tr(R.string.clear)) }
                }) else null)
            SearchCategories(category, "sipan-scope", onSelect = { category = it }, onDevice = { category = null })
            LazyColumn(Modifier.fillMaxSize().then(if (!hits.isNullOrEmpty()) Modifier.testTag("sipan-results") else Modifier),
                contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (local) item(key = "privacy") {
                    Row(Modifier.padding(horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ReaderGlyph(R.drawable.ic_reader_privacy, Modifier.size(17.dp), tint = palette.primary)
                        Text(tr(if (privateTab) R.string.search_private_scope else R.string.search_scope),
                            style = MaterialTheme.typography.labelMedium, color = palette.onSurfaceVariant)
                    }
                }
                if (term.isNotEmpty()) item(key = "submit") {
                    WebSearchAction(privateTab, local) { submitWeb(category ?: WebSearchCategory.WEB) }
                } else if (!local) item(key = "disclosure") {
                    Text(tr(if (privateTab) R.string.search_web_private_detail else R.string.search_submit_disclosure),
                        Modifier.padding(horizontal = 3.dp), style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
                item(key = "filters") { WebFiltersButton(filters, onFilters, Modifier.fillMaxWidth()) }
                if (category?.supportsRefinements == true) item(key = "refine") {
                    WebRefinementsButton(refinements) { hideKeyboard(); refine = true }
                }
                if (term.isEmpty()) item(key = "starter") { SearchStarter(local = local, onSearch = { input = it }) }
                else if (local) when {
                    hits == null -> item(key = "waiting") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    hits.isEmpty() -> item(key = "none") { EmptyState(R.string.search_empty, R.string.search_empty_detail) }
                    else -> {
                        item(key = "count") { Text(tr(R.string.search_count, hits.size), style = MaterialTheme.typography.labelLarge, color = palette.onSurfaceVariant) }
                        items(hits, key = { it.url }) { result -> ResultCard(result) { onOpen(result.url) } }
                    }
                }
            }
        }
    }
}

@Composable private fun localMatches(dao: BrowserDao, term: String, privateTab: Boolean): List<SearchEntry>? {
    val context = LocalContext.current
    val index by produceState<List<SearchEntry>?>(null, context) {
        value = withContext(Dispatchers.IO) { context.assets.open("sipan-index.tsv").bufferedReader().useLines(::readSipanIndex) }
    }
    // Private mode neither subscribes to library flows nor scores personal entries.
    val personal = if (privateTab) emptyList() else personalEntries(dao)
    return key(term, index, personal) {
        produceState<List<SearchEntry>?>(null) {
            val catalog = index ?: return@produceState
            delay(150)
            value = withContext(Dispatchers.Default) { findInSipan(term, catalog, personal, privateTab) }
        }.value
    }
}

@Composable internal fun SearchModeDialog(onDismiss: () -> Unit, choose: (AddressSearchMode) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(tr(R.string.search_choose_title)) },
        text = { Text(tr(R.string.search_choose_detail)) },
        confirmButton = {
            Button(onClick = { choose(AddressSearchMode.WEB) }, modifier = Modifier.testTag("sipan-enable-web")) {
                Text(tr(R.string.search_use_web))
            }
        },
        dismissButton = {
            TextButton(onClick = { choose(AddressSearchMode.DEVICE) }, modifier = Modifier.testTag("sipan-keep-device")) {
                Text(tr(R.string.search_use_device))
            }
        })
}

@Composable private fun categoryName(category: WebSearchCategory) = tr(when (category) {
    WebSearchCategory.WEB -> R.string.search_category_web
    WebSearchCategory.IMAGES -> R.string.search_category_images
    WebSearchCategory.NEWS -> R.string.search_category_news
    WebSearchCategory.VIDEOS -> R.string.search_category_videos
})

@Composable private fun SearchCategories(category: WebSearchCategory?, tagPrefix: String,
    onSelect: (WebSearchCategory) -> Unit, onDevice: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (item in WebSearchCategory.entries) {
            FilterChip(selected = item == category, onClick = { onSelect(item) },
                label = { Text(categoryName(item)) }, modifier = Modifier.testTag("$tagPrefix-${item.name.lowercase()}"))
        }
        if (onDevice != null) FilterChip(selected = category == null, onClick = onDevice,
            label = { Text(tr(R.string.search_on_device)) }, modifier = Modifier.testTag("$tagPrefix-device"))
    }
}

@Composable internal fun WebSearchBar(request: WebSearchRequest, filters: WebSearchFilters, onFilters: () -> Unit,
    onEdit: () -> Unit, onCategory: (WebSearchCategory) -> Unit, onDevice: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    Surface(color = palette.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("sipan-web-context")) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 3.dp)) {
                    Text(tr(R.string.search_web_provider), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    val summary = refinementsLabel(request.refinements)
                    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.primary)
                }
                TextButton(onClick = onEdit, modifier = Modifier.testTag("sipan-edit-search")) { Text(tr(R.string.search_edit)) }
                TextButton(onClick = onDevice, modifier = Modifier.testTag("sipan-device-search")) { Text(tr(R.string.search_on_device)) }
            }
            SearchCategories(request.category, "sipan-result", onSelect = { if (it != request.category) onCategory(it) })
            WebFiltersButton(filters, onFilters, Modifier.fillMaxWidth())
            if (request.category == WebSearchCategory.IMAGES) {
                Text(tr(R.string.search_open_image_tip), Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun WebSearchAction(privateTab: Boolean, fromDevice: Boolean, onWeb: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(onClick = onWeb, shape = RoundedCornerShape(17.dp), color = palette.primaryContainer,
        contentColor = palette.onPrimaryContainer, modifier = Modifier.fillMaxWidth().testTag("sipan-web-search-once")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Search, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tr(if (fromDevice) R.string.search_web_action else R.string.search_submit_brave), style = MaterialTheme.typography.titleMedium)
                Text(tr(when {
                    privateTab -> R.string.search_web_private_detail
                    fromDevice -> R.string.search_web_action_detail
                    else -> R.string.search_submit_disclosure
                }), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
        }
    }
}

@Composable private fun personalEntries(dao: BrowserDao): List<SearchEntry> {
    val bookmarks by remember(dao) { dao.bookmarks() }.collectAsState(emptyList())
    val history by remember(dao) { dao.visits() }.collectAsState(emptyList())
    val indexed by remember(dao) { dao.searchPages() }.collectAsState(emptyList())
    return remember(bookmarks, history, indexed) {
        indexed.map { SearchEntry(it.url, it.title, it.summary, it.body, SearchSource.SAVED_PAGE) } +
            bookmarks.take(1000).map { SearchEntry(it.url, it.title, source = SearchSource.BOOKMARK) } +
            history.map { SearchEntry(it.url, it.title, source = SearchSource.HISTORY) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SearchStarter(local: Boolean = false, onOpen: (() -> Unit)? = null, onSearch: (String) -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = palette.secondaryContainer, contentColor = palette.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val headline = if (onOpen == null) R.string.search_try_topic else R.string.sipan_search
            val launcher = if (onOpen == null) Modifier else Modifier.clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onOpen).testTag("sipan-open-search")
            Row(launcher.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(headline), style = MaterialTheme.typography.titleMedium)
                    Text(tr(if (local) R.string.search_starter else R.string.search_home_detail), style = MaterialTheme.typography.bodySmall)
                }
                if (onOpen != null) Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(R.string.search_topic_manga, R.string.search_topic_sorani, R.string.search_topic_erbil).forEach { id ->
                    val term = tr(id)
                    SuggestionChip(onClick = { onSearch(term) }, label = { Text(term) }, colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = palette.surface, labelColor = palette.onSurface))
                }
            }
        }
    }
}

@Composable private fun ResultCard(entry: SearchEntry, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(onClick = onClick, color = palette.surface, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, palette.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val source = tr(when (entry.source) {
                    SearchSource.INDEX -> R.string.search_source_index
                    SearchSource.SAVED_PAGE -> R.string.search_source_saved
                    SearchSource.BOOKMARK -> R.string.bookmarks
                    SearchSource.HISTORY -> R.string.history
                })
                Text("${webUrl(entry.url)?.host.orEmpty()} · $source", style = MaterialTheme.typography.labelSmall,
                    color = palette.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.summary.isNotBlank()) Text(entry.summary, style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(17.dp), palette.primary)
        }
    }
}
