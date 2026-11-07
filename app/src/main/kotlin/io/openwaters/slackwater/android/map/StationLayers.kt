package io.openwaters.slackwater.android.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationKind
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val PINS = "slackwater-stations"
private const val CAPTIONS = "slackwater-station-captions"
private const val FIX = "slackwater-location"
private const val INK = "#152256"
private const val NEUTRAL = "#5888a8"
internal val stationHitLayers = arrayOf("station-rings", "station-dots", "station-arrows", "station-gauges", "station-names", "station-readings", "station-vectors")

private fun pinColor(state: String) = when (state) {
    "rising" -> "#2dd4bf"
    "falling" -> "#fbbf24"
    "flood" -> "#4a9fd8"
    "ebb" -> "#e8a33d"
    "slack" -> "#88b868"
    else -> if (state.startsWith("#")) state else NEUTRAL
}

private fun size(base: Double) = interpolate(exponential(1.7), zoom(), stop(6, base * 0.4), stop(12.5, base * 1.8))

internal class StationLayers(private val style: Style, fieldsEnabled: Boolean = true) {
    val fields: CurrentFieldLayers?
    private val pins = GeoJsonSource(PINS, FeatureCollection.fromFeatures(emptyList()))
    private val location = GeoJsonSource(FIX, FeatureCollection.fromFeatures(emptyList()))

    // Glyph downloads can block an entire source tile, even its circles and local icons.
    private val captions = if (org.json.JSONObject(style.json).optString("glyphs").isNotBlank()) {
        GeoJsonSource(CAPTIONS, FeatureCollection.fromFeatures(emptyList()))
    } else null

    init {
        style.addImage("slackwater-arrow", arrowDistanceField(), true)
        fields = if (fieldsEnabled) CurrentFieldLayers(style) else null
        style.addSource(pins)
        style.addSource(location)
        style.addLayer(CircleLayer("station-selection", PINS).withFilter(has("selected")).withProperties(
            circleRadius(size(17.0)), circleColor(Color.WHITE), circleOpacity(0.18f)))
        style.addLayer(CircleLayer("station-rings", PINS).withFilter(eq(get("form"), literal("ring"))).withProperties(
            circleRadius(size(4.0)), circleColor(Color.TRANSPARENT), circleStrokeColor(NEUTRAL), circleStrokeWidth(size(1.6))))
        style.addLayer(CircleLayer("station-dots", PINS).withFilter(eq(get("form"), literal("dot"))).withProperties(
            circleRadius(size(4.0)), circleColor(toColor(get("color"))), circleStrokeColor(INK), circleStrokeWidth(size(1.5))))

        style.addLayer(SymbolLayer("station-arrows", PINS).withFilter(has("bearing")).withProperties(
            iconImage("slackwater-arrow"), iconRotate(get("bearing")), iconRotationAlignment("map"),
            iconAllowOverlap(true), iconSize(size(1.0)), iconColor(toColor(get("color"))),
            iconHaloColor(INK), iconHaloWidth(1.2f)))
        if (fieldsEnabled) {
            style.addLayer(SymbolLayer("station-vectors", "current-fill-patches").withFilter(has("id")).withProperties(
                iconImage("slackwater-arrow"), iconRotate(get("bearing")), iconRotationAlignment("map"),
                iconPitchAlignment("map"), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(size(0.75)), iconOffset(arrayOf(0f, -15f)), iconColor("#ffffff"),
                iconHaloColor(INK), iconHaloWidth(1.2f)).also { it.minZoom = 9f })
        }
        for (trend in listOf("rising", "falling")) for (bucket in 0..8) {
            style.addImage("station-$trend-$bucket", gaugeImage(trend, bucket))
        }
        style.addLayer(SymbolLayer("station-gauges", PINS).withFilter(has("gauge")).withProperties(
            iconImage(get("gauge")), iconAllowOverlap(true), iconSize(size(1.0))))
        fun caption(id: String, field: String, min: Float, anchor: String, offset: Float) =
            SymbolLayer(id, CAPTIONS).withFilter(has(field)).withProperties(
                textField(get(field)), textFont(arrayOf("Noto Sans Regular")), textSize(12f),
                textColor(Color.WHITE), textHaloColor(INK), textHaloWidth(1f),
                textAnchor(anchor), textOffset(arrayOf(0f, offset))).also { it.minZoom = min }
        if (captions != null) {
            style.addSource(captions)
            style.addLayer(caption("station-names", "name", 10.5f, "top", 1.1f))
            style.addLayer(caption("station-readings", "reading", 9.5f, "bottom", -1.1f))
        }
        style.addLayer(CircleLayer("user-fix", FIX).withProperties(circleColor("#3085ff"),
            circleRadius(6f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)))
    }

    fun showCurrentStations(show: Boolean) {
        style.getLayer("station-vectors")?.setProperties(visibility(if (show) "visible" else "none"))
    }

    fun update(stations: List<StationMapPin>, selected: StationId?) {
        val features = stations.map { pin ->
            val row = pin.station
            Feature.fromGeometry(Point.fromLngLat(row.longitude, row.latitude)).apply {
                addStringProperty("id", row.id.value)
                addStringProperty("name", row.name)
                addStringProperty("color", pinColor(pin.state.state))
                if (row.id == selected) addBooleanProperty("selected", true)
                val form = when {
                    row.kind == StationKind.Restricted -> "ring"
                    pin.state.bearing != null -> "arrow"
                    pin.state.gauge != null -> "gauge"
                    else -> "dot"
                }
                addStringProperty("form", form)
                if (form == "arrow") addNumberProperty("bearing", pin.state.bearing)
                if (form == "gauge") addStringProperty("gauge", "station-${pin.state.state}-${pin.state.gauge}")
                pin.state.reading?.let { addStringProperty("reading", it) }
            }
        }
        val collection = FeatureCollection.fromFeatures(features)
        pins.setGeoJson(collection)
        captions?.setGeoJson(collection)
    }

    fun updateFix(fix: Coordinate?) {
        val features = fix?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }.orEmpty()
        location.setGeoJson(FeatureCollection.fromFeatures(features))
    }
}

// MapLibre tints SDF images per feature. One arrow serves every speed on the continuous ramp.
private fun arrowDistanceField(): Bitmap {
    fun distance(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(x - ax - t * dx, y - ay - t * dy)
    }
    return Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        density = 160
        for (y in 0 until height) for (x in 0 until width) {
            val px = x + 0.5
            val py = y + 0.5
            val d = minOf(distance(px, py, 16.0, 7.0, 16.0, 25.0),
                distance(px, py, 16.0, 7.0, 10.0, 14.0), distance(px, py, 16.0, 7.0, 22.0, 14.0))
            val alpha = (192 + (1.5 - d) * 32).roundToInt().coerceIn(0, 255)
            setPixel(x, y, Color.argb(alpha, 255, 255, 255))
        }
    }
}

private fun gaugeImage(trend: String, bucket: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(24, 48, Bitmap.Config.ARGB_8888)
    bitmap.density = 320
    val canvas = Canvas(bitmap)
    canvas.scale(2f, 2f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rising = trend == "rising"
    val top = if (rising) 7f else 2f
    paint.color = Color.parseColor(INK)
    canvas.drawRoundRect(2f, top - 1, 10f, top + 16, 2f, 2f, paint)
    paint.color = Color.parseColor(pinColor(trend))
    canvas.drawRoundRect(3.5f, top + 15 - maxOf(2f, 15f * bucket / 8), 8.5f, top + 15, 1f, 1f, paint)
    val base = if (rising) 5f else 19f
    canvas.drawPath(Path().apply {
        moveTo(2.5f, base); lineTo(9.5f, base); lineTo(6f, base + if (rising) -4f else 4f); close()
    }, paint)
    return bitmap
}
