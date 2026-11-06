package io.openwaters.slackwater.android.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.FIRST_RUN_ANCHOR
import io.openwaters.slackwater.android.core.station.SearchResults
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A non-empty query waits this long after the last keystroke; an empty one ranks at once. */
private const val SEARCH_DEBOUNCE_MILLIS = 120L

/** Ranked results with the query they answer, so a superseded answer is never shown as this query's. */
private data class Answer(val query: String, val results: SearchResults)

/** The full-screen search (list §7). Each opening starts empty with the field focused. */
@Composable
fun SearchOverlay(model: StationListModel, now: Instant, onOpen: (StationId) -> Unit, onClose: () -> Unit) {
    val state by model.state.collectAsState()
    val preferences by model.readingPreferences.collectAsState()
    val list by model.preferences.collectAsState()
    val catalog by model.catalog.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<Answer?>(null) }
    val anchor = state?.sections?.anchor?.coordinate ?: FIRST_RUN_ANCHOR
    val filter = list?.seriesFilter
    val focus = remember { FocusRequester() }

    // A new keystroke, filter, anchor cell or the catalog arriving restarts this effect, so only the latest
    // request's results land. The anchor is keyed by its 0.001° cell so GPS jitter does not re-rank the results.
    val cell = Math.round(anchor.latitude * 1000) to Math.round(anchor.longitude * 1000)
    LaunchedEffect(query, filter, cell, catalog) {
        val loaded = catalog ?: return@LaunchedEffect
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MILLIS)
        answer = Answer(query, model.search(loaded, query, anchor, filter))
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val current = answer
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 360.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current != null && current.query == query) {
                    if (current.results.truncated) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.search_truncated), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (current.results.stations.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.search_no_matches), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                // Results show no distance and have no swipe actions.
                items(current?.results?.stations.orEmpty(), key = { it.id.value }) { result ->
                    StationCard(result.identity, null, now, preferences, { model.reading(result.id, it) }, { onOpen(result.id) })
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SeriesChips(filter) { series -> model.launchEdit { model.listPreferences.setSeriesFilter(series) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                        trailingIcon = if (query.isEmpty()) null else {
                            {
                                val clear = stringResource(R.string.search_clear)
                                TextButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = clear }) { Text("✕") }
                            }
                        },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.search_close))
                    }
                }
            }
        }
    }
}
