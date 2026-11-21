package dev.rojink.browser.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.web.Browser
import dev.rojink.browser.web.resolveLink
import dev.rojink.browser.web.webUrl

@Composable internal fun SourceReaderPage(
    browser: Browser, onClose: () -> Unit, onTranslate: (() -> Unit)?, translationActive: Boolean,
    translationBar: @Composable () -> Unit,
) {
    val tab = browser.selected
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by rememberSaveable(tab.id) { mutableStateOf(false) }
    var address by rememberSaveable(tab.id) { mutableStateOf("") }
    val home = tab.address.isEmpty()
    fun open(input: String) {
        if (browser.navigate(input)) {
            editing = false
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    fun paste() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = runCatching { clipboard?.primaryClip }.getOrNull()
        val link = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let(::resolveLink)
        if (link == null) browser.notice = R.string.paste_missing else open(link)
    }
    Column(Modifier.fillMaxSize().testTag("source-reader")) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.Close, tr(R.string.source_reader_close)) }
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.source_reader), style = MaterialTheme.typography.titleMedium)
                    val caption = webUrl(tab.address)?.host
                    if (caption != null) Text(caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                }
                if (tab.incognito) ReaderGlyph(R.drawable.ic_reader_privacy, Modifier.size(19.dp), tr(R.string.private_tab))
                if (!home) {
                    IconButton({ address = tab.address; editing = !editing }) { Icon(Icons.Default.Add, tr(R.string.source_reader_change_link)) }
                    IconButton({ if (tab.progress < 100) browser.stopLoading() else browser.reload() }) {
                        if (tab.progress < 100) Icon(Icons.Default.Close, tr(R.string.stop))
                        else Icon(Icons.Default.Refresh, tr(R.string.reload))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (home || editing) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (home) {
                    Text(tr(R.string.source_reader_filter), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(tr(R.string.source_reader_intro), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(address, { if (it.length <= 4096) address = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("source-reader-link"), label = { Text(tr(R.string.source_reader_link)) },
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open(address) }))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open(address) }, enabled = address.isNotBlank()) { Text(tr(R.string.go)) }
                    FilledTonalButton(onClick = ::paste) { Text(tr(R.string.paste_open)) }
                }
            }
        }
        if (!home) {
            if (tab.progress < 100) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
            translationBar()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val view = tab.view
                if (tab.failed) EmptyState(R.string.page_error, modifier = Modifier.align(Alignment.Center), button = R.string.try_again) {
                    if (view == null) open(tab.address) else browser.reload()
                } else if (view != null) key(tab.id, view) { NativeView(view, Modifier.fillMaxSize()) }
            }
            if (!editing) Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton({ browser.back() }, enabled = tab.canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.back)) }
                    IconButton(browser::forward, enabled = tab.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, tr(R.string.forward)) }
                    val colors = if (translationActive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else ButtonDefaults.buttonColors()
                    Button(onClick = { onTranslate?.invoke() }, modifier = Modifier.weight(1f).testTag("source-reader-translate"),
                        enabled = onTranslate != null && tab.view != null && !tab.failed && tab.progress == 100, colors = colors) {
                        Text(tr(R.string.translate))
                    }
                    // Google Lens reads stylized comic lettering the on-device models can miss.
                    IconButton({ browser.openInLens() }, modifier = Modifier.testTag("source-reader-lens"),
                        enabled = tab.view != null && !tab.failed && tab.progress == 100) {
                        Icon(Icons.Default.Search, tr(R.string.google_lens))
                    }
                }
            }
        }
    }
}
