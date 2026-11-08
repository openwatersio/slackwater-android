package io.openwaters.slackwater.android.core.tour

import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationSeries
import io.openwaters.slackwater.android.core.station.coordinate
import io.openwaters.slackwater.android.core.station.distanceKm
import io.openwaters.slackwater.android.core.station.series
import io.openwaters.slackwater.android.core.timeline.TimelineDay
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The first-run tour's coach marks, in order (list §11). Sky targets and the Moon tile each determine whether their mark is shown. */
enum class TourStep { READ, STARS, MOON, MOON_CARD, STAR }

/**
 * [step] is the mark on screen, null when none is. [station] is the detail the running tour belongs to; any other
 * detail draws no marks. [armed] is a tour waiting for a detail with a timeline to begin it, and [reservedFor] names
 * the only station that may, once a link has replaced the tour's station.
 */
data class TourState(
    val step: TourStep? = null,
    val station: StationId? = null,
    val armed: Boolean = false,
    val reservedFor: StationId? = null,
    val starsAvailable: Boolean = true,
    val moonAvailable: Boolean = true,
)

/**
 * The tour's one shared state. [onSeen] persists that the tour is done; it runs only when a showing tour finishes,
 * which is the one way a tour is marked seen and there is no resume-later state.
 */
class TourCoach(private val onSeen: () -> Unit) {
    private val _state = MutableStateFlow(TourState())
    val state: StateFlow<TourState> = _state.asStateFlow()
    private var armedOnce = false

    /** Once per process, with the stored flag: a tour already seen does not arm. */
    fun arm(seen: Boolean) {
        if (armedOnce) return
        armedOnce = true
        if (!seen) _state.update { it.copy(armed = true) }
    }

    /** Shows the tour again whether or not it was seen, from the next detail with a timeline. */
    fun replay() {
        _state.value = TourState(armed = true)
    }

    /** Ends any tour, armed or showing, without marking it seen. */
    fun standDown() {
        _state.value = TourState()
    }

    /** A detail with a usable timeline claims the armed tour; after a link, only the linked station may. */
    fun begin(station: StationId, starsAvailable: Boolean, moonAvailable: Boolean) = _state.update {
        if (!it.armed || (it.reservedFor != null && it.reservedFor != station)) it
        else TourState(TourStep.READ, station, starsAvailable = starsAvailable, moonAvailable = moonAvailable)
    }

    /**
     * A link replaced the tour's station: re-arm for the linked station alone. The replaced detail can still be
     * composed, and the reservation keeps it from claiming the tour back.
     */
    fun handOff(to: StationId) = _state.update {
        if (!(it.armed || it.step != null) || it.station == to) it
        else TourState(armed = true, reservedFor = to)
    }

    fun advance(moonCardAvailable: Boolean) {
        val step = _state.value.step ?: return
        val next = next(after = step, moonCardAvailable = moonCardAvailable)
        if (next == null) finish() else _state.update { it.copy(step = next) }
    }

    /** Skip, Done, or leaving the detail. A stray call with no mark showing does nothing, so it never ends a tour early. */
    fun finish() {
        if (_state.value.step == null) return
        _state.value = TourState()
        onSeen()
    }

    private fun next(after: TourStep, moonCardAvailable: Boolean): TourStep? {
        val state = _state.value
        return TourStep.entries.drop(after.ordinal + 1).firstOrNull {
            when (it) {
                TourStep.STARS -> state.starsAvailable
                TourStep.MOON -> state.moonAvailable
                TourStep.MOON_CARD -> moonCardAvailable
                else -> true
            }
        }
    }
}

/** Friday Harbor, where the tour opens with no fix or no tide place in range. */
val TOUR_FALLBACK_STATION = StationId("noaa/9449880")
const val TOUR_STATION_RANGE_KM = 150.0

/** The nearest bundled or Canadian tide place within [TOUR_STATION_RANGE_KM] of [fix], else Friday Harbor. */
fun tourStation(fix: Coordinate?, stations: Collection<StationIdentity>): StationId {
    if (fix == null) return TOUR_FALLBACK_STATION
    val nearest = stations.asSequence().filter { it.series == StationSeries.Tide }
        .map { it to distanceKm(fix, it.coordinate) }
        .minByOrNull { it.second } ?: return TOUR_FALLBACK_STATION
    return if (nearest.second <= TOUR_STATION_RANGE_KM) nearest.first.id else TOUR_FALLBACK_STATION
}

/** An hour past a sunset, late enough that the sky is dark and the stars are worth pointing at. */
private val DARK_MARGIN: Duration = Duration.ofHours(1)

/** The stars step's target: the first hour-past-sunset still ahead of [now], inside [coverage]; null when none is. */
fun tourStarsTime(days: List<TimelineDay>, now: Instant, coverage: ClosedRange<Instant>): Instant? =
    days.mapNotNull { it.sunset?.plus(DARK_MARGIN) }.sorted().firstOrNull { it > now && it in coverage }

/**
 * The moon step's target: the middle of the first span with the moon up and the sun down, ahead of [now] and inside
 * [coverage]. The only kind of moment where "that is the real moon" is worth showing; null when none is.
 */
fun tourMoonTime(days: List<TimelineDay>, now: Instant, coverage: ClosedRange<Instant>): Instant? {
    val sunrises = days.mapNotNull { it.sunrise }.sorted()
    val moonsets = days.mapNotNull { it.moonset }.sorted()
    val dark = days.mapNotNull { it.sunset }.sorted().mapNotNull { set -> sunrises.firstOrNull { it > set }?.let { set to it } }
    val moonUp = days.mapNotNull { it.moonrise }.sorted().mapNotNull { rise -> moonsets.firstOrNull { it > rise }?.let { rise to it } }
    return dark.flatMap { night ->
        moonUp.mapNotNull { up ->
            val from = maxOf(night.first, up.first)
            val to = minOf(night.second, up.second)
            if (from < to) from.plus(Duration.between(from, to).dividedBy(2)) else null
        }
    }.sorted().firstOrNull { it > now && it in coverage }
}
