package io.openwaters.slackwater.android.detail

import io.openwaters.slackwater.android.core.tour.TourStep
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.alerts.plotZone
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.stations.*
import io.openwaters.slackwater.android.data.SpeedUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DetailHeader(station: StationIdentity?, id: StationId, live: Boolean, data: StationListState?,
                          link: String?, linked: Boolean, back: () -> Unit, toggle: () -> Unit) {
    val context = LocalContext.current
    val favorite = data?.favorites?.contains(station?.id ?: id) == true
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val backLabel = stringResource(if (linked) R.string.reminder_back_to_stations else R.string.station_back)
        IconButton(onClick = back, modifier = Modifier.semantics { contentDescription = backLabel }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
        Spacer(Modifier.weight(1f))
        if (link != null) TextButton(onClick = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, link), null))
        }) { Text(stringResource(R.string.detail_share)) }
        if (live) {
            val star = stringResource(if (favorite) R.string.station_unstar else R.string.station_star)
            IconButton(onClick = toggle, modifier = Modifier.tourAnchor(TourStep.STAR).semantics { contentDescription = star }) {
                Text(if (favorite) "★" else "☆", color = if (favorite) androidx.compose.ui.graphics.Color(0xfff0c860) else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val map = io.openwaters.slackwater.android.map.LocalOpenStationMap.current
        Text(station?.name ?: id.value, Modifier.then(if (map == null || station == null || station.kind == StationKind.Retired) Modifier else Modifier.clickable(onClickLabel = stringResource(R.string.detail_map), role = Role.Button) { map(station) }).semantics { heading() }, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        val location = (data?.slot as? LocationSlot.Located)?.fix?.coordinate
        val distance = if (location != null && station != null) stringResource(R.string.list_distance_nm,
            formatNauticalMiles(distanceKm(station.coordinate, location), LocalConfiguration.current.locales[0])) else null
        Text(listOfNotNull(station?.region?.takeIf(String::isNotBlank), distance).joinToString(" • "))
    }
}

@Composable
internal fun DetailRelated(station: StationIdentity, meta: DetailMetadata?, model: StationListModel, open: (StationId) -> Unit, nearby: Boolean) {
    val catalog by model.catalog.collectAsState()
    val filter = model.state.collectAsState().value?.filter
    val prefs by model.readingPreferences.collectAsState()
    val now = remember(station.id) { Instant.now() }
    val ranked by produceState(emptyList<RankedStation>(), station.id, catalog) {
        value = withContext(Dispatchers.Default) { rankByDistance(catalog?.byId?.values?.filter { it.id != station.id }.orEmpty(), station.coordinate) }
    }
    if (nearby) {
        Text(stringResource(R.string.detail_nearby), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to R.string.detail_all, StationSeries.Tide to R.string.detail_tides, StationSeries.Current to R.string.detail_currents).forEach { (series, label) ->
                FilterChip(selected = filter == series, onClick = { model.launchEdit { model.listPreferences.setSeriesFilter(series) } },
                    label = { Text(stringResource(label)) })
            }
        }
        val shown = ranked.filter { filter == null || it.identity.series == filter }.take(6)
        shown.forEach { row ->
            key(row.id) {
                StationCard(row.identity, row.distanceKm, now, prefs, { at, threshold -> model.card(row.id, at, threshold) }, { open(row.id) })
                val provider = when {
                    row.id.value.startsWith("chs-") -> "CHS"
                    row.id.value.removePrefix("current:").startsWith("noaa/") -> "NOAA"
                    else -> row.identity.source
                }
                val kind = stringResource(if (row.identity.series == StationSeries.Tide) R.string.detail_tides else R.string.detail_currents)
                Text(listOfNotNull(kind, provider, bearingText(station.coordinate, row.identity.coordinate)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (shown.isNotEmpty()) NearbyMap(station, shown, open)
    } else {
        val paired = meta?.pairedTide?.let { catalog?.byId?.get(it) }
        val opposite = ranked.firstOrNull { it.distanceKm <= HERO_CROSS_SERIES_RADIUS_KM && it.identity.series != station.series }
        val other = paired ?: opposite?.identity
        if (other != null) TextButton(onClick = { open(other.id) }) {
            val name = stringResource(if (other.series == StationSeries.Current) R.string.detail_related_currents else R.string.detail_related_tides, other.name)
            Text(if (paired == null && opposite != null) name + " · " + stringResource(R.string.list_distance_nm,
                formatNauticalMiles(opposite.distanceKm, LocalConfiguration.current.locales[0])) else name)
        }
        val others = remember(station, ranked) { ranked.filter { station.place != null && it.identity.place == station.place } }
        var chooser by rememberSaveable(station.id.value) { mutableStateOf(false) }
        if (others.isNotEmpty()) TextButton(onClick = { chooser = true }) { Text(stringResource(R.string.detail_namesakes, others.size)) }
        if (chooser) AlertDialog(onDismissRequest = { chooser = false }, title = { Text(station.name) }, text = {
            androidx.compose.foundation.lazy.LazyColumn { items(others.size) { index ->
                val item = others[index].identity
                TextButton(onClick = { model.launchEdit { model.places.chooseStation(item.id) }; chooser = false; open(item.id) }) {
                    Text(item.region.ifBlank { item.id.value } + " · " + item.id.value)
                }
            } }
        }, confirmButton = { TextButton(onClick = { chooser = false }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
internal fun StationDetails(station: StationIdentity, meta: DetailMetadata?, ready: PredictionResult.Ready?,
                            speedUnit: SpeedUnit, today: LocalDate, zone: ZoneId, revision: Long) {
    var expanded by rememberSaveable(station.id.value) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.detail_details)) }
    if (!expanded) return
    val online = meta?.gate?.online == true
    val saved = if (online) onlineCoverage(station.id, revision) else emptyList()
    val method = when (ready?.capabilities) {
        StationCapabilities.DERIVED_TIMING -> R.string.detail_derived
        StationCapabilities.ONLINE_CURRENT, StationCapabilities.TIDE_PREVIEW -> R.string.detail_official
        StationCapabilities.SUBORDINATE_TIDE, StationCapabilities.SUBORDINATE_CURRENT -> R.string.detail_subordinate
        StationCapabilities.HARMONIC_CURRENT, StationCapabilities.HARMONIC_TIDE -> R.string.detail_harmonic
        null -> if (online && saved.isNotEmpty()) R.string.detail_official else null
        else -> R.string.detail_fitted
    }
    listOf(R.string.detail_station to station.id.value, R.string.detail_position to formatCoordinate(station.coordinate),
        R.string.detail_zone to station.timezone, R.string.detail_datum to meta?.datum, R.string.detail_reference to meta?.reference,
        R.string.detail_source to meta?.credit, R.string.detail_license to station.license).forEach { (label, value) ->
        if (value != null) Text(stringResource(label) + " · " + value)
    }
    if (method != null) Text(stringResource(R.string.detail_prediction) + " · " + stringResource(method))
    if (online) {
        GateExplanation(meta?.gate, speedUnit)
        if (saved.isEmpty()) Text(stringResource(R.string.card_not_downloaded), Modifier.testTag("online-detail-download-status"))
        else OnlineValidity(savedThrough(saved, today, zone), today)
    }
    if (meta?.datum != null) Text(stringResource(R.string.detail_datum_note))
    if (station.kind == StationKind.DerivedGate) {
        if (meta?.highLag != null && meta.lowLag != null) Text(stringResource(R.string.detail_lag_value, meta.highLag, meta.lowLag))
        Text(stringResource(R.string.detail_shape_note))
    }
    meta?.meanFlow?.let { Text(stringResource(R.string.detail_flow) + " · " +
        if (kotlin.math.abs(it) < 0.05) stringResource(R.string.detail_none) else "%.2f kn".format(it)) }
}

@Composable
internal fun DetailFeedback(station: StationIdentity, moment: Instant, link: String?, unavailable: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun report(title: String) {
        menu = false
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val body = listOfNotNull(title, "", "—", station.name, station.id.value,
            moment.atZone(station.plotZone()).toString(), link, "Slackwater $version").joinToString("\n")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:slackwater@openwaters.io?subject=" +
            Uri.encode(title) + "&body=" + Uri.encode(body)))
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, body))
            copied = true
        }
    }
    Box {
        if (unavailable) Column {
            Text(stringResource(R.string.detail_help))
            val title = stringResource(R.string.detail_share_details)
            TextButton(onClick = { report(title) }) { Text(title) }
        } else TextButton(onClick = { menu = true }) { Text(stringResource(R.string.detail_feedback)) }
        DropdownMenu(menu, { menu = false }) {
            listOf(R.string.detail_wrong_place, R.string.detail_wrong_name, R.string.detail_wrong_prediction).forEach {
                val title = stringResource(it)
                DropdownMenuItem(text = { Text(title) }, onClick = { report(title) })
            }
        }
    }
    if (copied) AlertDialog(onDismissRequest = { copied = false }, title = { Text(stringResource(R.string.detail_mail_unavailable)) },
        text = { Text(stringResource(R.string.detail_copied)) }, confirmButton = {
            TextButton(onClick = { copied = false }) { Text(stringResource(R.string.close)) }
        })
}

@Composable
internal fun DetailProvenance(station: StationIdentity, metadata: DetailMetadata?, ready: PredictionResult.Ready?, referenceName: String?) {
    val basis = when (ready?.capabilities) {
        StationCapabilities.HARMONIC_TIDE -> metadata?.publisher?.takeIf { !station.id.value.startsWith("noaa/") }?.let {
            stringResource(R.string.detail_publisher, it)
        } ?: if (station.id.value.startsWith("noaa/")) stringResource(R.string.detail_noaa_tide) else ready.source
        StationCapabilities.SUBORDINATE_TIDE -> stringResource(R.string.detail_noaa_sub, referenceName ?: metadata?.reference.orEmpty())
        StationCapabilities.HARMONIC_CURRENT -> stringResource(R.string.detail_noaa_current)
        StationCapabilities.SUBORDINATE_CURRENT -> stringResource(R.string.detail_sub_current)
        StationCapabilities.TIDE_PREVIEW -> stringResource(R.string.detail_chs_preview)
        StationCapabilities.DERIVED_TIMING -> stringResource(R.string.detail_derived_source, station.name, referenceName ?: metadata?.reference.orEmpty())
        StationCapabilities.ONLINE_CURRENT -> stringResource(R.string.detail_official)
        null -> null
        else -> stringResource(R.string.detail_chs_fit)
    }
    val flood = (ready?.series as? PredictionSeries.Current)?.floodBearing ?: metadata?.flood
    val line = listOfNotNull(metadata?.datum?.let { stringResource(R.string.detail_datum_source, it) }, basis,
        flood?.let { stringResource(R.string.detail_flood, Math.round(it).toInt()) }).joinToString(" ")
    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall)
    metadata?.credit?.takeIf { it.isNotBlank() && it != basis }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
