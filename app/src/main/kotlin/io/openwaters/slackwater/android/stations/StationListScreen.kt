package io.openwaters.slackwater.android.stations

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.LocationSlot
import io.openwaters.slackwater.android.core.station.RankedStation
import io.openwaters.slackwater.android.core.station.RankingAnchor
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationSeries
import io.openwaters.slackwater.android.core.station.formatCoordinate
import io.openwaters.slackwater.android.core.station.promptRefused
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GoGreen = Color(0xFF88B868)
private val AttentionAmber = Color(0xFFE8A33D)
private val SunTint = Color(0xFFF0C860)

private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** The home surface: My Location, Favorites, Near Me and Recents, each station once (list §§3–5). */
@Composable
fun StationListScreen(
    model: StationListModel,
    onOpen: (StationId) -> Unit,
    onSearch: () -> Unit,
    onCredits: () -> Unit,
    now: Instant,
    showing: Boolean = true,
    onMap: (() -> Unit)? = null,
    mapShowing: Boolean = false,
    footerExtras: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    val readingPreferences by model.readingPreferences.collectAsState()
    val listPreferences by model.preferences.collectAsState()
    val catalogFailed by model.catalogFailed.collectAsState()
    var chooser by remember { mutableStateOf<RemovedStation?>(null) }
    val listState = rememberLazyListState()

    // The prompt's answer, read against the rationale flag around it, says whether the system will ask again.
    var promptStarted by remember { mutableLongStateOf(0L) }
    var rationaleBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants.values.any { it }
        val rationaleAfter = activity?.let { a -> locationPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(a, it) } } ?: false
        model.location.promptAnswered(promptRefused(granted, rationaleBefore, rationaleAfter, SystemClock.elapsedRealtime() - promptStarted))
        scope.launch { model.refreshLocation() }
    }
    fun ask() {
        rationaleBefore = activity?.let { a -> locationPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(a, it) } } ?: false
        promptStarted = SystemClock.elapsedRealtime()
        model.location.promptShown()
        model.launchEdit { model.listPreferences.markLocationAsked() }
        launcher.launch(locationPermissions)
    }

    // The list asks for location once, on its first appearance on a first launch, and never again. It waits for
    // the stored flag, so a slow read never looks like a first launch.
    val asked = listPreferences?.locationAsked
    // Never over a page, search or an alert's screen: it waits until the list itself is showing.
    LaunchedEffect(state != null, asked, showing) {
        if (showing && state != null && asked != null && model.claimLocationAsk(asked)) ask()
    }

    val current = state
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        if (catalogFailed) Text(stringResource(R.string.list_catalog_unavailable)) else CircularProgressIndicator()
                    }
                }
            } else {
                locationSlot(current, model, readingPreferences, now, onOpen, onAsk = ::ask, onSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                })
                savedSection(R.string.list_favorites, current.sections.favorites, current, model, readingPreferences, now, onOpen,
                    onRemoved = { chooser = it }, isFavorites = true)
                nearMe(current, model, readingPreferences, now, onOpen) { series ->
                    model.launchEdit { model.listPreferences.setSeriesFilter(series) }
                }
                savedSection(R.string.list_recents, current.sections.recents, current, model, readingPreferences, now, onOpen,
                    onRemoved = { chooser = it }, isFavorites = false)
                footer(onCredits, footerExtras)
            }
            // Keeps the last row above the floating search button.
            item { Spacer(Modifier.height(96.dp)) }
        }
        if (onMap != null) ExtendedFloatingActionButton(
            onClick = onMap, modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 16.dp, bottom = 24.dp).heightIn(min = 56.dp)
        ) { Text(stringResource(if (mapShowing) R.string.map_list else R.string.map_open)) }
        ExtendedFloatingActionButton(
            onClick = onSearch,
            modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 16.dp, bottom = 24.dp).heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.list_search)) }
    }

    chooser?.let { removed ->
        ReplacementChooser(removed, model, readingPreferences, now, onDismiss = { chooser = null }) { replacement ->
            chooser = null
            model.launchEdit { model.places.replaceFavorite(removed.id, replacement) }
            onOpen(replacement)
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    // Uppercase is visual only: assistive technology hears the heading as written.
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        color = GoGreen,
        modifier = modifier.semantics { heading(); contentDescription = text },
    )
}

private fun fallbackPlace(state: StationListState, firstRun: String) = state.lastOpenedName ?: firstRun

private fun LazyListScope.locationSlot(
    state: StationListState,
    model: StationListModel,
    preferences: ReadingPreferences,
    now: Instant,
    onOpen: (StationId) -> Unit,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
) {
    item(key = "location-heading") { SectionHeading(stringResource(R.string.list_my_location)) }
    item(key = "location-slot") {
        val firstRun = stringResource(R.string.list_first_run_region)
        val fallback = stringResource(R.string.list_showing_near, fallbackPlace(state, firstRun))
        when (val slot = state.slot) {
            is LocationSlot.Located -> SlotTile(
                if (slot.approximate) stringResource(R.string.list_approximate_location) else formatCoordinate(slot.fix.coordinate),
                monospace = !slot.approximate,
            )
            LocationSlot.Locating -> SlotTile(stringResource(R.string.list_finding_location), spinner = true)
            LocationSlot.Failed -> NoticeCard(stringResource(R.string.list_location_unavailable), fallback, null, null, AttentionAmber)
            LocationSlot.Denied -> NoticeCard(stringResource(R.string.list_location_unavailable), fallback,
                stringResource(R.string.list_go_to_settings), onSettings, AttentionAmber)
            LocationSlot.NotDetermined -> NoticeCard(stringResource(R.string.list_location_unavailable), fallback,
                stringResource(R.string.list_find_tides_near_me), onAsk, GoGreen)
        }
    }
    items(state.sections.heroes, key = { "hero-${it.id.value}" }) { hero ->
        StationCard(hero.identity, hero.distanceKm, now, preferences, { model.reading(hero.id, it) }, { onOpen(hero.id) })
    }
}

@Composable
private fun SlotTile(text: String, spinner: Boolean = false, monospace: Boolean = false) {
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (spinner) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodyLarge, fontFamily = if (monospace) FontFamily.Monospace else null)
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, action: String?, onAction: (() -> Unit)?, accent: Color) {
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action, color = accent) }
            }
        }
    }
}

private fun LazyListScope.nearMe(
    state: StationListState,
    model: StationListModel,
    preferences: ReadingPreferences,
    now: Instant,
    onOpen: (StationId) -> Unit,
    onFilter: (StationSeries?) -> Unit,
) {
    item(key = "near-heading") {
        val heading = if (state.sections.anchor == RankingAnchor.FirstRun) stringResource(R.string.list_first_run_region)
        else stringResource(R.string.list_near_me)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            SectionHeading(heading, Modifier.align(Alignment.CenterVertically).weight(1f))
            SeriesChips(state.filter, onFilter)
        }
    }
    items(state.sections.nearMe, key = { "near-${it.id.value}" }) { station ->
        val favorite = SwipeAction(stringResource(R.string.action_favorite), SunTint) {
            model.launchEdit { model.places.setFavorite(station.id, true) }
        }
        SwipeRow(leading = favorite, trailing = null) {
            StationCard(
                station.identity,
                station.distanceKm.takeIf { state.sections.showsDistances },
                now, preferences, { model.reading(station.id, it) }, { onOpen(station.id) },
                actions = listOf(CustomAccessibilityAction(favorite.label) { favorite.onAction(); true }),
            )
        }
    }
}

/** The three chips that narrow Near Me and search. Tapping the active one returns to All. */
@Composable
fun SeriesChips(filter: StationSeries?, onSelect: (StationSeries?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((series, label, spoken) in listOf(
            Triple(null, R.string.list_filter_all, R.string.list_filter_show_all),
            Triple(StationSeries.Tide, R.string.list_filter_tides, R.string.list_filter_show_tides),
            Triple(StationSeries.Current, R.string.list_filter_currents, R.string.list_filter_show_currents),
        )) {
            val description = stringResource(spoken)
            FilterChip(
                selected = filter == series,
                onClick = { onSelect(if (series == filter) null else series) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { selected = filter == series; contentDescription = description },
            )
        }
    }
}

private fun LazyListScope.savedSection(
    heading: Int,
    ids: List<StationId>,
    state: StationListState,
    model: StationListModel,
    preferences: ReadingPreferences,
    now: Instant,
    onOpen: (StationId) -> Unit,
    onRemoved: (RemovedStation) -> Unit,
    isFavorites: Boolean,
) {
    if (ids.isEmpty()) return
    val prefix = if (isFavorites) "favorite" else "recent"
    item(key = "$prefix-heading") { SectionHeading(stringResource(heading)) }
    items(ids, key = { "$prefix-${it.value}" }) { id ->
        val removed = state.removed[id]
        val station = state.stations[id]
        when {
            removed != null && isFavorites -> {
                val remove = SwipeAction(stringResource(R.string.action_remove), AttentionAmber) {
                    model.launchEdit { model.places.forgetFavorite(id) }
                }
                SwipeRow(leading = null, trailing = remove) { RemovedStationCard(removed, remove) { onRemoved(removed) } }
            }
            station != null -> {
                val leading = if (isFavorites) null else SwipeAction(stringResource(R.string.action_favorite), SunTint) {
                    model.launchEdit { model.places.setFavorite(id, true) }
                }
                val trailing = if (isFavorites) {
                    SwipeAction(stringResource(R.string.action_unfavorite), AttentionAmber) {
                        // An unstarred station moves to the front of Recents rather than disappearing.
                        model.launchEdit { model.places.setFavorite(id, false) }
                    }
                } else {
                    SwipeAction(stringResource(R.string.action_remove), AttentionAmber) {
                        model.launchEdit { model.places.removeRecent(id) }
                    }
                }
                SwipeRow(leading = leading, trailing = trailing) {
                    StationCard(
                        station, null, now, preferences, { model.reading(id, it) }, { onOpen(id) },
                        actions = listOfNotNull(leading, trailing).map { action ->
                            CustomAccessibilityAction(action.label) { action.onAction(); true }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemovedStationCard(removed: RemovedStation, remove: SwipeAction, onChoose: () -> Unit) {
    val region = removed.lastKnown?.region?.ifEmpty { null }
    val headline = when {
        region == null -> stringResource(R.string.removed_gone_unknown)
        removed.withdrawnByChs -> stringResource(R.string.removed_withdrawn, region)
        else -> stringResource(R.string.removed_gone, region)
    }
    val detail = stringResource(if (removed.withdrawnByChs) R.string.removed_withdrawn_detail else R.string.removed_gone_detail)
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().semantics {
            customActions = listOf(CustomAccessibilityAction(remove.label) { remove.onAction(); true })
        },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(removed.lastKnown?.name ?: stringResource(R.string.removed_title), style = MaterialTheme.typography.titleMedium, color = AttentionAmber)
            Text(headline, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onChoose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.removed_choose), color = AttentionAmber)
                }
                // Reachable by keyboard and switch access too, not only by swiping.
                TextButton(onClick = remove.onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(remove.label) }
            }
        }
    }
}

@Composable
private fun ReplacementChooser(
    removed: RemovedStation,
    model: StationListModel,
    preferences: ReadingPreferences,
    now: Instant,
    onDismiss: () -> Unit,
    onChoose: (StationId) -> Unit,
) {
    val state by model.state.collectAsState()
    val anchor = state?.sections?.anchor?.coordinate ?: io.openwaters.slackwater.android.core.station.FIRST_RUN_ANCHOR
    var choices by remember(removed) { mutableStateOf<List<RankedStation>>(emptyList()) }
    LaunchedEffect(removed) { choices = model.replacements(removed, anchor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.removed_choose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.removed_choose_prompt))
                for (choice in choices) {
                    StationCard(choice.identity, null, now, preferences, { model.reading(choice.id, it) }, { onChoose(choice.id) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private fun LazyListScope.footer(onCredits: () -> Unit, extras: @Composable () -> Unit) {
    item(key = "footer") {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCredits, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.data_and_licenses))
                }
            }
            extras()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.list_wordmark), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.list_by_open_waters), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.list_not_for_navigation), style = MaterialTheme.typography.bodySmall)
        }
    }
}
