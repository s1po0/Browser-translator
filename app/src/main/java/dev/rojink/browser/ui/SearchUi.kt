package dev.rojink.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.data.BrowserDao
import dev.rojink.browser.search.AddressSearchMode
import dev.rojink.browser.search.SEARCH_QUERY_LIMIT
import dev.rojink.browser.search.SearchEntry
import dev.rojink.browser.search.SearchSource
import dev.rojink.browser.search.findInSipan
import dev.rojink.browser.search.readSipanIndex
import dev.rojink.browser.search.searchQuery
import dev.rojink.browser.web.webUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun SipanSearchScreen(dao: BrowserDao, query: String, privateTab: Boolean,
    onSearch: (String) -> Unit, onOpen: (String) -> Unit, onWeb: (String) -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val palette = MaterialTheme.colorScheme
    var input by remember(query) { mutableStateOf(query) }
    val index by produceState<List<SearchEntry>?>(null, context) {
        value = withContext(Dispatchers.IO) { context.assets.open("sipan-index.tsv").bufferedReader().useLines(::readSipanIndex) }
    }
    // Private mode never subscribes to private library flows or scores their entries.
    val personal = if (privateTab) emptyList() else personalEntries(dao)
    val hits = key(query, index, personal, privateTab) {
        produceState<List<SearchEntry>?>(null) {
            value = index?.let { public -> withContext(Dispatchers.Default) { findInSipan(query, public, personal, privateTab) } }
        }.value
    }
    fun submit(action: (String) -> Unit) {
        val term = searchQuery(input)
        focus.clearFocus(); keyboard?.hide()
        action(term)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 710.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            OutlinedTextField(input, { input = it.take(SEARCH_QUERY_LIMIT) }, Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp).testTag("sipan-query"),
                singleLine = true, shape = RoundedCornerShape(17.dp), placeholder = { Text(tr(R.string.search_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search, autoCorrectEnabled = false), keyboardActions = KeyboardActions(onSearch = { submit(onSearch) }),
                trailingIcon = { IconButton(onClick = { submit(onSearch) }) { Icon(Icons.Default.Search, tr(R.string.search_on_device_title)) } })
            Row(Modifier.padding(start = 3.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ReaderGlyph(R.drawable.ic_reader_privacy, Modifier.size(17.dp), tint = palette.primary)
                Text(tr(if (privateTab) R.string.search_private_scope else R.string.search_scope), style = MaterialTheme.typography.labelMedium,
                    color = palette.onSurfaceVariant)
            }
            val results = hits
            val list = Modifier.fillMaxSize().then(if (!results.isNullOrEmpty()) Modifier.testTag("sipan-results") else Modifier)
            key(query) {
                LazyColumn(list, contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (input.isNotBlank()) item(key = "web") { WebSearchAction(privateTab) { submit(onWeb) } }
                    when {
                        query.isBlank() -> item { SearchStarter(onSearch) }
                        results == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        results.isEmpty() -> item { EmptyState(R.string.search_empty, R.string.search_empty_detail) }
                        else -> {
                            item { Text(tr(R.string.search_count, results.size), style = MaterialTheme.typography.labelLarge, color = palette.onSurfaceVariant) }
                            items(results, key = { it.url }) { result -> ResultCard(result) { onOpen(result.url) } }
                        }
                    }
                }
            }
        }
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

@Composable internal fun WebSearchBar(onDevice: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(color = palette.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("sipan-web-context")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Search, null, Modifier.size(17.dp), palette.primary)
            Text(tr(R.string.search_web_provider), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onDevice, modifier = Modifier.testTag("sipan-device-search")) { Text(tr(R.string.search_on_device)) }
        }
    }
}

@Composable private fun WebSearchAction(privateTab: Boolean, onWeb: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(onClick = onWeb, shape = RoundedCornerShape(17.dp), color = palette.primaryContainer,
        contentColor = palette.onPrimaryContainer, modifier = Modifier.fillMaxWidth().testTag("sipan-web-search-once")) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Search, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tr(R.string.search_web_action), style = MaterialTheme.typography.titleMedium)
                Text(tr(if (privateTab) R.string.search_web_private_detail else R.string.search_web_action_detail), style = MaterialTheme.typography.bodySmall)
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
@Composable internal fun SearchStarter(onSearch: (String) -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = palette.primaryContainer, contentColor = palette.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                Text(tr(R.string.sipan_search), style = MaterialTheme.typography.titleMedium)
            }
            Text(tr(R.string.search_starter), style = MaterialTheme.typography.bodySmall)
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
