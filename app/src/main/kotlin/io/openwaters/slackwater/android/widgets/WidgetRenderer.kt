package io.openwaters.slackwater.android.widgets

import android.content.Context
import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.plotZone
import io.openwaters.slackwater.android.core.prediction.PredictionRepository
import io.openwaters.slackwater.android.core.prediction.PredictionPurpose
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.stations.cardTimeFormatter
import io.openwaters.slackwater.android.stations.drawCurve
import io.openwaters.slackwater.android.stations.kind
import io.openwaters.slackwater.android.stations.EbbAmber
import io.openwaters.slackwater.android.stations.FallingAmber
import io.openwaters.slackwater.android.stations.FloodBlue
import io.openwaters.slackwater.android.stations.CardGoGreen
import io.openwaters.slackwater.android.stations.RisingTeal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal fun widgetSpan(now: Instant, wide: Boolean): TimeSpan {
    val full = cardSpan(now)
    if (wide) return full
    val halfSwing = Duration.between(full.start, now).dividedBy(3)
    return TimeSpan(now.minus(halfSwing), now.plus(halfSwing.multipliedBy(3)))
}

internal suspend fun renderStationWidget(
    context: Context, widgetId: Int, station: StationId?, size: WidgetSize, variants: Int, now: Instant,
    repository: PredictionRepository = AndroidPredictionRepository(context),
    preferences: ReadingPreferences? = null,
): RemoteViews = withContext(Dispatchers.IO) {
    val wide = size.wide
    val views = RemoteViews(context.packageName, if (wide) R.layout.station_widget_wide else R.layout.station_widget)
    val identity = station?.let { id ->
        when (val resolved = BundledStationCatalog(context.assets).resolve(id)) {
            is StationResolution.Known -> resolved.identity
            is StationResolution.Successor -> resolved.target.identity
            is StationResolution.Unknown -> null
        }
    }
    val locale = context.resources.configuration.locales[0]
    val zone = identity?.plotZone() ?: ZoneId.systemDefault()
    val stamp = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdjmz"), locale).withZone(zone)
    views.setTextViewText(R.id.widget_stamp, context.getString(R.string.widget_snapshot_time, stamp.format(now)))
    views.setTextViewText(R.id.widget_name, identity?.name ?: station?.value ?: context.getString(R.string.app_name))
    views.setViewVisibility(R.id.widget_curve, View.GONE)
    views.setViewVisibility(R.id.widget_event, View.GONE)
    if (identity == null) {
        val message = when {
            station != null -> R.string.card_unavailable
            hasNearestChoice(context, widgetId) -> R.string.widget_location_hint
            else -> R.string.query_choose
        }
        views.setTextViewText(R.id.widget_reading, context.getString(message))
        return@withContext views
    }
    val prefs = preferences ?: ReadingPreferencesStore.get(context).preferences.first().getOrDefault(ReadingPreferences())
    val threshold = prefs.effectiveThresholdKn(identity.id)
    val span = widgetSpan(now, wide)
    val result = repository.load(identity.id, span, PredictionPurpose.WIDGET)
    val reading = cardReading(result, now)
    views.setTextViewText(R.id.widget_reading, context.readingLine(reading, prefs, locale, wide))
    if (!wide && reading is CardReading.Tide) {
        val direction = context.getString(if (reading.rising) R.string.card_rising else R.string.card_falling)
        val imperial = prefs.heightUnits(locale) == HeightUnits.IMPERIAL
        views.setContentDescription(R.id.widget_reading, context.getString(R.string.card_spoken_reading,
            formatHeightValue(reading.heightMetres, imperial, locale),
            context.getString(if (imperial) R.string.spoken_feet else R.string.spoken_metres), direction))
    }
    val ready = result.blockAt(now) ?: return@withContext views
    val formatter = cardTimeFormatter(locale, zone)
    val curve = CardCurve.from(result, now, threshold, span)
    val close = curve?.windowClosing(now)
    val event = when (val series = ready.series) {
        is PredictionSeries.Tide -> series.events.firstOrNull { it.time > now }?.let {
            (if (it.kind == ExtremeKind.HIGH) R.string.chart_high else R.string.chart_low) to it.time
        }
        is PredictionSeries.Current -> series.events.firstOrNull { it.time > now && it.kind == io.openwaters.slackwater.CurrentEventKind.SLACK }
            ?.let { R.string.card_slack to it.time }
        is PredictionSeries.Derived -> series.slacks.firstOrNull { it.time > now }?.let { R.string.card_slack to it.time }
    }
    fun eventTime(time: Instant): String = if (time.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate())
        formatter.format(time) else stamp.format(time)
    val caption = if (close != null) context.getString(R.string.widget_event_time, context.getString(R.string.chart_closes), eventTime(close))
        else event?.let { (label, time) -> context.getString(R.string.widget_event_time, context.getString(label), eventTime(time)) }
    if (caption != null) {
        views.setTextViewText(R.id.widget_event, caption)
        views.setViewVisibility(R.id.widget_event, View.VISIBLE)
    }
    if (curve != null && (!curve.schematic || wide)) {
        val widthDp = (size.width - 16).roundToInt().coerceIn(32, 4096)
        val heightDp = (size.height - if (wide) 65 else 85).roundToInt().coerceIn(24, 4096)
        val bitmap = context.graphBitmap(curve, prefs, locale, formatter, ready.approximate, widthDp, heightDp, variants)
        views.setImageViewBitmap(R.id.widget_curve, bitmap)
        views.setViewVisibility(R.id.widget_curve, View.VISIBLE)
    }
    views
}

internal fun CardCurve.windowClosing(now: Instant): Instant? = windows.firstOrNull {
    now in it && it.end > now && it.end < now.plusSeconds(7200) &&
        it.end < end && it.end < samples.last().time
}?.end

private fun Context.readingLine(reading: CardReading, prefs: ReadingPreferences, locale: Locale, wide: Boolean): CharSequence {
    fun marked(text: String, mark: String, color: Color): SpannableString = SpannableString(text).apply {
        val start = text.indexOf(mark)
        if (start >= 0) setSpan(ForegroundColorSpan(color.toArgb()), start, start + mark.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    fun phaseColor(value: CardCurrentState) = when (value) {
        CardCurrentState.SLACK -> CardGoGreen
        CardCurrentState.FLOODING -> FloodBlue
        CardCurrentState.EBBING -> EbbAmber
    }
    fun phase(value: CardCurrentState) = getString(when (value) {
        CardCurrentState.SLACK -> R.string.card_slack
        CardCurrentState.FLOODING -> R.string.card_flooding
        CardCurrentState.EBBING -> R.string.card_ebbing
    })
    return when (reading) {
        is CardReading.Tide -> {
            val imperial = prefs.heightUnits(locale) == HeightUnits.IMPERIAL
            val units = getString(if (imperial) R.string.unit_feet else R.string.unit_metres)
            val height = formatHeightValue(reading.heightMetres, imperial, locale)
            val direction = if (reading.rising) "↗" else "↘"
            val state = if (wide) getString(if (reading.rising) R.string.card_rising else R.string.card_falling) else ""
            val text = listOf(height, units, direction, state).filter(String::isNotEmpty).joinToString(" ")
            marked(text, direction, if (reading.rising) RisingTeal else FallingAmber)
        }
        is CardReading.Current -> {
            val speed = formatSpeedValue(reading.speedKnots, prefs.speedUnit.kind, locale)
            val unit = getString(when (prefs.speedUnit) {
                SpeedUnit.KNOTS -> R.string.unit_knots
                SpeedUnit.KILOMETRES_PER_HOUR -> R.string.unit_kilometres_per_hour
                SpeedUnit.METRES_PER_SECOND -> R.string.unit_metres_per_second
            })
            val value = getString(if (reading.approximate) R.string.card_value_unit_approximate else R.string.card_value_unit, speed, unit)
            val direction = reading.setDegrees?.let { degrees ->
                val arrow = listOf("↑","↗","→","↘","↓","↙","←","↖")[Math.floorMod((degrees / 45).roundToInt(), 8)]
                val set = if (prefs.setFormat == SetFormat.DEGREES) formatSet(degrees, true)
                    else resources.getStringArray(R.array.pdf_compass)[io.openwaters.slackwater.compassIndex(degrees)]
                "$arrow $set"
            } ?: "•"
            val state = phase(reading.state).takeIf { wide || reading.state == CardCurrentState.SLACK }
            val text = listOfNotNull(value, state, direction).joinToString(" ")
            val mark = if (reading.state == CardCurrentState.SLACK) listOfNotNull(state, direction).joinToString(" ") else direction
            marked(text, mark, phaseColor(reading.state))
        }
        is CardReading.Derived -> phase(reading.phase).let { marked(it, it, phaseColor(reading.phase)) }
        CardReading.NotDownloaded -> getString(R.string.card_not_downloaded)
        CardReading.Unavailable -> getString(R.string.card_unavailable)
    }
}

/** All sizes in a responsive update share one bitmap budget. Leave room for its RemoteViews data and stay below
 * the launcher's 1.5-screen ARGB bitmap limit even on small displays. */
internal fun widgetBitmapScale(widthDp: Int, heightDp: Int, density: Float, screenWidth: Int, screenHeight: Int, variants: Int): Float {
    val pixelBudget = (minOf(131_072L, screenWidth.toLong() * screenHeight) / variants).coerceAtLeast(1)
    return minOf(density, sqrt(pixelBudget.toDouble() / (widthDp * heightDp)).toFloat())
}

private fun Context.graphBitmap(
    curve: CardCurve, prefs: ReadingPreferences, locale: Locale, formatter: DateTimeFormatter, approximate: Boolean, widthDp: Int, heightDp: Int, variants: Int,
): Bitmap {
    val metrics = resources.displayMetrics
    val scale = widgetBitmapScale(widthDp, heightDp, metrics.density, metrics.widthPixels, metrics.heightPixels, variants)
    val density = Density(scale, resources.configuration.fontScale)
    val width = (widthDp * scale).toInt().coerceAtLeast(1)
    val height = (heightDp * scale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val direction = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    val measurer = TextMeasurer(createFontFamilyResolver(this), density, direction)
    CanvasDrawScope().draw(density, direction, Canvas(bitmap.asImageBitmap()), Size(width.toFloat(), height.toFloat())) {
        drawCurve(curve, measurer, formatter, approximate, { extreme ->
            when (extreme.kind) {
                CurveExtreme.Kind.TIDE -> formatHeightValue(extreme.value, prefs.heightUnits(locale) == HeightUnits.IMPERIAL, locale)
                CurveExtreme.Kind.CURRENT -> formatSpeedValue(abs(extreme.value), prefs.speedUnit.kind, locale)
                CurveExtreme.Kind.DERIVED -> null
            }
        }, Color.White)
    }
    return bitmap
}
