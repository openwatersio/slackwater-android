package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant

data class TimeSpan(val start: Instant, val end: Instant) {
    init { require(start <= end) }
    operator fun contains(time: Instant) = time >= start && time <= end
}

enum class PredictionPurpose { DISPLAY, WIDGET, ALERT, CALENDAR, PRINT, QUERY }

interface PredictionRepository {
    suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult
}

sealed interface PredictionResult {
    enum class Reason { UNKNOWN_STATION, MISSING_DOWNLOAD, INVALID_MODEL, UNSUPPORTED_PURPOSE, OUTSIDE_COVERAGE }
    data class Unavailable(val reason: Reason) : PredictionResult
    /** Each block has its own coverage and bearings. Consumers must select a covered block before reading. */
    data class Disjoint(val blocks: List<Ready>) : PredictionResult
    data class Ready(
        val station: StationId,
        val coverage: TimeSpan,
        val source: String,
        val capabilities: StationCapabilities,
        val series: PredictionSeries,
        val slackErrorBoundMinutes: Int? = null,
        val successor: io.openwaters.slackwater.android.core.station.StationResolution.Successor? = null,
        /** Complete validated sample interval, even when this result contains only a preview slice. */
        val previewSampleSpan: TimeSpan? = null,
        val chartModel: ChartModel? = null,
    ) : PredictionResult
}

sealed interface PredictionSeries {
    data class Tide(val points: List<TidePoint>, val rates: List<TideRatePoint>, val events: List<TideExtreme>) : PredictionSeries
    data class Current(
        val points: List<CurrentPoint>, val events: List<CurrentEvent>,
        val floodBearing: Double?, val ebbBearing: Double?,
        val hasCrossAxis: Boolean = false,
    ) : PredictionSeries
    data class DerivedSlack(val time: Instant, val referenceTurn: ExtremeKind)
    data class Derived(val slacks: List<DerivedSlack>) : PredictionSeries
}
