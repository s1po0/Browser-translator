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

val Ink = Color(0xFF123B32)
val Moss = Color(0xFF235C48)
val Lime = Color(0xFFE5F7CE)
val Apricot = Color(0xFFFFA07A)
private val light = lightColorScheme(
    primary = Moss, onPrimary = Color.White, primaryContainer = Lime, onPrimaryContainer = Ink,
    secondary = Color(0xFF486350), secondaryContainer = Color(0xFFE1EBDB), onSecondaryContainer = Ink,
    tertiary = Color(0xFF995133), tertiaryContainer = Color(0xFFFFDBCB), onTertiaryContainer = Color(0xFF391609),
    background = Color(0xFFF6F4EC), onBackground = Color(0xFF18241E), surface = Color(0xFFFBFAF5),
    onSurface = Color(0xFF18241E), surfaceVariant = Color(0xFFE8ECE2), onSurfaceVariant = Color(0xFF49564B),
    outline = Color(0xFF738071), outlineVariant = Color(0xFFCCD6C9),
)
private val dark = darkColorScheme(
    primary = Color(0xFFAFE1B6), onPrimary = Color(0xFF133B2A), primaryContainer = Color(0xFF25533F), onPrimaryContainer = Lime,
    secondary = Color(0xFFBFD5C1), secondaryContainer = Color(0xFF354439), onSecondaryContainer = Lime,
    tertiary = Apricot, tertiaryContainer = Color(0xFF653824), onTertiaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF101A16), surface = Color(0xFF18251E), surfaceVariant = Color(0xFF283830),
    onSurfaceVariant = Color(0xFFB8C9BA), outline = Color(0xFF8E9F90), outlineVariant = Color(0xFF43554A),
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
            displaySmall = TextStyle(fontFamily = font, fontSize = 40.sp, lineHeight = 47.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp),
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
