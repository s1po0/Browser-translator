package dev.rojink.browser.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.search.WebSafeSearch
import dev.rojink.browser.search.WebSearchFilters
import dev.rojink.browser.search.countryDomain
import dev.rojink.browser.search.hasBraveRegion
import dev.rojink.browser.search.searchCountryCodes
import dev.rojink.browser.search.searchWords
import java.text.Collator
import java.util.Locale

private data class SearchCountry(val code: String, val english: String, val sorani: String) {
    val words by lazy(LazyThreadSafetyMode.NONE) { searchWords("$english $sorani $code ${countryDomain(code)}") }
    fun label(ckb: Boolean) = if (ckb) sorani else english
}

private fun readCountries(context: Context): List<SearchCountry> {
    val names = runCatching {
        context.assets.open("search-countries.tsv").bufferedReader().useLines { rows ->
            rows.filterNot { it.startsWith('#') }.mapNotNull { row ->
                val cols = row.split('\t', limit = 3)
                if (cols.size == 3) SearchCountry(cols[0], cols[1], cols[2]) else null
            }.associateBy { it.code }
        }
    }.getOrDefault(emptyMap())
    return searchCountryCodes.map { code ->
        names[code] ?: Locale("", code).getDisplayCountry(Locale.ENGLISH).let { SearchCountry(code, it, it) }
    }
}

@Composable private fun countries(): List<SearchCountry> {
    val context = LocalContext.current.applicationContext
    return remember(context) { readCountries(context) }
}

@Composable private fun countryName(code: String?): String {
    if (code == null) return tr(R.string.search_worldwide)
    val sorani = IsSorani.current
    return countries().firstOrNull { it.code == code }?.label(sorani) ?: code
}

@Composable private fun domainName(domain: String) = if (IsSorani.current) "\u2066.$domain\u2069" else ".$domain"

@Composable private fun safeSearchName(mode: WebSafeSearch) = tr(when (mode) {
    WebSafeSearch.MODERATE -> R.string.search_safety_moderate
    WebSafeSearch.STRICT -> R.string.search_safety_strict
    WebSafeSearch.OFF -> R.string.search_safety_off
})

@Composable internal fun WebFiltersButton(filters: WebSearchFilters, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val country = countryName(filters.country)
    val domain = filters.domainFilter
    val countryLabel = if (domain != null) tr(R.string.search_region_with_domain, country, domainName(domain)) else country
    AssistChip(onClick = onClick, modifier = modifier.testTag("sipan-search-filters"),
        leadingIcon = { Icon(Icons.Default.Settings, tr(R.string.search_filters), Modifier.size(17.dp)) },
        label = { Text(tr(R.string.search_filters_summary, countryLabel, safeSearchName(filters.safeSearch)),
            maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WebFiltersDialog(initial: WebSearchFilters, privateTab: Boolean, onClose: () -> Unit, onApply: (WebSearchFilters) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var choosingCountry by remember { mutableStateOf(false) }
    if (choosingCountry) {
        CountryPicker(draft.country, onClose = { choosingCountry = false }) { code ->
            draft = draft.copy(country = code, countryDomainsOnly = code != null && draft.countryDomainsOnly)
            choosingCountry = false
        }
        return
    }
    val palette = MaterialTheme.colorScheme
    val country = draft.country
    AlertDialog(onDismissRequest = onClose,
        title = { Text(tr(R.string.search_filters)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(if (privateTab) R.string.search_filters_private_detail else R.string.search_filters_detail),
                    color = palette.onSurfaceVariant)
                OutlinedButton({ choosingCountry = true }, Modifier.fillMaxWidth().testTag("sipan-country-picker")) {
                    Text(tr(R.string.search_region_value, countryName(country)), Modifier.weight(1f))
                    Spacer(Modifier.width(7.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                }
                if (country != null) {
                    val domainDetail = tr(R.string.search_only_country_domains_detail, domainName(countryDomain(country)))
                    if (hasBraveRegion(country)) {
                        if (!draft.countryDomainsOnly) Text(tr(R.string.search_region_priority_detail),
                            style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                        SettingSwitch(R.string.search_only_country_domains, draft.countryDomainsOnly, supporting = domainDetail,
                            modifier = Modifier.testTag("sipan-country-domains")) { draft = draft.copy(countryDomainsOnly = it) }
                    } else {
                        Text(tr(R.string.search_region_domain_fallback) + " " + domainDetail,
                            style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Text(tr(R.string.search_safety), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in WebSafeSearch.entries) {
                        FilterChip(selected = draft.safeSearch == mode, onClick = { draft = draft.copy(safeSearch = mode) },
                            label = { Text(safeSearchName(mode)) }, modifier = Modifier.testTag("sipan-safety-${mode.cookieValue}"))
                    }
                }
                Text(tr(when (draft.safeSearch) {
                    WebSafeSearch.MODERATE -> R.string.search_safety_moderate_detail
                    WebSafeSearch.STRICT -> R.string.search_safety_strict_detail
                    WebSafeSearch.OFF -> R.string.search_safety_off_detail
                }), style = MaterialTheme.typography.bodySmall,
                    color = if (draft.safeSearch == WebSafeSearch.OFF) palette.tertiary else palette.onSurfaceVariant)
            }
        }, confirmButton = {
            Button({ onApply(draft) }, Modifier.testTag("sipan-apply-filters")) { Text(tr(R.string.search_filters_apply)) }
        }, dismissButton = { TextButton(onClose) { Text(tr(R.string.cancel)) } })
}

@Composable private fun CountryPicker(selected: String?, onClose: () -> Unit, onPick: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val sorani = IsSorani.current
    val all = countries()
    val sorted = remember(all, sorani) {
        val sort = Collator.getInstance(Locale.forLanguageTag(if (sorani) "ckb" else "en"))
        all.sortedWith { a, b -> sort.compare(a.label(sorani), b.label(sorani)) }
    }
    val results = remember(sorted, query) {
        val tokens = searchWords(query)
        sorted.filter { country -> tokens.all { term -> country.words.any { it.startsWith(term) } } }
            .sortedByDescending { it.code.equals(query.trim(), true) }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(tr(R.string.search_region)) }, text = {
        Column(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(query, { query = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("sipan-country-query"),
                label = { Text(tr(R.string.search_country_find)) })
            LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                if (query.isBlank()) item(key = "world") {
                    CountryOption(tr(R.string.search_worldwide), null, selected == null, Modifier.testTag("sipan-country-world")) { onPick(null) }
                }
                items(results, key = { it.code }) { country ->
                    val detail = if (sorani) "${country.english} · ${country.code}" else country.code
                    CountryOption(country.label(sorani), detail, selected == country.code,
                        Modifier.testTag("sipan-country-${country.code}")) { onPick(country.code) }
                }
                if (results.isEmpty()) item { Text(tr(R.string.search_country_empty), Modifier.padding(vertical = 8.dp)) }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text(tr(R.string.back)) } })
}

@Composable private fun CountryOption(label: String, detail: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)) {
        Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
