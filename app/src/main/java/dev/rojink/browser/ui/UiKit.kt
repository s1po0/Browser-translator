package dev.rojink.browser.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rojink.browser.R
import dev.rojink.browser.data.BrowserSettings
import java.util.Locale

val Ink = Color(0xFF0F172A)
val Moss = Color(0xFF4F46E5)
val Lime = Color(0xFFE0E7FF)
val Apricot = Color(0xFF38BDF8)
private val light = lightColorScheme(
    primary = Moss, onPrimary = Color.White, primaryContainer = Lime, onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0284C7), secondaryContainer = Color(0xFFE0F2FE), onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFDB2777), tertiaryContainer = Color(0xFFFCE7F3), onTertiaryContainer = Color(0xFF831843),
    background = Color(0xFFF8FAFC), onBackground = Ink, surface = Color(0xFFFFFFFF),
    onSurface = Ink, surfaceVariant = Color(0xFFF1F5F9), onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8), outlineVariant = Color(0xFFE2E8F0),
    surfaceContainerLow = Color(0xFFF8FAFC), surfaceContainer = Color(0xFFF1F5F9), surfaceContainerHigh = Color(0xFFE2E8F0),
)
private val dark = darkColorScheme(
    primary = Color(0xFF818CF8), onPrimary = Color(0xFF1E1B4B), primaryContainer = Color(0xFF312E81), onPrimaryContainer = Lime,
    secondary = Color(0xFF38BDF8), secondaryContainer = Color(0xFF0C4A6E), onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFFF472B6), tertiaryContainer = Color(0xFF831843), onTertiaryContainer = Color(0xFFFCE7F3),
    background = Color(0xFF0B0F19), surface = Color(0xFF131A2A), surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFB8C9BA), outline = Color(0xFF8E9F90), outlineVariant = Color(0xFF43554A),
    surfaceContainerLow = Color(0xFF14201A), surfaceContainer = Color(0xFF1E2D25), surfaceContainerHigh = Color(0xFF293930),
)
private val LocaleResources = staticCompositionLocalOf<Resources> { error("Missing RojInk resources") }
val IsSorani = staticCompositionLocalOf { false }

@Composable fun RojInkTheme(settings: BrowserSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val sorani = when (settings.uiLanguage) { "ckb" -> true; "en" -> false; else -> config.locales[0].language == "ckb" }
    val resources = remember(context, config, sorani) {
        context.createConfigurationContext(Configuration(config).apply { setLocale(Locale.forLanguageTag(if (sorani) "ckb" else "en")) }).resources
    }
    val isDark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val font = if (sorani) FontFamily(Font(R.font.noto_naskh_arabic)) else FontFamily.SansSerif
    val type = remember(font) {
        Typography(
            displaySmall = TextStyle(fontFamily = font, fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold, letterSpacing = if (sorani) 0.sp else (-1.2).sp),
            headlineMedium = TextStyle(fontFamily = font, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontFamily = font, fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontFamily = font, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
            labelSmall = TextStyle(fontFamily = font, fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
        )
    }
    CompositionLocalProvider(LocaleResources provides resources, IsSorani provides sorani,
        LocalLayoutDirection provides if (sorani) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = if (isDark) dark else light, typography = type, content = content)
    }
}

@Composable fun tr(id: Int, vararg arguments: Any): String = if (arguments.isEmpty()) LocaleResources.current.getString(id) else LocaleResources.current.getString(id, *arguments)

data class Language(val code: String, val nativeName: String, val englishName: String)
class Languages(context: Context) {
    val values: List<Language> = context.assets.open("languages.tsv").bufferedReader().useLines { lines ->
        lines.filter(String::isNotBlank).mapNotNull { val p = it.split('\t'); if (p.size == 3) Language(p[0], p[1], p[2]) else null }.toList()
    }
    operator fun get(code: String): Language = values.firstOrNull { it.code == code } ?: Language(code, code, code)
}


@Composable internal fun languageLabel(code: String): String {
    val context = LocalContext.current
    val language = remember(context) { Languages(context) }[code]
    return if (IsSorani.current || code != "ckb") language.nativeName else language.englishName
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChooseLanguage(languages: Languages, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, languages) {
        val q = query.trim()
        languages.values.filter { it.nativeName.contains(q, true) || it.englishName.contains(q, true) || it.code.contains(q, true) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(R.string.target_language)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr(R.string.search_languages)) })
            if (results.isEmpty()) Text(tr(R.string.no_languages), Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(results, key = { it.code }) { item ->
                    androidx.compose.material3.Surface(onClick = { onPick(item.code) }) {
                        ListItem(headlineContent = { Text(item.nativeName) }, supportingContent = { Text("${item.englishName} · ${item.code}") },
                            leadingContent = { RadioButton(selected = selected == item.code, onClick = null) })
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } })
}
