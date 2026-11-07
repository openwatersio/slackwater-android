package io.openwaters.slackwater.android.stations

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import io.openwaters.slackwater.android.core.station.CardCurve
import io.openwaters.slackwater.android.core.station.CardCurveStyle
import io.openwaters.slackwater.android.core.station.CurveExtreme
import io.openwaters.slackwater.android.core.station.CurveRun
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// List §6.4 colours: a tide line in sky blue, a current in yellow outside its slack windows and blue inside, slack
// in go green, tide highs teal and lows amber.
private val GraphLine = Color(0xFF38BDF8)
private val CurrentLine = Color(0xFFF5C96B)
private val GoGreen = Color(0xFF88B868)
private val GraphHigh = Color(0xFF2DD4BF)
private val GraphLow = Color(0xFFFBBF24)
private val Uncertain = Color(0xFFEF6F4A)
private const val PAST_LINE_OPACITY = 0.35f
private const val FILL_OPACITY = 0.18f
private const val LINE_WIDTH_DP = 2.5f
private const val RUN_WIDTH_DP = 3.5f
private const val NOW_DOT_DP = 7f
private const val TIME_FONT_SP = 10f
private const val VALUE_FONT_SP = 14f
private const val GLYPH_FONT_SP = 12f
/** The axis row's centre above the canvas bottom, and the clear strip below the plot it sits in. */
private const val TIME_BASELINE_DP = 10f
private const val TIME_LABEL_SPACE_DP = 6f

/** The station's clock in the reader's locale, as the card's times print. */
fun cardTimeFormatter(locale: Locale, zone: ZoneId): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "jm"), locale).withZone(zone)

/**
 * Draws [curve] behind a card's text: the line with its past faded, tide turns as dots with hanging readings, a
 * current's slack windows and slack strokes, a derived gate's slack dots, thinned axis times and the now dot.
 * [valueText] formats an extreme's printed number; null prints only its pointer.
 */
@Composable
fun CardCurveCanvas(
    curve: CardCurve,
    zone: ZoneId,
    approximate: Boolean,
    valueText: (CurveExtreme) -> String?,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, zone) { cardTimeFormatter(locale, zone) }
    Canvas(modifier.fillMaxSize()) {
        drawCurve(curve, measurer, formatter, approximate, valueText, ink)
    }
}

internal fun DrawScope.drawCurve(
    curve: CardCurve,
    measurer: TextMeasurer,
    formatter: DateTimeFormatter,
    approximate: Boolean,
    valueText: (CurveExtreme) -> String?,
    ink: Color,
) {
    if (curve.samples.size < 2) return
    val dp = density
    val spanMillis = Duration.between(curve.start, curve.end).toMillis().toFloat()
    val widthPx = size.width
    val dpPerHour = widthPx / dp / (spanMillis / 3_600_000f)
    val plotHeight = (size.height - TIME_LABEL_SPACE_DP * dp).coerceAtLeast(0f)
    val lo = curve.domain.start
    val hi = curve.domain.endInclusive
    fun x(t: Instant) = Duration.between(curve.start, t).toMillis() / spanMillis * widthPx
    fun y(v: Double) = (plotHeight * (1 - (v - lo) / (hi - lo))).toFloat()
    val nowX = x(curve.now)

    fun pathOver(from: Instant, to: Instant): Path = Path().apply {
        moveTo(x(from), y(curve.valueAt(from)))
        for (s in curve.samples) if (s.time > from && s.time < to) lineTo(x(s.time), y(s.value))
        lineTo(x(to), y(curve.valueAt(to)))
    }
    val line = pathOver(curve.samples.first().time, curve.samples.last().time)

    // The past is context: everything left of now is drawn at reduced strength.
    fun split(draw: DrawScope.(past: Boolean) -> Unit) {
        clipRect(right = nowX) { draw(true) }
        clipRect(left = nowX) { draw(false) }
    }

    // The area under the curve, closed to the zero line for a current and to the bottom for a tide.
    val baseY = if (curve.includesZero) y(0.0) else size.height
    val area = Path().apply {
        addPath(line)
        lineTo(x(curve.samples.last().time), baseY)
        lineTo(x(curve.samples.first().time), baseY)
        close()
    }
    val lineColor = if (curve.includesZero && !curve.schematic) CurrentLine else GraphLine
    drawPath(area, lineColor.copy(alpha = FILL_OPACITY))
    if (curve.includesZero || (lo < 0 && 0 < hi)) {
        drawLine(ink.copy(alpha = 0.35f), Offset(0f, y(0.0)), Offset(widthPx, y(0.0)), strokeWidth = dp,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dp, 3 * dp)))
    }
    split { past -> drawPath(line, lineColor.copy(alpha = if (past) PAST_LINE_OPACITY else 1f), style = Stroke(LINE_WIDTH_DP * dp, cap = StrokeCap.Round)) }

    // Slack windows as blue runs over the yellow line, and slack as a short green stroke centred on its zero.
    if (curve.includesZero && !curve.schematic) {
        val windows = curve.drawnWindows(dpPerHour)
        val halfStroke = Duration.ofMillis((CardCurveStyle.SHORTEST_SLACK_STROKE / dpPerHour / 2 * 3_600_000).toLong())
        val strokes = curve.slacks.map { CurveRun(it.minus(halfStroke), it.plus(halfStroke)) }
        split { past ->
            val alpha = if (past) PAST_LINE_OPACITY else 1f
            for (run in windows) drawPath(pathOver(run.start, run.end), GraphLine.copy(alpha = alpha), style = Stroke(RUN_WIDTH_DP * dp, cap = StrokeCap.Round))
            for (run in strokes) drawPath(pathOver(run.start, run.end), GoGreen.copy(alpha = alpha), style = Stroke(RUN_WIDTH_DP * dp, cap = StrokeCap.Round))
        }
    }

    // Axis times, thinned so they clear both edges and each other; a current's are green.
    val timeStyle = TextStyle(fontSize = TIME_FONT_SP.sp, fontWeight = FontWeight.Medium)
    // Thinned by the widest time as it will actually print, so large text never overlaps or clips.
    val candidates = curve.axisMoments(dpPerHour)
    val widest = candidates.maxOfOrNull { measurer.measure(formatter.format(it), timeStyle).size.width }?.let { it / dp + 4f } ?: 0f
    // The same edge the thinning keeps, so a window whose opening cannot print names its slack instead.
    val edge = maxOf(CardCurveStyle.AXIS_EDGE_MARGIN, widest / 2)
    val printableFrom = curve.start.plusMillis((edge * dp / widthPx * spanMillis).toLong())
    val moments = curve.axisMoments(dpPerHour, printableFrom)
    val kept = CardCurve.thinAxisMoments(moments, { x(it) / dp }, widthPx / dp, minGap = widest)
    val timeInk = mutableListOf<Rect>()
    for (time in kept) {
        val fade = if (time < curve.now) CardCurveStyle.PAST_FADE else 1f
        val color = (if (curve.includesZero) GoGreen else ink).copy(alpha = fade)
        val layout = measurer.measure(formatter.format(time), timeStyle.copy(color = color))
        val centre = Offset(x(time), size.height - TIME_BASELINE_DP * dp)
        val topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f)
        drawText(layout, topLeft = topLeft)
        timeInk += Rect(topLeft, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
    }

    // Extremes: a tide turn gets a dot; every extreme gets a hanging reading unless it would leave the card or
    // land on an axis time.
    val valueStyle = TextStyle(fontSize = VALUE_FONT_SP.sp, fontWeight = FontWeight.SemiBold)
    val glyphStyle = TextStyle(fontSize = GLYPH_FONT_SP.sp, fontWeight = FontWeight.Bold)
    for (extreme in curve.extremes) {
        val fade = if (extreme.time < curve.now) CardCurveStyle.PAST_FADE else 1f
        val tint = (if (extreme.high) GraphHigh else GraphLow).copy(alpha = fade)
        val readingInk = (if (approximate) Uncertain else ink).copy(alpha = fade)
        val dot = Offset(x(extreme.time), y(extreme.value))
        if (extreme.kind == CurveExtreme.Kind.TIDE) drawCircle(tint, CardCurveStyle.DOT_RADIUS * dp, dot)
        val value = valueText(extreme)?.let { measurer.measure(it, valueStyle.copy(color = readingInk)) }
        // A reading that would reach past either edge (a wide value at large text) keeps only its dot and time.
        val margin = maxOf(CardCurveStyle.LABEL_EDGE_MARGIN * dp, (value?.size?.width ?: 0) / 2f + 4 * dp)
        if (dot.x < margin || dot.x > widthPx - margin) continue
        val toward = CardCurve.hangToward(dot.y / dp, extreme.high, plotHeight / dp)
        val glyphText = when (extreme.kind) {
            CurveExtreme.Kind.TIDE -> if (extreme.high) "⤒" else "⤓"
            CurveExtreme.Kind.CURRENT -> if (extreme.setDegrees != null) "↑" else null
            CurveExtreme.Kind.DERIVED -> if (extreme.flood == true) "→" else "←"
        }
        val glyphColor = if (extreme.kind == CurveExtreme.Kind.TIDE) tint else readingInk
        val glyph = glyphText?.let { measurer.measure(it, glyphStyle.copy(color = glyphColor)) }
        val hang = dot.y + toward * CardCurveStyle.HANG_OFFSET * dp
        val glyphCentre = Offset(dot.x, hang - toward * CardCurveStyle.HANG_GLYPH_GAP * dp)
        val valueCentre = Offset(dot.x, hang + toward * CardCurveStyle.HANG_VALUE_GAP * dp)
        // A reading hanging down onto a printed time is hidden rather than drawn over it.
        if (toward > 0) {
            val readingRect = listOfNotNull(
                glyph?.let { Rect(glyphCentre - Offset(it.size.width / 2f, it.size.height / 2f), Size(it.size.width.toFloat(), it.size.height.toFloat())) },
                value?.let { Rect(valueCentre - Offset(it.size.width / 2f, it.size.height / 2f), Size(it.size.width.toFloat(), it.size.height.toFloat())) },
            ).reduceOrNull { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
            if (readingRect != null && timeInk.any { it.overlaps(readingRect) }) continue
        }
        glyph?.let {
            val topLeft = glyphCentre - Offset(it.size.width / 2f, it.size.height / 2f)
            val rotation = if (extreme.kind == CurveExtreme.Kind.CURRENT) extreme.setDegrees?.toFloat() ?: 0f else 0f
            rotate(rotation, pivot = glyphCentre) { drawText(it, topLeft = topLeft) }
        }
        value?.let { drawText(it, topLeft = valueCentre - Offset(it.size.width / 2f, it.size.height / 2f)) }
    }

    // A schematic shape has no speed to run a slack along, so its slack is a green dot on the zero line.
    if (curve.schematic) for (slack in curve.slacks) drawCircle(GoGreen, CardCurveStyle.DOT_RADIUS * dp, Offset(x(slack), y(0.0)))

    // The now dot rides the curve, so it is left out where the drawn line does not reach now.
    if (curve.now >= curve.samples.first().time && curve.now <= curve.samples.last().time) {
        val nowAt = Offset(nowX, y(curve.valueAt(curve.now)))
        drawCircle(ink, NOW_DOT_DP / 2 * dp + dp, nowAt)
        drawCircle(lineColor, NOW_DOT_DP / 2 * dp, nowAt)
    }
}

/**
 * The data-free placeholder a waiting Canadian card shows at its full height, so the list does not jump when the
 * predictions arrive: a wave and three capsules.
 */
@Composable
fun CardCurveSkeleton(ink: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val dp = density
        val tone = ink.copy(alpha = 0.12f)
        val mid = (size.height - TIME_LABEL_SPACE_DP * dp) * 0.55f
        val wave = Path().apply {
            moveTo(0f, mid)
            val steps = 48
            for (i in 1..steps) {
                val f = i / steps.toFloat()
                lineTo(f * size.width, mid - kotlin.math.sin(f * 4 * Math.PI).toFloat() * size.height * 0.18f)
            }
        }
        drawPath(wave, tone, style = Stroke(LINE_WIDTH_DP * dp, cap = StrokeCap.Round))
        val capsule = Size(36 * dp, 8 * dp)
        for (i in 0 until 3) {
            val cx = size.width * (0.25f + 0.25f * i)
            drawRoundRect(tone, Offset(cx - capsule.width / 2, size.height - TIME_BASELINE_DP * dp - capsule.height / 2), capsule, CornerRadius(capsule.height / 2))
        }
    }
}
