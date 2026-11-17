package dev.rojink.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.data.Bookmark
import dev.rojink.browser.web.BrowserTab
import dev.rojink.browser.web.webUrl

@Composable internal fun ReaderGlyph(id: Int, modifier: Modifier = Modifier, description: String? = null, tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), description, modifier, tint)
}

@Composable internal fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Image(painterResource(R.drawable.ic_sipan), null, Modifier.size(35.dp))
        Column {
            Text(tr(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(tr(R.string.home_kicker), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun ReaderHome(
    tab: BrowserTab,
    bookmarks: List<Bookmark>,
    target: String,
    onPaste: () -> Unit,
    onPhoto: (() -> Unit)?,
    onDemo: () -> Unit,
    onSaved: () -> Unit,
    onBookmark: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab.incognito) {
                item {
                    Surface(shape = RoundedCornerShape(27.dp), color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReaderGlyph(R.drawable.ic_reader_privacy, Modifier.size(30.dp))
                            Text(tr(R.string.private_header), style = MaterialTheme.typography.headlineMedium)
                            Text(tr(R.string.private_copy), style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = onPaste, modifier = Modifier.testTag("reader-paste")) {
                                ReaderGlyph(R.drawable.ic_reader_clipboard, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text(tr(R.string.paste_open))
                            }
                        }
                    }
                }
            } else {
                item { ReadingHero(target, onPaste) }
                item { SearchStarter(onSearch) }
                item { Text(tr(R.string.home_tools), Modifier.padding(start = 2.dp, top = 1.dp), style = MaterialTheme.typography.titleMedium) }
                item {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (onPhoto != null) ReadingTile(Modifier.weight(1f).fillMaxHeight(), R.drawable.ic_reader_photo,
                            R.string.photo, R.string.photo_short, scheme.tertiaryContainer, scheme.onTertiaryContainer, onPhoto)
                        ReadingTile(Modifier.weight(1f).fillMaxHeight(), R.drawable.ic_reader_sample,
                            R.string.demo_title, R.string.demo_short, scheme.primaryContainer, scheme.onPrimaryContainer, onDemo)
                    }
                }
                item { Text(tr(R.string.quick_access), Modifier.padding(start = 2.dp, top = 3.dp), style = MaterialTheme.typography.titleMedium) }
                if (bookmarks.isEmpty()) {
                    item { BookmarkTile(tr(R.string.bookmarks), tr(R.string.no_bookmarks), onSaved) }
                } else {
                    items(bookmarks.take(6), key = { it.url }) { page ->
                        BookmarkTile(page.title, webUrl(page.url)?.host ?: page.url) { onBookmark(page.url) }
                    }
                }
            }
        }
    }
}

@Composable private fun ReadingHero(target: String, onPaste: () -> Unit) {
    val shape = RoundedCornerShape(29.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFF122F29), Color(0xFF1F5341))))) {
        // The illustration is decorative; give enlarged text the full card width.
        val artSize = (maxWidth * .30f).coerceAtMost(136.dp)
        val showArt = maxWidth >= 284.dp && LocalDensity.current.fontScale <= 1.23f
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(tr(R.string.manga_badge), color = Color(0xFFCAE2BF), style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.hero_title), Modifier.weight(1f), color = Color(0xFFF3FFDF), style = MaterialTheme.typography.displaySmall)
                if (showArt) ReadingArt(Modifier.size(artSize))
            }
            Text(tr(R.string.hero_body), color = Color(0xFFDCE9D7), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 285.dp))
            Button(onClick = onPaste, modifier = Modifier.testTag("reader-paste").padding(top = 4.dp).heightIn(min = 49.dp), shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)) {
                ReaderGlyph(R.drawable.ic_reader_clipboard, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr(R.string.paste_open))
            }
            Surface(color = Color(0xFF39644F), contentColor = Color(0xFFE5F4DA), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderGlyph(R.drawable.ic_reader_translate, Modifier.size(14.dp))
                    Text(tr(R.string.home_target, languageLabel(target)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun ReadingTile(modifier: Modifier, icon: Int, title: Int, subtitle: Int, color: Color, ink: Color, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(11.dp), color = color, contentColor = ink) {
                    Box(Modifier.size(35.dp), contentAlignment = Alignment.Center) { ReaderGlyph(icon, Modifier.size(20.dp)) }
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), scheme.onSurfaceVariant)
            }
            Text(tr(title), style = MaterialTheme.typography.titleMedium)
            Text(tr(subtitle), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable private fun BookmarkTile(title: String, detail: String, open: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = open, shape = RoundedCornerShape(18.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(11.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), scheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ReadingArt(modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 150f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val orbit = Color(0xFF80A78A).copy(alpha = .47f)
        val fold = Color(0xFFBCD8A7)
        drawCircle(orbit, 61 * u, p(80f, 73f), style = Stroke(.9f * u))
        drawCircle(orbit.copy(alpha = .24f), 69 * u, p(80f, 73f), style = Stroke(.9f * u))
        drawLine(orbit, p(80f, 5f), p(80f, 140f), .6f * u)
        drawLine(orbit, p(9f, 73f), p(148f, 73f), .6f * u)
        drawCircle(Apricot, 17 * u, p(107f, 43f))
        drawCircle(Lime.copy(alpha = .72f), 2.3f * u, p(124f, 19f))
        drawCircle(Lime.copy(alpha = .53f), 1.4f * u, p(34f, 31f))
        val pageLeft = Path().apply {
            moveTo(30 * u, 60 * u); quadraticTo(53 * u, 54 * u, 78 * u, 74 * u); lineTo(78 * u, 116 * u)
            quadraticTo(57 * u, 101 * u, 30 * u, 106 * u); close()
        }
        val pageRight = Path().apply {
            moveTo(82 * u, 74 * u); quadraticTo(105 * u, 54 * u, 132 * u, 59 * u); lineTo(132 * u, 105 * u)
            quadraticTo(105 * u, 100 * u, 82 * u, 116 * u); close()
        }
        drawPath(pageLeft, Lime.copy(alpha = .87f)); drawPath(pageRight, fold)
        for (y in listOf(73f, 80f, 87f)) {
            drawLine(Moss, p(39f, y), p(69f, y + 7), 1.2f * u)
            drawLine(Moss, p(91f, y + 7), p(119f, y), 1.2f * u)
        }
        drawRoundRect(Color(0xFF47856C), p(21f, 41f), Size(30 * u, 16 * u), CornerRadius(5 * u))
        for (x in listOf(29f, 36f, 43f)) drawCircle(Lime, 1.3f * u, p(x, 49f))
    }
}
