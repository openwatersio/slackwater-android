package io.openwaters.slackwater.android.core.timeline

import io.openwaters.slackwater.android.core.prediction.PredictionRepository
import io.openwaters.slackwater.android.core.prediction.PredictionPurpose
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.StationCapabilities
import java.time.Instant

/** An online chart needs one block covering the entire requested window, including its look-back. */
fun PredictionResult.onlineTimelineBlock(window: TimelineWindow): PredictionResult.Ready? {
    val blocks = when (this) {
        is PredictionResult.Ready -> listOf(this)
        is PredictionResult.Disjoint -> blocks
        is PredictionResult.Unavailable -> return null
    }
    return blocks.firstOrNull {
        it.capabilities == StationCapabilities.ONLINE_CURRENT &&
            it.coverage.start <= window.start && it.coverage.end >= window.end
    }
}

/** Preview input is the validated saved tide series loaded across its full sample span. */
fun PredictionResult.previewTimelineWindow(sessionNow: Instant): TimelineWindow? {
    if (this !is PredictionResult.Ready || capabilities != StationCapabilities.TIDE_PREVIEW ||
        previewSampleSpan != coverage || sessionNow !in coverage || coverage.start == coverage.end) return null
    return TimelineWindow(coverage.start, coverage.end)
}

/** Expand a repository slice before handing the preview to a fixed-window controller. */
suspend fun PredictionRepository.completeTimelinePreview(
    slice: PredictionResult.Ready,
    sessionNow: Instant,
): PredictionResult.Ready? {
    val saved = slice.previewSampleSpan ?: return null
    if (slice.capabilities != StationCapabilities.TIDE_PREVIEW || sessionNow !in saved) return null
    if (slice.previewTimelineWindow(sessionNow) != null) return slice
    val loaded = load(slice.station, saved, PredictionPurpose.DISPLAY) as? PredictionResult.Ready ?: return null
    return loaded.takeIf { it.previewTimelineWindow(sessionNow) != null }
}
