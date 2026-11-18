package dev.rojink.browser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.RojInkApp
import dev.rojink.browser.data.ApiKey
import dev.rojink.browser.data.Bookmark
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.search.AddressSearchMode
import dev.rojink.browser.translation.ENGLISH_COMIC_SCRIPT
import dev.rojink.browser.web.Browser
import kotlin.math.roundToInt

@Composable internal fun SettingsScreen(browser: Browser, app: RojInkApp, settings: BrowserSettings, bookmarks: List<Bookmark>,
    clearTranslations: suspend () -> Unit, onPrivacy: () -> Unit, onAbout: () -> Unit,
) {
    val ctx = LocalContext.current
    val languages = remember(ctx) { Languages(ctx) }
    val enabledKeys by app.keys.enabled.collectAsState()
    var languageDialog by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<Choice?>(null) }
    var keyDialog by remember { mutableStateOf<ApiKey?>(null) }
    var confirm by remember { mutableStateOf<Pair<Int, () -> Unit>?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) browser.safely { browser.library.importBookmarks(uri); browser.notice = R.string.import_done }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) browser.safely { browser.library.exportBookmarks(uri, bookmarks); browser.notice = R.string.export_done }
    }
    fun update(block: (BrowserSettings) -> BrowserSettings) { browser.safely { app.settings.update(block) } }
    val themes = listOf("system" to tr(R.string.system), "light" to tr(R.string.light), "dark" to tr(R.string.dark))
    val locales = listOf("system" to tr(R.string.system), "en" to "English", "ckb" to "کوردیی سۆرانی")
    val scripts = ocrChoices()
    fun asName(list: List<Pair<String, String>>, current: String) = list.toMap()[current]
    LazyColumn(contentPadding = PaddingValues(vertical = 10.dp, horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { Section(R.string.appearance) }
        item { SettingRow(R.string.language, asName(locales, settings.uiLanguage)) { chooser = Choice(R.string.language, locales, settings.uiLanguage) { value -> update { it.copy(uiLanguage = value) } } } }
        item { SettingRow(R.string.theme, asName(themes, settings.theme)) { chooser = Choice(R.string.theme, themes, settings.theme) { value -> update { it.copy(theme = value) } } } }
        item {
            var zoom by remember(settings.textSize) { mutableFloatStateOf(settings.textSize.toFloat()) }
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(tr(R.string.zoom, zoom.roundToInt()), style = MaterialTheme.typography.titleMedium)
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 75f..175f, steps = 19, onValueChangeFinished = { update { it.copy(textSize = zoom.roundToInt()) } })
            }
        }
        item { Section(R.string.browsing) }
        item {
            SettingSwitch(R.string.search_setting_web, settings.addressSearch == AddressSearchMode.WEB,
                supporting = tr(R.string.search_setting_web_detail)) { enabled ->
                update { it.copy(addressSearch = if (enabled) AddressSearchMode.WEB else AddressSearchMode.DEVICE) }
            }
        }
        item { SettingRow(R.string.clear_search_index, tr(R.string.clear_search_index_body)) {
            confirm = R.string.clear_search_index to { browser.safely { browser.dao.clearSearchPages(); browser.notice = R.string.search_index_cleared } }
        } }
        item { SettingSwitch(R.string.block_trackers, settings.trackerBlocking) { value -> update { it.copy(trackerBlocking = value) } } }
        item { SettingRow(R.string.import_bookmarks) { import.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain", "application/octet-stream")) } }
        item { SettingRow(R.string.export_bookmarks) { export.launch("sipan-bookmarks.html") } }
        item { Section(R.string.translation_section) }
        item { SettingRow(R.string.target_language, languages[settings.target].nativeName) { languageDialog = true } }
        item { SettingSwitch(R.string.text_option, settings.translateText) { value -> update { it.copy(translateText = value) } } }
        item { SettingSwitch(R.string.images_option, settings.translateImages) { value -> update { it.copy(translateImages = value) } } }
        item { SettingSwitch(R.string.enhanced_ocr, settings.enhancedOcr, supporting = tr(R.string.enhanced_ocr_detail)) { value -> update { it.copy(enhancedOcr = value) } } }
        item { SettingRow(R.string.ocr_script, asName(scripts, settings.ocrHint)) { chooser = Choice(R.string.ocr_script, scripts, settings.ocrHint) { value -> update { it.copy(ocrHint = value) } } } }
        item { SettingRow(R.string.cloud_key, tr(if (ApiKey.TRANSLATE in enabledKeys) R.string.cloud_provider else R.string.free_provider)) { keyDialog = ApiKey.TRANSLATE } }
        item { SettingRow(R.string.vision_key, if (ApiKey.VISION in enabledKeys) tr(R.string.key_saved) else null) { keyDialog = ApiKey.VISION } }
        item { SettingSwitch(R.string.vision_option, settings.useVision && ApiKey.VISION in enabledKeys) { value ->
            if (value && ApiKey.VISION !in enabledKeys) browser.notice = R.string.vision_need_key else update { it.copy(useVision = value) }
        } }
        item { Section(R.string.privacy) }
        item { SettingRow(R.string.clear_cache) { confirm = R.string.clear_cache to { browser.safely { clearTranslations(); browser.clearWebCache(); browser.notice = R.string.cache_cleared } } } }
        item { SettingRow(R.string.clear_history) { confirm = R.string.clear_history to { browser.safely { browser.dao.clearHistory(); browser.notice = R.string.history_cleared } } } }
        item { SettingRow(R.string.clear_cookies) { confirm = R.string.clear_cookies to browser::clearCookies } }
        item { SettingRow(R.string.privacy, onClick = onPrivacy) }
        item { SettingRow(R.string.about, onClick = onAbout) }
    }
    if (languageDialog) ChooseLanguage(languages, settings.target, { languageDialog = false }) { value -> update { it.copy(target = value) }; languageDialog = false }
    chooser?.let { choice -> ChoiceDialog(choice, { chooser = null }) { chooser = null; choice.pick(it) } }
    keyDialog?.let { kind ->
        var value by remember(kind) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { keyDialog = null }, title = { Text(tr(if (kind == ApiKey.VISION) R.string.vision_key else R.string.cloud_key)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.key_help))
                OutlinedTextField(value, { value = it.take(180) }, singleLine = true, label = { Text(tr(R.string.key_field)) }, visualTransformation = PasswordVisualTransformation())
                if (kind in enabledKeys) TextButton(onClick = { browser.safely { app.keys.set(kind, null); if (kind == ApiKey.VISION) app.settings.update { it.copy(useVision = false) } }; keyDialog = null }) { Text(tr(R.string.remove)) }
            }
        }, confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { val key = value; browser.safely { app.keys.set(kind, key) }; keyDialog = null }) { Text(tr(R.string.save)) } },
            dismissButton = { TextButton(onClick = { keyDialog = null }) { Text(tr(R.string.cancel)) } })
    }
    confirm?.let { (title, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(tr(title)) }, text = { Text(tr(R.string.clear_confirm)) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(tr(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr(R.string.cancel)) } })
    }
}

@Composable internal fun ocrChoices() = listOf("auto" to tr(R.string.automatic), ENGLISH_COMIC_SCRIPT to tr(R.string.english_comics),
    "ja" to tr(R.string.japanese), "ko" to tr(R.string.korean), "zh" to tr(R.string.chinese),
    "latin" to tr(R.string.latin), "devanagari" to tr(R.string.devanagari))

@Composable private fun Section(id: Int) {
    Text(tr(id), Modifier.padding(start = 14.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}
@Composable internal fun SettingRow(title: Int, supporting: String? = null, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(tr(title)) }, supportingContent = if (supporting != null) { { Text(supporting) } } else null,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickable(role = Role.Button, onClick = onClick))
}
@Composable internal fun SettingSwitch(title: Int, checked: Boolean, supporting: String? = null, modifier: Modifier = Modifier, change: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(tr(title)) }, supportingContent = if (supporting != null) { { Text(supporting) } } else null,
        trailingContent = { Switch(checked, onCheckedChange = null) }, modifier = modifier.clip(RoundedCornerShape(15.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = change))
}
internal data class Choice(val title: Int, val values: List<Pair<String, String>>, val current: String, val pick: (String) -> Unit = {})
@Composable internal fun ChoiceDialog(choice: Choice, dismiss: () -> Unit, pick: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr(choice.title)) }, text = {
        Column {
            choice.values.forEach { (value, title) ->
                ListItem(headlineContent = { Text(title) }, leadingContent = { RadioButton(choice.current == value, onClick = null) }, modifier = Modifier.clickable { pick(value) })
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(tr(R.string.cancel)) } })
}
