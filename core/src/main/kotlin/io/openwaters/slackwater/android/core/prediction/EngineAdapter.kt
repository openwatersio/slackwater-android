package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.*

/** Tide offsets in TCDB are minutes. The generated currents.json offsets are already seconds. */
object EngineAdapter {
    fun subordinateTide(reference: Station, highMinutes: Int, lowMinutes: Int, height: HeightOffset) =
        SubordinateTideStation(reference, highMinutes * 60.0, lowMinutes * 60.0, height)

    fun tide(station: Station, span: TimeSpan, stepSeconds: Double = 600.0): PredictionSeries.Tide =
        PredictionSeries.Tide(station.heights(span.start, span.end, stepSeconds),
            station.rates(span.start, span.end, stepSeconds), station.extremes(span.start, span.end).filter { it.time in span })

    fun tide(station: SubordinateTideStation, span: TimeSpan, stepSeconds: Double = 600.0): PredictionSeries.Tide =
        PredictionSeries.Tide(station.heights(span.start, span.end, stepSeconds),
            station.rates(span.start, span.end, stepSeconds), station.extremes(span.start, span.end))

    fun current(station: CurrentStation, span: TimeSpan, stepSeconds: Double = if (span.start == span.end) 1.0 else 600.0): PredictionSeries.Current =
        PredictionSeries.Current(station.speeds(span.start, span.end, stepSeconds), station.events(span.start, span.end),
            station.floodDirectionDegrees, station.ebbDirectionDegrees)

    fun current(station: SubordinateCurrentStation, span: TimeSpan, stepSeconds: Double = if (span.start == span.end) 1.0 else 600.0): PredictionSeries.Current =
        PredictionSeries.Current(station.speeds(span.start, span.end, stepSeconds), station.events(span.start, span.end),
            station.floodDirectionDegrees, station.ebbDirectionDegrees)
}
