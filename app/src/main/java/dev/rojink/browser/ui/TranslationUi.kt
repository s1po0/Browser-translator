package dev.rojink.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.translation.PageStatus
import dev.rojink.browser.translation.ReaderOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TranslateSheet(settings: BrowserSettings, gallery: Boolean = false, status: PageStatus? = null, initial: ReaderOptions? = status?.options,
    dismiss: () -> Unit, start: (ReaderOptions) -> Unit, stop: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val languages = remember(context) { Languages(context) }
    var target by remember { mutableStateOf(initial?.target ?: settings.target) }
    var text by remember { mutableStateOf(!gallery && (initial?.text ?: settings.translateText)) }
    var images by remember { mutableStateOf(gallery || (initial?.images ?: settings.translateImages)) }
    var script by remember { mutableStateOf(initial?.script ?: settings.ocrHint) }
    var detail by remember { mutableStateOf(initial?.enhancedOcr ?: settings.enhancedOcr) }
    val vision = settings.useVision
    val scheme = MaterialTheme.colorScheme
    var choose by remember { mutableStateOf(false) }
    var chooseScript by remember { mutableStateOf(false) }
    val scripts = ocrChoices()
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = scheme.surface, shape = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().testTag("reader-translation-sheet").verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(13.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                    Box(Modifier.size(43.dp), contentAlignment = Alignment.Center) { ReaderGlyph(R.drawable.ic_reader_translate) }
                }
                Text(tr(if (gallery) R.string.translate_photo_title else R.string.translate_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            }
            Surface(onClick = { choose = true }, modifier = Modifier.fillMaxWidth().testTag("reader-target-language"),
                shape = RoundedCornerShape(16.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.target_language), style = MaterialTheme.typography.labelMedium)
                        Text(languageLabel(target), style = MaterialTheme.typography.titleMedium)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            if (!gallery) {
                Column(Modifier.fillMaxWidth()) {
                    SettingSwitch(R.string.text_option, text, supporting = tr(R.string.text_option_detail), modifier = Modifier.testTag("reader-translate-text")) { text = it }
                    HorizontalDivider(color = scheme.outlineVariant)
                    SettingSwitch(R.string.images_option, images, supporting = tr(R.string.images_option_detail), modifier = Modifier.testTag("reader-translate-images")) { images = it }
                }
            }
            if (images && !vision) {
                SettingRow(R.string.ocr_script, scripts.toMap()[script]) { chooseScript = true }
                SettingSwitch(R.string.enhanced_ocr, detail) { detail = it }
            }
            Surface(color = scheme.surfaceContainer, contentColor = scheme.onSurface, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReaderGlyph(R.drawable.ic_reader_privacy, Modifier.size(19.dp), tint = scheme.primary)
                    Text(tr(when { images && vision -> R.string.vision_consent; images -> R.string.consent; else -> R.string.consent_text_only }),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = { start(ReaderOptions(target, text, images, script, vision, detail)) }, enabled = text || images,
                shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 51.dp).testTag("reader-start-translation")) {
                Text(tr(R.string.start))
            }
            if (stop != null && status != null) TextButton(onClick = stop, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.translation_off)) }
        }
    }
    if (choose) ChooseLanguage(languages, target, { choose = false }) { target = it; choose = false }
    if (chooseScript) ChoiceDialog(Choice(R.string.ocr_script, scripts, script), { chooseScript = false }) { script = it; chooseScript = false }
}

@Composable internal fun TranslationBar(status: PageStatus, onToggle: () -> Unit, stop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val name = languageLabel(status.options.target)
    val color = when {
        status.original -> scheme.surfaceContainer
        status.error != 0 -> scheme.errorContainer
        else -> scheme.primaryContainer
    }
    val ink = when {
        status.original -> scheme.onSurface
        status.error != 0 -> scheme.onErrorContainer
        else -> scheme.onPrimaryContainer
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
        shape = RoundedCornerShape(15.dp), color = color, contentColor = ink) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 9.dp, end = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                ReaderGlyph(R.drawable.ic_reader_translate, Modifier.size(21.dp))
                Column(Modifier.weight(1f).padding(horizontal = 7.dp, vertical = 6.dp)) {
                    Text(if (status.original) tr(R.string.original_label) else tr(R.string.applied_language, name),
                        style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val detail = when {
                        status.original -> tr(R.string.translation_paused)
                        status.error != 0 -> tr(status.error)
                        status.busyText -> tr(R.string.translating)
                        status.discovered > status.completed -> tr(R.string.image_progress, status.completed, status.discovered)
                        status.imageErrors > 0 -> tr(R.string.image_error)
                        status.keptImageText -> tr(R.string.image_text_kept)
                        else -> tr(R.string.translation_ready)
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = if (status.error != 0) 3 else 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onToggle, colors = ButtonDefaults.textButtonColors(contentColor = ink),
                    modifier = Modifier.widthIn(max = 118.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp)) {
                    Text(tr(if (status.original) R.string.show_translation else R.string.show_original),
                        maxLines = 2, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(stop) { Icon(Icons.Default.Close, tr(R.string.translation_off), Modifier.size(19.dp)) }
            }
            if (!status.original) {
                if (status.discovered > status.completed) LinearProgressIndicator(progress = { status.completed / status.discovered.toFloat() }, modifier = Modifier.fillMaxWidth())
                else if (status.busyText) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
