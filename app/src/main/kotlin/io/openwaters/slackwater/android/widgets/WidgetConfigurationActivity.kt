package io.openwaters.slackwater.android.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.SlackwaterTheme
import io.openwaters.slackwater.android.enableWaterEdgeToEdge
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.BundledStationCatalog
import io.openwaters.slackwater.android.data.UserStateStore
import io.openwaters.slackwater.android.stations.PlatformFixSource
import io.openwaters.slackwater.android.stations.LocationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || provider != ComponentName(this, StationWidget::class.java)) {
            finish()
            return
        }
        enableWaterEdgeToEdge()
        setContent {
            SlackwaterTheme {
                Surface(Modifier.fillMaxSize()) {
                    Picker { choice ->
                        saveWidgetChoice(this, id, choice)
                        WidgetRefreshWorker.schedule(this)
                        WidgetRefreshWorker.refresh(this)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }
                }
            }
        }
    }

    private data class Catalog(
        val search: StationSearch, val anchor: Coordinate,
        val favorites: List<StationIdentity>, val recents: List<StationIdentity>,
    )

    private suspend fun loadCatalog(): Catalog = withContext(Dispatchers.IO) {
        val identities = BundledStationCatalog(assets).identities()
        val live = identities.associateBy { it.id }
        val places = UserStateStore.get(this@WidgetConfigurationActivity).places.first().getOrNull()
        val permission = PlatformFixSource(this@WidgetConfigurationActivity).permission()
        val fix = LocationRepository.get(this@WidgetConfigurationActivity).cachedFix()?.coordinate
        if (fix != null) cacheWidgetStations(this@WidgetConfigurationActivity, fix, permission)
        val favorites = places?.favorites.orEmpty()
        val recents = places?.recents.orEmpty()
        Catalog(StationSearch(identities), rankingAnchor(fix, recents, live).coordinate,
            favorites.mapNotNull(live::get), (recents - favorites.toSet()).mapNotNull(live::get))
    }

    @Composable
    private fun Picker(select: (String) -> Unit) {
        var catalog by remember { mutableStateOf<Catalog?>(null) }
        var failed by remember { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(Unit) {
            try {
                catalog = loadCatalog()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
        val data = catalog
        val results by produceState<List<StationIdentity>?>(null, data, query) {
            value = if (data == null || query.isBlank()) null else withContext(Dispatchers.Default) {
                data.search.search(query, data.anchor).stations.map { it.identity }
            }
        }
        Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp)) {
            Text(stringResource(R.string.query_choose), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.list_search)) })
            LazyColumn {
                if (query.isBlank()) {
                    item { Heading(R.string.widget_nearest_section) }
                    item { Text(stringResource(R.string.widget_location_hint), style = MaterialTheme.typography.bodySmall) }
                    items(NearestStation.entries) { mode ->
                        val label = when (mode) {
                            NearestStation.ANY -> R.string.widget_nearest_any
                            NearestStation.TIDE -> R.string.widget_nearest_tide
                            NearestStation.CURRENT -> R.string.widget_nearest_current
                        }
                        ListItem(headlineContent = { Text(stringResource(label)) },
                            modifier = Modifier.clickable { select(mode.key) })
                    }
                    stationRows(R.string.list_favorites, data?.favorites.orEmpty(), select)
                    stationRows(R.string.list_recents, data?.recents.orEmpty(), select)
                } else {
                    stationRows(R.string.list_search, results.orEmpty(), select)
                    if (results?.isEmpty() == true) item { Text(stringResource(R.string.search_no_matches)) }
                }
                if (failed) item { Text(stringResource(R.string.list_catalog_unavailable)) }
                else if (data == null) item { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun Heading(resource: Int) {
    Text(stringResource(resource), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
}

private fun LazyListScope.stationRows(header: Int, stations: List<StationIdentity>, select: (String) -> Unit) {
    if (stations.isEmpty()) return
    item { Heading(header) }
    items(stations, key = { it.id.value }) { station ->
        val series = if (station.series == StationSeries.Current) R.string.detail_currents else R.string.detail_tides
        ListItem(headlineContent = { Text(station.name) },
            supportingContent = { Text(listOf(station.region, stringResource(series), station.id.value).joinToString(" · ")) },
            modifier = Modifier.clickable { select(station.id.value) })
    }
}
