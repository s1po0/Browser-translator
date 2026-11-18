package dev.rojink.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rojink.browser.R
import dev.rojink.browser.search.SearchFreshness
import dev.rojink.browser.search.WebRefinements

@Composable private fun freshnessName(freshness: SearchFreshness) = tr(when (freshness) {
    SearchFreshness.ANY -> R.string.search_date_any
    SearchFreshness.DAY -> R.string.search_date_day
    SearchFreshness.WEEK -> R.string.search_date_week
    SearchFreshness.MONTH -> R.string.search_date_month
    SearchFreshness.YEAR -> R.string.search_date_year
})

@Composable internal fun refinementsLabel(tools: WebRefinements): String = listOfNotNull(
    if (tools.freshness != SearchFreshness.ANY) freshnessName(tools.freshness) else null,
    if (tools.exactPhrase) tr(R.string.search_exact_phrase) else null,
    if (tools.pdfs) tr(R.string.search_pdfs) else null,
).joinToString(" · ")

@Composable internal fun WebRefinementsButton(refinements: WebRefinements, onClick: () -> Unit) {
    val summary = refinementsLabel(refinements)
    AssistChip(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("sipan-search-tools"), label = {
        Text(if (summary.isEmpty()) tr(R.string.search_tools) else tr(R.string.search_tools_summary, summary),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WebRefinementsDialog(initial: WebRefinements, onClose: () -> Unit, onApply: (WebRefinements) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onClose, title = { Text(tr(R.string.search_tools)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.search_date), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (date in SearchFreshness.entries) {
                    FilterChip(selected = draft.freshness == date, onClick = { draft = draft.copy(freshness = date) },
                        label = { Text(freshnessName(date)) }, modifier = Modifier.testTag("sipan-date-${date.name.lowercase()}"))
                }
            }
            HorizontalDivider()
            SettingSwitch(R.string.search_exact_phrase, draft.exactPhrase, supporting = tr(R.string.search_exact_phrase_detail),
                modifier = Modifier.testTag("sipan-exact-phrase")) { draft = draft.copy(exactPhrase = it) }
            SettingSwitch(R.string.search_pdfs, draft.pdfs, modifier = Modifier.testTag("sipan-pdf")) { draft = draft.copy(pdfs = it) }
            Text(tr(R.string.search_tools_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        Button({ onApply(draft) }, Modifier.testTag("sipan-apply-search-tools")) { Text(tr(R.string.search_filters_apply)) }
    }, dismissButton = { TextButton(onClose) { Text(tr(R.string.cancel)) } })
}
