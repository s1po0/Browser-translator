package dev.rojink.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.rojink.browser.R
import dev.rojink.browser.extensions.InstalledExtension
import dev.rojink.browser.extensions.extensionSearchTerms
import dev.rojink.browser.extensions.installedExtensions
import kotlinx.coroutines.CancellationException

/** Scan only while the stores screen is open and in the foreground. Resume also catches changes in Mihon. */
@Composable internal fun rememberInstalledScan(request: Int): Result<Map<String, InstalledExtension>>? {
    val manager = LocalContext.current.applicationContext.packageManager
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember(manager) { mutableStateOf<Result<Map<String, InstalledExtension>>?>(null) }
    LaunchedEffect(manager, lifecycle, request) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            result = null
            result = try { Result.success(installedExtensions(manager)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    return result
}

@Composable internal fun InstalledExtensionsShortcut(scan: Result<Map<String, InstalledExtension>>?, onClick: () -> Unit) {
    OutlinedCard(onClick, Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(tr(R.string.store_device_installed)) },
            supportingContent = { Text(installedScanCaption(scan)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                headlineColor = MaterialTheme.colorScheme.onPrimaryContainer,
                supportingColor = MaterialTheme.colorScheme.onPrimaryContainer),
        )
    }
}

@Composable internal fun InstalledExtensionsPage(
    scan: Result<Map<String, InstalledExtension>>?, onScan: () -> Unit, onOpen: (InstalledExtension) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val visible = remember(scan, query) {
        val terms = extensionSearchTerms(query)
        scan?.getOrNull().orEmpty().values.filter { extension -> terms.all(extension::matches) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(R.string.store_device_scope), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(installedScanCaption(scan), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onScan, enabled = scan != null) { Text(tr(R.string.store_device_rescan)) }
        }
        when {
            scan == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            scan.isSuccess && !scan.getOrNull().isNullOrEmpty() -> {
                OutlinedTextField(query, { if (it.length <= 200) query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr(R.string.store_device_search)) })
                if (visible.isEmpty()) Text(tr(R.string.store_no_matches), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(visible, key = { it.packageName }) { extension ->
                        OutlinedCard(onClick = { onOpen(extension) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(extension.name, style = MaterialTheme.typography.titleMedium)
                                    Text(tr(R.string.store_local_version, extension.version), style = MaterialTheme.typography.bodySmall)
                                    Text(extension.packageName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                            }
                        }
                    }
                }
            }
            scan.isSuccess -> Text(tr(R.string.store_device_none), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun installedScanCaption(scan: Result<Map<String, InstalledExtension>>?): String = when {
    scan == null -> tr(R.string.store_device_scanning)
    scan.isFailure -> tr(R.string.store_device_failed)
    else -> tr(R.string.store_device_count, scan.getOrNull().orEmpty().size)
}
