package io.openwaters.slackwater.android.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.openwaters.slackwater.android.core.map.FillCell
import io.openwaters.slackwater.android.core.map.currentSpeedColor
import io.openwaters.slackwater.android.core.map.triangleContains
import io.openwaters.slackwater.android.core.station.Coordinate
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal data class CurrentMapFields(
    val fill: List<FillCell>,
    val patches: List<FillCell>,
    val dodd: Pair<Coordinate, Double>? = null,
)

internal class CurrentFieldFeatures(val fill: FeatureCollection, val patches: FeatureCollection)

@Composable
internal fun rememberCurrentFieldFeatures(
    at: Instant,
    revision: Long,
    evaluate: suspend (Instant) -> CurrentFieldFeatures,
): CurrentFieldFeatures? {
    val features by produceState<CurrentFieldFeatures?>(null, Math.floorDiv(at.epochSecond, 60L), revision) {
        value = try {
            withContext(Dispatchers.Default) { evaluate(at) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("CurrentFieldLayers", "Could not evaluate current fields", error)
            null
        }
    }
    return features
}

private fun Coordinate.point() = Point.fromLngLat(longitude, latitude)

private fun direction(center: Coordinate, bearing: Double) =
    Feature.fromGeometry(center.point()).apply { addNumberProperty("bearing", bearing) }

internal fun CurrentMapFields.features(): CurrentFieldFeatures {
    fun features(cells: List<FillCell>, exclusions: List<FillCell>, stationDirection: Pair<Coordinate, Double>? = null): FeatureCollection {
        val result = ArrayList<Feature>(cells.size * 2 + 1)
        for (cell in cells) {
            val vertices = cell.polygon
            val ring = vertices.map { it.point() }
            result.add(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring + ring.first()))).apply {
                addNumberProperty("kn", cell.speedKn)
                addStringProperty("colour", currentSpeedColor(cell.speedKn))
            })
            if (cell.speedKn == 0.0) continue
            val center = Coordinate(vertices.sumOf { it.latitude } / 3, vertices.sumOf { it.longitude } / 3)
            if (exclusions.none { triangleContains(center, it.polygon) }) {
                result.add(direction(center, cell.bearingDeg))
            }
        }
        stationDirection?.let { (center, bearing) ->
            result.add(direction(center, bearing).apply { addStringProperty("id", "chs-dodd-narrows") })
        }
        return FeatureCollection.fromFeatures(result)
    }
    return CurrentFieldFeatures(features(fill, patches), features(patches, emptyList(), dodd))
}

internal class CurrentFieldLayers(style: Style) {
    private fun source(id: String) = GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()),
        GeoJsonOptions().withTolerance(0f))
    private val fill = source("current-fill")
    private val patch = source("current-fill-patches")

    init {
        style.addSource(fill)
        style.addSource(patch)
        for (source in listOf(fill, patch)) {
            style.addLayer(FillLayer(source.id, source.id).withFilter(has("kn")).withProperties(
                fillColor(toColor(get("colour"))), fillAntialias(false),
                fillOpacity(interpolate(linear(), get("kn"), stop(0.5, 0.18), stop(6, 0.65)))))
        }
        for ((source, layer) in listOf(fill to "current-directions", patch to "current-directions-patches")) {
            style.addLayer(SymbolLayer(layer, source.id).withFilter(all(has("bearing"), not(has("id")))).withProperties(
                iconImage("slackwater-arrow"), iconSize(0.65f), iconRotate(get("bearing")),
                iconRotationAlignment("map"), iconPitchAlignment("map"), iconAllowOverlap(false),
                iconIgnorePlacement(false), iconPadding(8f), iconColor("#152256")).also { it.minZoom = 9f })
        }
    }

    fun update(features: CurrentFieldFeatures?) {
        val empty = FeatureCollection.fromFeatures(emptyList())
        fill.setGeoJson(features?.fill ?: empty)
        patch.setGeoJson(features?.patches ?: empty)
    }
}
