package dev.rojink.browser.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.extensions.CatalogExtension
import dev.rojink.browser.extensions.CatalogWarning
import dev.rojink.browser.extensions.ExtensionCatalog
import dev.rojink.browser.extensions.ExtensionInstaller
import dev.rojink.browser.extensions.ExtensionStores
import dev.rojink.browser.extensions.ExtensionPresence
import dev.rojink.browser.extensions.InstalledExtension
import dev.rojink.browser.extensions.KEIYOUSHI_INDEX
import dev.rojink.browser.extensions.MAX_STORE_URL
import dev.rojink.browser.extensions.SavedExtensionStore
import dev.rojink.browser.extensions.StoreException
import dev.rojink.browser.extensions.StoreProblem
import dev.rojink.browser.extensions.presence
import dev.rojink.browser.extensions.sections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable internal fun ExtensionStoresScreen(
    stores: ExtensionStores, installer: ExtensionInstaller, onBack: () -> Unit, onReadWebsite: (String) -> Unit,
) {
    val stream = remember(stores) { stores.saved.map { Result.success(it) }.catch { emit(Result.failure(it)) } }
    val saved by stream.collectAsState(initial = null)
    var addAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var openStore by remember { mutableStateOf<SavedExtensionStore?>(null) }
    var initialCatalog by remember { mutableStateOf<ExtensionCatalog?>(null) }
    var showInstalled by rememberSaveable { mutableStateOf(false) }
    var scanRequest by remember { mutableIntStateOf(0) }
    val scan = rememberInstalledScan(scanRequest)
    val current = openStore
    fun closeCatalog() { openStore = null; initialCatalog = null }
    fun closeInstalled() { showInstalled = false }
    BackHandler(enabled = current != null || showInstalled) {
        if (showInstalled) closeInstalled() else closeCatalog()
    }
    if (showInstalled) {
        ScreenHeader(R.string.store_device_installed, ::closeInstalled)
        InstalledExtensionsPage(scan) { scanRequest++ }
    } else if (current != null) {
        ScreenHeader(R.string.extensions, ::closeCatalog)
        key(current.indexUrl) {
            ExtensionCatalogPage(stores, installer, current, initialCatalog, scan?.getOrNull().orEmpty(),
                onScan = { scanRequest++ }, onRemove = ::closeCatalog, onReadWebsite = onReadWebsite)
        }
    } else {
        ScreenHeader(R.string.extension_stores, onBack)
        val records = saved?.getOrNull().orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { InstalledExtensionsShortcut(scan) { showInstalled = true } }
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(R.string.store_catalog_intro), style = MaterialTheme.typography.titleMedium)
                        Text(tr(R.string.store_scope), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when {
                saved == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                saved?.isFailure == true -> item { StoreStatus(R.string.store_storage_failed, error = true) }
                else -> {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (records.none { it.indexUrl == KEIYOUSHI_INDEX }) {
                                Button({ addAddress = KEIYOUSHI_INDEX }, Modifier.weight(1f)) { Text(tr(R.string.store_add_kei)) }
                            }
                            FilledTonalButton({ addAddress = "" }, Modifier.weight(1f)) { Text(tr(R.string.store_add)) }
                        }
                    }
                    if (records.isEmpty()) item { StoreStatus(R.string.store_empty) }
                    items(records, key = { it.indexUrl }) { store ->
                        OutlinedCard(onClick = { openStore = store }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(store.name, style = MaterialTheme.typography.titleMedium)
                                StoreAddress(store.indexUrl, lines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
    addAddress?.let { address ->
        AddStoreDialog(stores, address, onDismiss = { addAddress = null }) { catalog ->
            addAddress = null
            initialCatalog = catalog
            openStore = catalog.store
        }
    }
}

@Composable private fun AddStoreDialog(
    stores: ExtensionStores, address: String, onDismiss: () -> Unit, onAdd: (ExtensionCatalog) -> Unit,
) {
    var input by rememberSaveable(address) { mutableStateOf(address) }
    var preview by remember { mutableStateOf<ExtensionCatalog?>(null) }
    var error by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val checked = preview
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(tr(if (checked == null) R.string.store_add else R.string.store_review)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (checked == null) {
                    Text(tr(R.string.store_input_hint))
                    OutlinedTextField(input, { if (it.length <= MAX_STORE_URL * 3) { input = it; error = 0 } },
                        modifier = Modifier.fillMaxWidth(), label = { Text(tr(R.string.store_index_address)) },
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 3, enabled = !busy)
                } else {
                    Text(checked.store.name, style = MaterialTheme.typography.titleMedium)
                    StoreAddress(checked.store.indexUrl, lines = 5)
                    Text(tr(R.string.store_entry_count, checked.entries.size), style = MaterialTheme.typography.bodySmall)
                    Text(tr(if (checked.store.indexUrl == KEIYOUSHI_INDEX) R.string.store_known_signer else R.string.store_trust_detail))
                    Text(tr(R.string.store_fingerprint), style = MaterialTheme.typography.labelMedium)
                    StoreAddress(checked.store.signer)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != 0) StoreStatus(error, error = true)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && input.isNotBlank(), onClick = {
                busy = true
                error = 0
                scope.launch {
                    try {
                        if (checked == null) preview = stores.preview(input)
                        else {
                            stores.add(checked.store)
                            onAdd(checked)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.storeMessage(if (checked == null) R.string.store_load_failed else R.string.store_storage_failed) }
                    finally { busy = false }
                }
            }) { Text(tr(if (checked == null) R.string.store_check else R.string.store_confirm_add)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } })
}

@Composable private fun ExtensionCatalogPage(
    stores: ExtensionStores, installer: ExtensionInstaller, store: SavedExtensionStore, initial: ExtensionCatalog?,
    installed: Map<String, InstalledExtension>, onScan: () -> Unit, onRemove: () -> Unit, onReadWebsite: (String) -> Unit,
) {
    var catalog by remember { mutableStateOf(initial) }
    var request by remember { mutableIntStateOf(0) }
    var error by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var warnings by rememberSaveable { mutableStateOf(false) }
    var entry by remember { mutableStateOf<CatalogExtension?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(request) {
        if (request != 0 || initial == null) {
            catalog = null
            error = 0
            try { catalog = stores.refresh(store) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.storeMessage(R.string.store_load_failed) }
        }
    }
    val current = catalog
    val results = remember(current, query, warnings, installed) {
        current?.entries.orEmpty().sections(query, warnings, installed)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(current?.store?.name ?: store.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        StoreAddress(store.indexUrl, lines = 2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { request++; onScan() }, enabled = current != null || error != 0) { Text(tr(R.string.store_refresh)) }
            TextButton(onClick = { removeError = false; removing = true }) { Text(tr(R.string.store_remove)) }
        }
        if (current == null) {
            if (error == 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else StoreStatus(error, error = true)
        } else {
            OutlinedTextField(query, { if (it.length <= 200) query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr(R.string.store_search)) })
            SettingSwitch(R.string.store_include_warnings, warnings) { warnings = it }
            Text(tr(R.string.store_results, results.size, current.entries.size), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            if (results.size == 0) StoreStatus(R.string.store_no_matches)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (results.installed.isNotEmpty()) {
                    item { StoreStatus(R.string.store_device_installed) }
                    items(results.installed, key = { it.packageName }) { item ->
                        CatalogExtensionRow(item, current.store, installed[item.packageName]) { entry = item }
                    }
                    if (results.available.isNotEmpty()) item { StoreStatus(R.string.store_available) }
                }
                items(results.available, key = { it.packageName }) { item ->
                    CatalogExtensionRow(item, current.store, installed = null) { entry = item }
                }
            }
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text(tr(R.string.store_remove)) }, text = {
        Column {
            Text(tr(R.string.store_remove_detail, store.name))
            if (removeError) StoreStatus(R.string.store_storage_failed, error = true)
        }
    }, confirmButton = {
        TextButton(onClick = {
            scope.launch {
                try { stores.remove(store); onRemove() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { removeError = true }
            }
        }) { Text(tr(R.string.remove)) }
    }, dismissButton = { TextButton({ removing = false }) { Text(tr(R.string.cancel)) } })
    if (current != null) entry?.let { item ->
        ExtensionDetailsDialog(installer, current.store, item, installed[item.packageName], { entry = null }, onReadWebsite)
    }
}

@Composable private fun CatalogExtensionRow(
    entry: CatalogExtension, store: SavedExtensionStore, installed: InstalledExtension?, onClick: () -> Unit,
) {
    OutlinedCard(onClick, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            if (installed != null) {
                Text(tr(entry.presence(store.signer, installed).label()), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(listOfNotNull(entry.languages.take(6).joinToString(", ").ifBlank { null }, installed?.version ?: entry.version,
                tr(entry.warning.label())).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ExtensionDetailsDialog(
    installer: ExtensionInstaller, store: SavedExtensionStore, entry: CatalogExtension, installed: InstalledExtension?,
    onDismiss: () -> Unit, onReadWebsite: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    var permissionPrompt by remember { mutableStateOf(false) }
    val sites = entry.sources.filter { it.website != null }.distinctBy { it.website }
    val presence = entry.presence(store.signer, installed)
    val canDownload = entry.apk != null && presence.canDownload
    if (permissionPrompt && canDownload) {
        AlertDialog(onDismissRequest = { permissionPrompt = false }, title = { Text(tr(R.string.store_install_permission)) },
            text = { Text(tr(R.string.store_install_permission_detail)) }, confirmButton = {
                TextButton(onClick = {
                    permissionPrompt = false
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))
                    try { context.startActivity(intent) } catch (_: Exception) { error = R.string.store_install_failed }
                }) { Text(tr(R.string.settings)) }
            }, dismissButton = { TextButton({ permissionPrompt = false }) { Text(tr(R.string.cancel)) } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(entry.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StoreAddress(entry.packageName)
            Text(tr(R.string.store_version_warning, entry.version, tr(entry.warning.label())))
            if (installed != null) Text(tr(R.string.store_local_version, installed.version))
            when (presence) {
                ExtensionPresence.INSTALLED -> Text(tr(R.string.store_already_installed))
                ExtensionPresence.CONFLICT -> StoreStatus(R.string.store_installed_conflict, error = true)
                else -> if (entry.apk != null) {
                    Text(tr(R.string.store_apk_detail))
                    StoreAddress(entry.apk.host)
                } else Text(tr(R.string.store_no_apk))
            }
            if (sites.isNotEmpty()) {
                HorizontalDivider()
                Text(tr(R.string.store_website_detail), style = MaterialTheme.typography.bodySmall)
                sites.forEach { source ->
                    val url = checkNotNull(source.website)
                    TextButton(onClick = { onDismiss(); onReadWebsite(url.toString()) }, enabled = !installing) {
                        Text(tr(R.string.store_open_website, url.host), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (installing) {
                Text(tr(R.string.store_verifying))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (error != 0) StoreStatus(error, error = true)
        }
    }, confirmButton = {
        if (canDownload) TextButton(enabled = !installing, onClick = {
            error = 0
            if (!context.packageManager.canRequestPackageInstalls()) permissionPrompt = true
            else {
                installing = true
                scope.launch {
                    try { installer.handOff(context, store, entry); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.storeMessage(R.string.store_install_failed) }
                    finally { installing = false }
                }
            }
        }) { Text(tr(if (presence == ExtensionPresence.UPDATE) R.string.store_verify_update else R.string.store_verify_install)) }
    }, dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } })
}

@Composable private fun StoreAddress(address: String, lines: Int = 4) {
    Text(address, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable private fun StoreStatus(id: Int, error: Boolean = false) {
    Text(tr(id), style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 5.dp))
}

private fun CatalogWarning.label() = when (this) {
    CatalogWarning.SAFE -> R.string.store_safe
    CatalogWarning.MIXED -> R.string.store_mixed
    CatalogWarning.NSFW -> R.string.store_adult
    CatalogWarning.UNSPECIFIED -> R.string.store_unrated
}

private fun ExtensionPresence.label() = when (this) {
    ExtensionPresence.INSTALLED -> R.string.store_installed_label
    ExtensionPresence.UPDATE -> R.string.store_update_available
    ExtensionPresence.CONFLICT -> R.string.store_conflict_label
    ExtensionPresence.AVAILABLE -> R.string.store_available
}

private fun Exception.storeMessage(fallback: Int) = when ((this as? StoreException)?.problem) {
    StoreProblem.ADDRESS -> R.string.store_bad_address
    StoreProblem.FORMAT -> R.string.store_bad_format
    StoreProblem.SIGNER -> R.string.store_wrong_signer
    StoreProblem.DUPLICATE -> R.string.store_duplicate
    StoreProblem.LIMIT -> R.string.store_limit
    StoreProblem.APK -> R.string.store_apk_rejected
    StoreProblem.ALREADY_INSTALLED -> R.string.store_already_installed
    StoreProblem.INSTALLED_CONFLICT -> R.string.store_installed_conflict
    null -> fallback
}
