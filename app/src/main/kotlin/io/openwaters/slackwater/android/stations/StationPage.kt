package io.openwaters.slackwater.android.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.StationAlerts
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant

/**
 * The page a card opens until the full station detail exists: the station's identity, live reading and star, then
 * its alerts, calendar, schedule and printable tables. Opening it records the station in Recents, as every station
 * detail does.
 */
@Composable
fun StationPage(model: StationListModel, id: StationId, now: Instant, onBack: () -> Unit) {
    val catalog by model.catalog.collectAsState()
    val state by model.state.collectAsState()
    val preferences by model.readingPreferences.collectAsState()
    val station = catalog?.byId?.get(id)
    LaunchedEffect(id) { model.places.recordVisit(id) }
    val favorite = state?.favorites?.contains(id) == true
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.station_back)) }
                Text(station?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { model.launchEdit { model.places.setFavorite(id, !favorite) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(if (favorite) R.string.station_unstar else R.string.station_star)) }
            }
            if (station != null) {
                StationCard(station, null, now, preferences, { model.reading(id, it) }, onOpen = {})
            }
            // This station's alerts, calendar, schedule and printable tables.
            StationAlerts(destination = null, station = id) {}
        }
    }
}
