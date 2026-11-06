package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.*
import java.time.Instant

fun PredictionSeries.Current.readingAt(time: Instant): CurrentReading? =
    readCurrent(time, points, events, floodBearing, ebbBearing)

fun PredictionSeries.Current.pinSpeedKnots(time: Instant): Double? = points.speedAt(time)
