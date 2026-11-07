package io.openwaters.slackwater.android.detail

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.*
import io.openwaters.slackwater.android.calendars.StationCalendarButton
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.core.timeline.*
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.stations.StationListModel
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private data class LoadedDetail(val timeline: DetailTimeline, val ready: PredictionResult.Ready)

private val waterColors = darkColorScheme(
    primary = Color(0xffa4d77d), onPrimary = Color(0xff143511),
    secondary = Color(0xff8ec6d6), tertiary = Color(0xfff0c860),
    surface = Color(0xff0c1b24), background = Color(0xff0c1b24),
    onSurface = Color(0xfff3f5ef), onSurfaceVariant = Color(0xffc2cfcf),
    surfaceVariant = Color(0xff20323b), surfaceContainer = Color(0xff182b34),
    surfaceContainerHigh = Color(0xff21353e), surfaceContainerHighest = Color(0xff293e46),
    outline = Color(0xff789086), primaryContainer = Color(0xff29472e),
    secondaryContainer = Color(0xff23444f), onSecondaryContainer = Color(0xffd6f2f0),
)

@Composable
internal fun StationDetailScreen(model: StationListModel, id: StationId, clock: Instant, linkedTime: Instant?,
                                onOpen: (StationId) -> Unit, onBack: () -> Unit, recordVisit: Boolean = true,
                                downloads: OnlineGateDownloads = OnlineGateDownloads.get(LocalContext.current)) {
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(activity) {
        val bars = activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (lightStatus != null) bars.isAppearanceLightStatusBars = lightStatus
            if (lightNavigation != null) bars.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    MaterialTheme(colorScheme = waterColors) { StationDetailBody(model, id, clock, linkedTime, onOpen, onBack, recordVisit, downloads) }
}

@Composable
private fun StationDetailBody(model: StationListModel, id: StationId, clock: Instant, linkedTime: Instant?,
                                onOpen: (StationId) -> Unit, onBack: () -> Unit, recordVisit: Boolean, downloads: OnlineGateDownloads) {
    val context = LocalContext.current.applicationContext
    val repository = remember { AndroidPredictionRepository(context) }
    val catalog = remember { BundledStationCatalog(context.assets) }
    val online by downloads.online.collectAsState()
    val records = remember { DetailRecords(context.assets) }
    val identity by produceState<StationResolution?>(null, id) { value = catalog.resolve(id) }
    val resolved = identity
    val known = (resolved as? StationResolution.Successor)?.target ?: resolved as? StationResolution.Known
    val station = known?.identity
    val live = known is StationResolution.Live
    val data by model.state.collectAsState()
    val prefs by model.readingPreferences.collectAsState()
    val revision by ModelStore.get(context).revision.collectAsState()
    val metadata by produceState<DetailMetadata?>(null, station) {
        value = station?.let { withContext(Dispatchers.IO) { records.metadata(it) } }
    }
    LaunchedEffect(station?.id, live, recordVisit) { if (recordVisit && live && station != null) model.launchEdit { model.places.recordVisit(station.id) } }
    val zone = station?.plotZone() ?: ZoneOffset.UTC
    var restoredTime by rememberSaveable { mutableStateOf(linkedTime?.toString()) }
    var restoredDay by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerRevision by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<LoadedDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadAt by remember { mutableStateOf<Instant?>(null) }
    val expired = loaded?.timeline?.takeIf {
        it.mode == TimelineMode.PREVIEW && it.state.now !in it.state.coverage.start..it.state.coverage.end
    }
    LaunchedEffect(expired) {
        if (expired != null) {
            reloadAt = expired.state.now
            restoredTime = null
            restoredDay = null
            loaded = null
            expired.close()
        }
    }
    DisposableEffect(Unit) { onDispose { loaded?.timeline?.close() } }
    LaunchedEffect(station, revision, reloadAt, pickerRevision) {
        if (station == null || !live) { loading = resolved == null; return@LaunchedEffect }
        val previous = loaded?.timeline
        if (previous?.mode == TimelineMode.ONLINE && (previous.missingDay != null || previous.state.onlineDayRequested != null)) {
            return@LaunchedEffect
        }
        val anchor = restoredDay?.let(LocalDate::parse)
        val instant = previous?.state?.selected ?: restoredTime?.let(Instant::parse) ?: clock.takeIf { restoredDay != null }
        val now = previous?.state?.now ?: reloadAt ?: clock
        val day = anchor ?: (instant ?: now).atZone(zone).toLocalDate()
        val window = TimelineWindow.fixed(day, zone)
        var next: DetailTimeline? = null
        try {
            loading = previous == null && (loading || metadata?.gate?.online != true)
            var result = repository.load(station.id, TimeSpan(window.start, window.end), PredictionPurpose.DISPLAY)
            if (result is PredictionResult.Unavailable && instant != null && station.kind == StationKind.CanadianTide) {
                result = repository.load(station.id, TimeSpan(now, now), PredictionPurpose.DISPLAY)
            }
            val ready = result.onlineTimelineBlock(window) ?: result as? PredictionResult.Ready
            if (ready != null) next = openDetailTimeline(repository, station, zone, now, ready,
                !ValueAnimator.areAnimatorsEnabled(), instant, day.takeIf { ready.capabilities == StationCapabilities.ONLINE_CURRENT } ?: anchor)
            if (previous != null && next != null) {
                do {
                    val settled = snapshotFlow { previous.state }.first { !it.busy }
                    next.restorePosition(settled)
                    val current = previous.state
                } while (current.busy || settled.selected != current.selected || settled.anchor != current.anchor)
            }
            previous?.close()
            loaded = if (next != null && ready != null) LoadedDetail(next, ready) else null
            next = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("StationDetail", "Cannot load ${id.value}", error)
            previous?.close()
            loaded = null
        } finally { next?.close(); loading = false }
    }
    val detail = loaded.takeIf { expired == null }
    val timeline = detail?.timeline
    DetailHinge(timeline)
    if (timeline != null && station != null) {
        DetailTimelineEffects(timeline, station, prefs.effectiveThresholdKn(station.id), prefs.showSky, online) { day ->
            downloads.fetch(station.id, day)
        }
        LaunchedEffect(timeline) {
            snapshotFlow { timeline.state }.collect { state ->
                if (state.viewport > 0.0 && state.motion != TimelineMotion.INITIAL_POSITIONING) {
                    val position = state.move?.to?.takeIf { state.motion == TimelineMotion.HINGE_FOLLOW } ?: state.selected
                    restoredTime = if (Duration.between(position, state.now).abs() <= Duration.ofSeconds(200)) null else position.toString()
                    restoredDay = state.anchor.toString()
                }
            }
        }
    }
    val gate = metadata?.gate?.online == true
    val today = (timeline?.state?.now ?: clock).atZone(zone).toLocalDate()
    val requestedDay = timeline?.missingDay ?: restoredDay?.let(LocalDate::parse)
        ?: (restoredTime?.let(Instant::parse) ?: clock).atZone(zone).toLocalDate()
    val missing = gate && !loading && (detail == null || timeline?.missingDay != null)
    var retry by remember(id, requestedDay) { mutableIntStateOf(0) }
    LaunchedEffect(gate, id, today, online, detail != null) {
        if (gate && online && detail != null) downloads.refreshIfStale(id, today)
    }
    LaunchedEffect(missing, requestedDay, retry, online) {
        if (missing && online) {
            val covered = downloads.fetch(id, requestedDay, manual = retry > 0)
            if (covered && timeline?.missingDay == requestedDay) timeline.dispatch(TimelineAction.PickDay(requestedDay))
            if (requestedDay != today) downloads.refreshIfStale(id, today)
        }
    }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    fun pickDay(day: LocalDate) {
        if (timeline != null) timeline.dispatch(TimelineAction.PickDay(day))
        else {
            restoredDay = day.toString()
            restoredTime = day.atTime(12, 0).atZone(zone).toInstant().toString()
            pickerRevision++
        }
        picker = null
    }
    val scroll = rememberScrollState()
    val page = rememberDetailPageScroll(scroll)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().then(page.viewportModifier)
            .onGloballyPositioned { page.measureViewport(it, scroll) }.verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val selected = timeline?.state?.let { if (it.home) null else it.selected }
            val link = detailShareLink(metadata?.route, selected, zone)
            DetailHeader(station, id, live, data, link, linkedTime != null, onBack) {
                if (station != null) model.launchEdit { model.places.setFavorite(station.id, data?.favorites?.contains(station.id) != true) }
            }
            if (linkedTime != null && timeline == null && station != null) Text(station.timeLabel(linkedTime))
            when {
                resolved == null || (live && loading && detail == null) -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                !live || station == null -> {
                    Text(stringResource(R.string.detail_unavailable), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.detail_gap))
                    if (station != null) DetailFeedback(station, selected ?: clock, link, unavailable = true)
                }
                missing -> {
                    val saved = onlineCoverage(id, revision)
                    if (saved.isNotEmpty()) ScheduleWeekBar(requestedDay, requestedDay.plusDays(6), clock, zone,
                        choose = { picker = requestedDay.toString() }, change = { pickDay(requestedDay.plusWeeks(it.toLong())) })
                    OnlineGateStatus(downloads, id, requestedDay, today, zone, revision, online,
                        explanation = { GateExplanation(metadata?.gate, prefs.speedUnit) }, retry = { retry++ }) {
                        val now = Instant.now()
                        if (timeline != null) timeline.dispatch(TimelineAction.Now(now))
                        else {
                            restoredDay = now.atZone(zone).toLocalDate().toString()
                            restoredTime = now.toString()
                            reloadAt = now
                        }
                    }
                    OnlineAlternative(station, model, repository, clock, revision, onOpen)
                }
                detail == null -> MissingPredictions(station, metadata, model)
                else -> DetailContent(station, detail, prefs, repository, downloads, revision, metadata, page.scheduleModifier(scroll),
                    related = { DetailRelated(station, metadata, model, onOpen, nearby = false) }) {
                    page.cancelRestore()
                    picker = it.toString()
                }
            }
            if (station != null) {
                if (live) {
                    if (!missing && (station.kind != StationKind.CanadianCurrent || metadata != null) && (!gate || detail != null)) {
                        StationCalendarButton(station.id)
                        Text(stringResource(R.string.detail_notice), style = MaterialTheme.typography.bodySmall)
                        DetailProvenance(station, metadata, detail?.ready,
                            metadata?.reference?.let { data?.stations?.get(StationId(it))?.name })
                        DetailFeedback(station, timeline?.state?.selected ?: clock, link)
                    }
                    StationDetails(station, metadata, detail?.ready, prefs.speedUnit, today, zone, revision)
                }
                if (!live) DetailProvenance(station, metadata, null, null)
                DetailRelated(station, metadata, model, onOpen, nearby = true)
            }
        }
    }
    picker?.let { day ->
        DetailDatePicker(LocalDate.parse(day), ::pickDay, { picker = null })
    }
}

@Composable
private fun DetailContent(station: StationIdentity, detail: LoadedDetail, prefs: ReadingPreferences,
                          repository: PredictionRepository, downloads: OnlineGateDownloads, revision: Long, metadata: DetailMetadata?, scheduleModifier: Modifier, related: @Composable () -> Unit, pick: (LocalDate) -> Unit) {
    val timeline = detail.timeline
    val state = timeline.state
    val series = timeline.series
    val cap = detail.ready.capabilities
    val threshold = prefs.effectiveThresholdKn(station.id)
    val supportsAlerts = cap.supports(PredictionPurpose.ALERT)
    val units = prefs.heightUnits()
    DetailAlertControls(state.now) { rules, edit, showRules ->
        val base = remember(series, cap, state.now, threshold) {
            val next = when (series) {
                is PredictionSeries.Tide -> series.events.firstOrNull { it.time >= state.now }?.let { AlertTrigger.TideExtreme(it.kind == ExtremeKind.HIGH) to it.time }
                is PredictionSeries.Current -> series.events.firstOrNull { it.time >= state.now }?.let {
                    (if (it.kind == CurrentEventKind.SLACK) AlertTrigger.SlackWindowOpens else AlertTrigger.CurrentPeak(it.kind == CurrentEventKind.MAX_FLOOD)) to it.time }
                is PredictionSeries.Derived -> series.slacks.firstOrNull { it.time >= state.now }?.let { AlertTrigger.Slack to it.time }
            }
            val occurrence = if (next?.first == AlertTrigger.SlackWindowOpens && series is PredictionSeries.Current) {
                io.openwaters.slackwater.slackRuns(series.points, series.events, threshold)
                    .firstOrNull { next.second >= it.start && next.second <= it.end }?.start ?: next.second
            } else next?.second
            ChartAlertSelection(station, cap, next?.first ?: when (series) {
                is PredictionSeries.Tide -> AlertTrigger.TideExtreme(true)
                is PredictionSeries.Current -> AlertTrigger.SlackWindowOpens
                is PredictionSeries.Derived -> AlertTrigger.Slack
            }, next?.second ?: state.now, occurrence)
        }
        val markerSpan = TimeSpan(state.coverage.start, state.coverage.end)
        val marks = rememberAlertMarkers(true, base, rules, markerSpan, threshold, repository)
        var parked by remember(timeline) { mutableStateOf<Instant?>(null) }
        val press: ((Instant) -> Unit)? = if (supportsAlerts) ({ at -> parked = at }) else null
        val directAlert: ((Instant) -> Unit)? = if (supportsAlerts) ({ at ->
            plotSelection(series, base, at, units)?.lineDraft(true, rules, state.now)?.let(edit)
        }) else null
        val input: (String) -> Modifier = { spoken -> Modifier.detailTimelineInput(timeline, spoken, press, directAlert,
            if (timeline.mode == TimelineMode.PREVIEW) null else pick) }
        TimelineCanvas(station, series, timeline.days, state.selected, state.now, inputModifier = input,
            thresholdKnots = threshold, alertMoments = marks, useFeet = units == HeightUnits.IMPERIAL,
            speedUnit = SpeedUnitKind.valueOf(prefs.speedUnit.name), bearingDegrees = prefs.setFormat == SetFormat.DEGREES,
            approximate = cap == StationCapabilities.BOUNDED_CURRENT || cap == StationCapabilities.PROVISIONAL_CURRENT,
            chartModel = detail.ready.chartModel, modelYear = state.now.atZone(timeline.zone).year,
            showSky = prefs.showSky, fixedWindow = timeline.mode != TimelineMode.CONTINUOUS,
            parked = parked?.epochSecond?.div(60) == state.selected.epochSecond / 60, controlsVisible = state.pillsVisible, onTarget = timeline::jump, onNow = { timeline.dispatch(TimelineAction.Now(Instant.now())) })
        val active = state.selected.takeIf { state.motion == TimelineMotion.RESTING && (!state.home || parked?.epochSecond?.div(60) == it.epochSecond / 60) }
        if (supportsAlerts) AlertLine(active?.let { plotSelection(series, base, it, units) } ?: base, active != null, rules, state.now, edit)
        detail.ready.slackErrorBoundMinutes?.let { Text(stringResource(R.string.detail_bound, station.name, it)) }
        if (cap == StationCapabilities.TIDE_PREVIEW || cap == StationCapabilities.PROVISIONAL_CURRENT) {
            Text(stringResource(R.string.detail_more_data))
            if (cap == StationCapabilities.TIDE_PREVIEW) {
                Text(stringResource(R.string.tide_download_saved))
                Text(stringResource(R.string.detail_preview,
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(timeline.zone).format(state.coverage.end)))
            }
        }
        if (timeline.mode == TimelineMode.ONLINE) {
            val today = state.now.atZone(timeline.zone).toLocalDate()
            OnlineGateValidity(downloads, station.id, today, timeline.zone, revision)
            state.onlineDayRequested?.let { day ->
                val online by downloads.online.collectAsState()
                val scope = rememberCoroutineScope()
                OnlineGateStatus(downloads, station.id, day, today, timeline.zone, revision, online, retry = {
                    val generation = timeline.state.generation
                    scope.launch {
                        if (downloads.fetch(station.id, day, manual = true) && timeline.state.generation == generation && timeline.state.onlineDayRequested == day) {
                            timeline.online(day, generation)
                        }
                    }
                }) { timeline.dispatch(TimelineAction.Now(Instant.now())) }
            }
        }
        DetailSummary(station, timeline, prefs, repository, metadata?.pairedTide?.let { StationListModel.get(LocalContext.current).catalog.collectAsState().value?.byId?.get(it)?.name },
            cap == StationCapabilities.PROVISIONAL_CURRENT || cap == StationCapabilities.BOUNDED_CURRENT)
        metadata?.magnitudeNote?.let { Text(stringResource(R.string.detail_context, it)) }
        related()
        Box(scheduleModifier.fillMaxWidth()) {
            DetailSchedule(station, timeline, prefs, repository, revision) { pick(state.anchor) }
        }
        TextButton(onClick = showRules) { Text(stringResource(R.string.reminder_my_alerts)) }
    }
}

@Composable
private fun MissingPredictions(station: StationIdentity, metadata: DetailMetadata?, model: StationListModel) {
    Text(stringResource(R.string.detail_no_data))
    metadata?.magnitudeNote?.let { Text(stringResource(R.string.detail_context, it)) }
    if (station.kind == StationKind.CanadianTide) {
        TideDownloadControls(station.id, model)
    } else if (metadata != null && metadata.gate?.online != true && (station.kind == StationKind.CanadianCurrent || station.kind == StationKind.DerivedGate)) {
        val store = model.places.downloads
        val intent by store.state.collectAsState(null)
        val requestedId = metadata?.pairedTide?.takeIf { station.kind == StationKind.DerivedGate } ?: station.id
        val queued = intent?.getOrNull()?.requested?.contains(requestedId) == true
        Button(enabled = !queued, onClick = { model.launchEdit { store.requestDownloads(listOf(requestedId)) } }) {
            Text(stringResource(if (queued) R.string.detail_queued else R.string.detail_download))
        }
    }
}

@Composable
private fun OnlineAlternative(station: StationIdentity, model: StationListModel, repository: PredictionRepository,
                              now: Instant, revision: Long, open: (StationId) -> Unit) {
    val catalog by model.catalog.collectAsState()
    val alternative by produceState<StationIdentity?>(null, station.id, catalog, revision) {
        value = withContext(Dispatchers.Default) {
            val span = TimeSpan(now, now.plusSeconds(600))
            val candidates = rankByDistance(catalog?.byId?.values.orEmpty().filter {
                it.id != station.id && (it.kind == StationKind.Current || it.kind == StationKind.CanadianCurrent)
            }, station.coordinate).sortedBy { it.identity.kind != StationKind.CanadianCurrent }
            candidates.firstOrNull {
                val result = repository.load(it.id, span, PredictionPurpose.DISPLAY) as? PredictionResult.Ready
                result?.capabilities?.continuous == true
            }?.identity
        }
    }
    alternative?.let { TextButton(onClick = { open(it.id) }) { Text(stringResource(R.string.detail_instead, it.name)) } }
}
