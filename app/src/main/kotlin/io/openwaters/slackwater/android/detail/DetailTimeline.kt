package io.openwaters.slackwater.android.detail

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.timeline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor

private val mainHandler = Handler(Looper.getMainLooper())
private val mainExecutor = Executor { mainHandler.post(it) }

internal class DetailTimeline(
    private val controller: TimelineController,
    private val continuous: ContinuousTimeline?,
    private val repository: PredictionRepository,
    private val station: StationIdentity,
    val zone: ZoneId,
    initialSeries: PredictionSeries,
    initialDays: List<TimelineDay>,
) : AutoCloseable {
    val mode get() = controller.mode
    var state by mutableStateOf(controller.state)
        private set
    var series by mutableStateOf(initialSeries, referentialEqualityPolicy())
        private set
    var days by mutableStateOf(initialDays)
        private set
    var targets = MagneticTargets()
        private set
    var missingDay by mutableStateOf<LocalDate?>(null)
        private set
    var showSky = true
    var closed = false
        private set
    private var published: TimelineChunkData? = null

    init { sync() }

    private fun sync() {
        if (closed) return
        val data = continuous?.content?.data
        if (data != null && published !== data) {
            series = when (series) {
                is PredictionSeries.Tide -> PredictionSeries.Tide(data.tidePoints, data.tideRates, data.tideEvents)
                is PredictionSeries.Current -> PredictionSeries.Current(data.currentPoints, data.currentEvents,
                    data.floodBearing, data.ebbBearing, data.hasCrossAxis)
                is PredictionSeries.Derived -> PredictionSeries.Derived(data.derivedSlacks)
            }
            days = data.days
            published = data
        }
        state = controller.state
    }

    fun dispatch(action: TimelineAction) {
        if (closed) return
        if (action is TimelineAction.Now || action is TimelineAction.PickDay || action is TimelineAction.BeginDrag) missingDay = null
        if (action is TimelineAction.Targets) targets = action.value
        if (continuous == null) controller.dispatch(action)
        else {
            val completion = continuous.dispatch(action)
            if (!completion.isDone) completion.whenCompleteAsync({ _, _ -> sync() }, mainExecutor)
        }
        sync()
    }

    fun jump(target: Instant) {
        if (mode == TimelineMode.ONLINE && state.coverage.clamp(target, state.viewport) != target) {
            dispatch(TimelineAction.PrepareRequest(target, anchor = target.atZone(zone).toLocalDate()))
        } else dispatch(TimelineAction.Request(target))
    }

    fun direct(target: Instant) {
        dispatch(TimelineAction.Request(state.coverage.clamp(target, state.viewport), direct = true))
    }

    /** Rebuild against the new model at the latest settled position, even if it moved while loading. */
    suspend fun restorePosition(previous: TimelineState) {
        dispatch(TimelineAction.Viewport(previous.viewport))
        val request = TimelineAction.Request(previous.selected, direct = true, anchor = previous.anchor)
        when {
            continuous != null -> {
                continuous.dispatch(request).await()
                sync()
            }
            mode == TimelineMode.ONLINE && state.coverage != TimelineWindow.fixed(previous.anchor, zone) -> {
                dispatch(TimelineAction.PrepareRequest(previous.selected, direct = true, anchor = previous.anchor))
                online(previous.anchor, state.generation)
            }
            else -> dispatch(request)
        }
    }

    suspend fun refresh() {
        val request = continuous?.refresh() ?: return
        // The store owns shared requests. Cancelling this effect must not cancel another observer's load.
        try { request.thenApply { it }.await() } finally { sync() }
    }

    suspend fun online(day: LocalDate, generation: Long, fetch: (suspend (LocalDate) -> Boolean)? = null) {
        val window = TimelineWindow.fixed(day, zone)
        suspend fun readBlock() = withContext(Dispatchers.IO) {
            repository.load(station.id, TimeSpan(window.start, window.end), PredictionPurpose.DISPLAY)
                .onlineTimelineBlock(window)
        }
        val block = readBlock() ?: if (fetch?.invoke(day) == true) readBlock() else null
        val chrome = block?.let { fixedDays(station, zone, window) }
        if (closed || state.generation != generation || state.onlineDayRequested != day) return
        if (block == null) {
            if (state.awaitingCoverage) {
                missingDay = day
                dispatch(TimelineAction.RequestFailed(generation))
            }
            return
        }
        // If a request arrives during a drag, defer the entire block until release.
        if (state.motion == TimelineMotion.DRAG) return
        dispatch(TimelineAction.OnlineBlock(day, window))
        if (state.coverage == window) {
            series = block.series
            days = chrome.orEmpty()
        }
    }

    override fun close() {
        closed = true
        continuous?.close()
    }
}

internal suspend fun openDetailTimeline(
    repository: PredictionRepository, station: StationIdentity, zone: ZoneId,
    now: Instant, result: PredictionResult.Ready, reduceMotion: Boolean, initialSelection: Instant? = null,
    initialAnchor: LocalDate? = null,
): DetailTimeline? {
    val initial = initialSelection ?: now.minusSeconds(7200)
    val today = now.atZone(zone).toLocalDate()
    val opening = TimelineWindow.fixed(today, zone)
    val anchor = initialAnchor ?: if (initial in opening.start..opening.end) today else initial.atZone(zone).toLocalDate()
    if (result.capabilities.continuous) {
        val chunks = TimelineChunks(anchor, zone, now) { repository.timelineChunk(station, zone, it) }
        try {
            val content = chunks.open(initial).await() ?: run { chunks.close(); return null }
            val controller = TimelineController(now, zone, content.window, 0.0, initialSelection = initial,
                reduceMotion = reduceMotion, anchor = anchor, allowOpening = initialSelection == null)
            return DetailTimeline(controller, ContinuousTimeline(controller, chunks, content, mainExecutor),
                repository, station, zone, result.series, content.data.days)
        } catch (e: Throwable) {
            chunks.close()
            throw e
        }
    }
    val preview = result.capabilities == StationCapabilities.TIDE_PREVIEW
    val mode = if (preview) TimelineMode.PREVIEW else TimelineMode.ONLINE
    val fixed = TimelineWindow.fixed(anchor, zone)
    val full = withContext(Dispatchers.IO) {
        if (preview) repository.completeTimelinePreview(result, now)
        else result.onlineTimelineBlock(fixed) ?: repository.load(station.id, TimeSpan(fixed.start, fixed.end),
            PredictionPurpose.DISPLAY).onlineTimelineBlock(fixed)
    } ?: return null
    val window = if (preview) full.previewTimelineWindow(now) ?: return null else fixed
    val controller = TimelineController(now, zone, window, 0.0, mode, initial, reduceMotion, anchor = anchor,
        allowOpening = initialSelection == null)
    return DetailTimeline(controller, null, repository, station, zone, full.series, fixedDays(station, zone, window))
}

private suspend fun fixedDays(station: StationIdentity, zone: ZoneId, window: TimelineWindow) =
    withContext(Dispatchers.Default) { timelineDays(station, zone, window) }

@Composable
internal fun DetailTimelineEffects(timeline: DetailTimeline, station: StationIdentity, thresholdKnots: Double, showSky: Boolean, onlineAvailable: Boolean = false, fetch: (suspend (LocalDate) -> Boolean)? = null) {
    val state = timeline.state
    val series = timeline.series
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(timeline, lifecycle) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (paused && event == Lifecycle.Event.ON_RESUME) {
                paused = false
                timeline.dispatch(TimelineAction.Foreground(Instant.now()))
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(timeline, series, state.coverage, thresholdKnots, showSky) {
        timeline.showSky = showSky
        val stops = withContext(Dispatchers.Default) {
            MagneticTargets.from(state.coverage, series, thresholdKnots, station.latitude, station.longitude, showSky)
        }
        timeline.dispatch(TimelineAction.Targets(stops))
    }
    LaunchedEffect(timeline, state.motion, state.generation, state.viewport, state.awaitingCoverage) {
        if (state.viewport == 0.0 || state.awaitingCoverage) return@LaunchedEffect
        when (state.motion) {
            TimelineMotion.OPENING -> while (timeline.state.motion == TimelineMotion.OPENING)
                withFrameNanos { timeline.dispatch(TimelineAction.Tick) }
            TimelineMotion.RIDE, TimelineMotion.MAGNETIC_SETTLE -> {
                val start = withFrameNanos { it }
                var fraction = 0.0
                while (fraction < 1.0) withFrameNanos {
                    fraction = ((it - start) / 300_000_000.0).coerceIn(0.0, 1.0)
                    val left = 1 - fraction
                    timeline.dispatch(TimelineAction.AnimationFrame(state.generation, 1 - left * left * left))
                }
            }
            TimelineMotion.RESTING -> {
                delay(450)
                timeline.dispatch(TimelineAction.Tick)
                while (!timeline.state.settled) {
                    delay(50)
                    timeline.dispatch(TimelineAction.Tick)
                }
            }
            else -> Unit
        }
    }
    val week = Math.floorDiv(state.selected.atZone(timeline.zone).toLocalDate().toEpochDay() - state.anchor.toEpochDay(), 7L)
    LaunchedEffect(timeline, week, state.busy, state.pendingGeneration, state.anchor) {
        try { timeline.refresh() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A failed neighbor leaves the published strip reachable. */ }
    }
    LaunchedEffect(timeline, state.onlineDayRequested, state.generation, state.motion == TimelineMotion.DRAG, onlineAvailable) {
        val day = state.onlineDayRequested ?: return@LaunchedEffect
        if (state.motion != TimelineMotion.DRAG) try {
            timeline.online(day, state.generation, if (onlineAvailable) fetch else null)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { timeline.dispatch(TimelineAction.RequestFailed(state.generation)) }
    }
}
