package dev.rojink.browser.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.data.BrowserSettings
import dev.rojink.browser.translation.GallerySession

@Composable internal fun GalleryUi(session: GallerySession, settings: BrowserSettings) {
    var menu by remember { mutableStateOf(false) }
    var scale by remember(session.before) { mutableFloatStateOf(1f) }
    var shift by remember(session.before) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 7f)
        shift = if (scale == 1f) Offset.Zero else Offset((shift.x + pan.x).coerceIn(-2500f, 2500f), (shift.y + pan.y).coerceIn(-3000f, 3000f))
    }
    val write = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if (uri != null) session.save(uri) }
    val snack = remember { SnackbarHostState() }
    val feedback = session.feedback.takeIf { it != 0 }?.let { tr(it) }
    LaunchedEffect(feedback) { if (feedback != null) try { snack.showSnackbar(feedback) } finally { session.clearFeedback() } }
    BackHandler { session.close() }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { pads ->
        Column(Modifier.fillMaxSize().padding(pads)) {
            ScreenHeader(R.string.photo, session::close)
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                val frame = if (session.original) session.before else session.after ?: session.before
                if (frame != null) {
                    val display = remember(frame) { frame.asImageBitmap() }
                    Box(Modifier.fillMaxSize().clipToBounds().transformable(transform), contentAlignment = Alignment.Center) {
                        Image(display, tr(if (session.original) R.string.show_original else R.string.show_translation),
                            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = shift.x; translationY = shift.y }, contentScale = ContentScale.Fit)
                    }
                    Surface(Modifier.align(Alignment.TopStart).padding(10.dp), shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                        Text(tr(if (session.original) R.string.original_label else R.string.translated_label),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val less = tr(R.string.zoom_out)
                        val more = tr(R.string.zoom_in)
                        FilledTonalIconButton(onClick = { scale = (scale / 1.35f).coerceAtLeast(1f); if (scale == 1f) shift = Offset.Zero },
                            modifier = Modifier.semantics { contentDescription = less }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        FilledTonalIconButton(onClick = { scale = (scale * 1.35f).coerceAtMost(7f) },
                            modifier = Modifier.semantics { contentDescription = more }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                } else if (session.loading) CircularProgressIndicator()
                else EmptyState(if (session.error == 0) R.string.photo_error else session.error)
                if (session.working) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
            }
            if (session.before != null) {
                if (session.error != 0) Text(tr(session.error), Modifier.padding(start = 15.dp, end = 15.dp, top = 8.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { menu = true }, enabled = !session.working, shape = RoundedCornerShape(15.dp), modifier = Modifier.weight(1f)) {
                            ReaderGlyph(R.drawable.ic_reader_translate, Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(tr(if (session.working) R.string.translating else R.string.translate), maxLines = 2)
                        }
                        if (session.after != null) OutlinedButton(session::toggle, shape = RoundedCornerShape(15.dp), modifier = Modifier.weight(1f)) {
                            Text(tr(if (session.original) R.string.show_translation else R.string.show_original), maxLines = 2)
                        }
                    }
                    session.textCounts?.let { counts ->
                        Text(tr(R.string.image_text_counts, counts.found, counts.translated, counts.placed),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (session.after != null && !session.privateGallery) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { write.launch("sipan-translation.png") }) { Text(tr(R.string.save)) }
                        TextButton(onClick = session::share) { Text(tr(R.string.share)) }
                    }
                }
            }
        }
    }
    if (menu) TranslateSheet(settings, gallery = true, initial = session.lastOptions, dismiss = { menu = false }, start = { menu = false; session.translate(it) })
}
