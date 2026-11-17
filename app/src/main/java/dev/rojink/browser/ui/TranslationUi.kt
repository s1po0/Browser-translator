package dev.rojink.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.translation.PageStatus
import dev.rojink.browser.translation.ReaderOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TranslateSheet(settings: BrowserSettings, gallery: Boolean = false, status: PageStatus? = null,
    dismiss: () -> Unit, start: (ReaderOptions) -> Unit, stop: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val languages = remember(context) { Languages(context) }
    var target by remember { mutableStateOf(status?.options?.target ?: settings.target) }
    var text by remember { mutableStateOf(!gallery && (status?.options?.text ?: settings.translateText)) }
    var images by remember { mutableStateOf(gallery || (status?.options?.images ?: settings.translateImages)) }
    var script by remember { mutableStateOf(status?.options?.script ?: settings.ocrHint) }
    val vision = settings.useVision
    var choose by remember { mutableStateOf(false) }
    var chooseScript by remember { mutableStateOf(false) }
    val scripts = ocrChoices()
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 25.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr(if (gallery) R.string.translate_photo_title else R.string.translate_title), Modifier.padding(start = 13.dp), style = MaterialTheme.typography.headlineMedium)
            SettingRow(R.string.target_language, languages[target].nativeName) { choose = true }
            if (!gallery) {
                SettingSwitch(R.string.text_option, text) { text = it }
                SettingSwitch(R.string.images_option, images) { images = it }
            }
            if (images && !vision) SettingRow(R.string.ocr_script, scripts.toMap()[script]) { chooseScript = true }
            Text(tr(if (images && vision) R.string.vision_consent else R.string.consent), Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { start(ReaderOptions(target, text, images, script, vision)) }, enabled = text || images, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text(tr(R.string.start))
            }
            if (stop != null && status != null) TextButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.translation_off)) }
        }
    }
    if (choose) ChooseLanguage(languages, target, { choose = false }) { target = it; choose = false }
    if (chooseScript) ChoiceDialog(Choice(R.string.ocr_script, scripts, script), { chooseScript = false }) { script = it; chooseScript = false }
}

@Composable internal fun TranslationBar(status: PageStatus, onToggle: () -> Unit, stop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
                    val label = when {
                        status.error != 0 -> tr(status.error)
                        status.discovered > 0 -> tr(R.string.image_progress, status.completed, status.discovered)
                        status.busyText -> tr(R.string.translating)
                        else -> tr(R.string.translation_active)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (status.imageErrors > 0 && status.error == 0) Text(tr(R.string.image_error), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)) {
                    Text(tr(if (status.original) R.string.show_translation else R.string.show_original), maxLines = 2, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(stop) { Icon(Icons.Default.Close, tr(R.string.translation_off)) }
            }
            if (!status.original && status.discovered > status.completed) LinearProgressIndicator(progress = { status.completed / status.discovered.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}
