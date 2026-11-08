package io.openwaters.slackwater.android.map

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.slackWindowAround
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.CardCurve
import io.openwaters.slackwater.android.core.station.CurveRun
import io.openwaters.slackwater.android.core.station.CurveSample
import io.openwaters.slackwater.android.stations.CardCurveCanvas
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

private val points = (0..120).map { i ->
    val phase = i / 60.0 - 1
    CurrentPoint(Instant.EPOCH.plusSeconds(i * 180L), 10 * phase * phase * phase)
}
private val samples = points.map { CurveSample(it.time, it.speedKnots) }
private val slack = points[60].time

internal fun exampleSlackCurve(threshold: Double): CardCurve {
    val window = slackWindowAround(points, slack, threshold)
    return CardCurve(points.first().time, points.last().time, Instant.EPOCH,
        samples, emptyList(), includesZero = true, slacks = listOf(slack),
        windows = listOfNotNull(window?.let { CurveRun(it.start, it.end) }))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlackWindowPreview(threshold: Double) {
    val curve = remember(threshold) { exampleSlackCurve(threshold) }
    val window = curve.windows.singleOrNull()
    val width = window?.let { Duration.between(it.start, it.end) } ?: Duration.ZERO
    val minutes = (width.toMillis() / 60_000.0).roundToInt()
    val locale = LocalConfiguration.current.locales[0]
    val duration = if (window == null) stringResource(R.string.reminder_status_off) else remember(minutes, locale) {
        val measures = buildList {
            if (minutes >= 60) add(Measure(minutes / 60, MeasureUnit.HOUR))
            if (minutes % 60 > 0 || isEmpty()) add(Measure(minutes % 60, MeasureUnit.MINUTE))
        }
        MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(*measures.toTypedArray())
    }
    val label = stringResource(R.string.settings_example_window)
    val fraction by animateFloatAsState(width.toMillis().toFloat() / Duration.between(curve.start, curve.end).toMillis(),
        animationSpec = tween(200), label = "Window width")
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label; stateDescription = duration }) {
        Box(Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .background(Color(0xFF38BDF8).copy(alpha = 0.12f), RoundedCornerShape(4.dp)))
            CardCurveCanvas(curve, ZoneOffset.UTC, approximate = false, valueText = { null },
                ink = MaterialTheme.colorScheme.onSurface, showAnnotations = false)
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_example))
            Text(duration)
        }
    }
}
