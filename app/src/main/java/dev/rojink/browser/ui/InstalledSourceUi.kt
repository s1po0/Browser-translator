package dev.rojink.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.extensions.ExtensionStores
import dev.rojink.browser.extensions.InstalledExtension
import dev.rojink.browser.extensions.InstalledSourceIssue
import dev.rojink.browser.extensions.InstalledSourceLookup
import dev.rojink.browser.extensions.KEIYOUSHI_INDEX
import dev.rojink.browser.extensions.KEIYOUSHI_SIGNER
import dev.rojink.browser.extensions.SavedExtensionStore
import kotlinx.coroutines.CancellationException

/** A tap starts one cancellable lookup. Unambiguous links go straight to the reader. */
@Composable internal fun InstalledSourceDialog(
    extension: InstalledExtension,
    stores: ExtensionStores,
    approved: Result<List<SavedExtensionStore>>?,
    onAddStore: (String) -> Unit,
    onDismiss: () -> Unit,
    onReadWebsite: (String?) -> Unit,
) {
    var retry by remember(extension) { mutableIntStateOf(0) }
    var lookup by remember(extension, retry) { mutableStateOf<Result<InstalledSourceLookup>?>(null) }
    val open by rememberUpdatedState(onReadWebsite)
    LaunchedEffect(stores, extension, retry) {
        val result = try { Result.success(stores.lookupInstalled(extension, retry = retry != 0)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        val target = result.getOrNull()?.direct
        if (target == null) lookup = result else open(target.website.toString())
    }
    val resolved = lookup?.getOrNull()
    val issue = resolved?.issue
    val links = resolved?.links.orEmpty()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(extension.name) }, text = {
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                lookup == null -> item {
                    Text(tr(R.string.store_device_link_search))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                lookup?.isFailure == true -> item { StoreStatus(R.string.store_storage_failed, error = true) }
                issue != null -> item { StoreStatus(issue.message(), error = issue.isRetryable) }
            }
            if (links.isNotEmpty()) item { Text(tr(R.string.store_device_pick_website)) }
            items(links, key = { it.website.toString() }) { link ->
                OutlinedCard(onClick = { open(link.website.toString()) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(listOf(link.name, link.language).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall)
                        StoreAddress(link.website.toString(), lines = 3)
                        Text(link.storeName, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val records = approved?.getOrNull()
            if (issue?.isRetryable == true) item { TextButton({ retry++ }) { Text(tr(R.string.try_again)) } }
            if (issue != null && records != null) {
                if (extension.signer == KEIYOUSHI_SIGNER && records.none { it.indexUrl == KEIYOUSHI_INDEX }) {
                    item { TextButton({ onAddStore(KEIYOUSHI_INDEX) }) { Text(tr(R.string.store_add_kei)) } }
                }
                item { TextButton({ onAddStore("") }) { Text(tr(R.string.store_add)) } }
            }
        }
    }, confirmButton = {
        TextButton({ open(null) }) { Text(tr(R.string.store_device_use_link)) }
    }, dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } })
}

private val InstalledSourceIssue.isRetryable get() = this == InstalledSourceIssue.UNAVAILABLE || this == InstalledSourceIssue.SIGNER_CHANGED

private fun InstalledSourceIssue.message() = when (this) {
    InstalledSourceIssue.NO_STORES -> R.string.store_device_link_no_stores
    InstalledSourceIssue.NO_SIGNER_MATCH -> R.string.store_device_link_no_signer
    InstalledSourceIssue.NO_WEBSITE -> R.string.store_device_link_missing
    InstalledSourceIssue.UNAVAILABLE -> R.string.store_device_link_unavailable
    InstalledSourceIssue.SIGNER_CHANGED -> R.string.store_wrong_signer
}
