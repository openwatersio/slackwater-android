package io.openwaters.slackwater.android.map

import io.openwaters.slackwater.android.core.map.FillCell
import io.openwaters.slackwater.android.core.station.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class CurrentFieldFeaturesTest {
    @Test fun preservesCertifiedRingsAndSuppressesBackdropDirectionInsidePatch() {
        val vertices = listOf(Coordinate(48.0, -123.0), Coordinate(48.0, -122.9), Coordinate(48.1, -123.0))
        val backdrop = FillCell(vertices, 1.0, 180.0)
        val patch = FillCell(vertices, 8.0, 355.0)
        val dodd = Coordinate(49.13, -123.81) to 155.0
        val fields = CurrentMapFields(listOf(backdrop), listOf(patch), dodd).features()
        val fillFeatures = fields.fill.features()!!
        assertEquals(1, fillFeatures.size)
        val polygon = fillFeatures.single()
        assertEquals("#f5c96b", polygon.getStringProperty("colour"))
        val ring = (polygon.geometry() as Polygon).coordinates().single()
        assertEquals(vertices.map { Point.fromLngLat(it.longitude, it.latitude) } + ring.first(), ring)
        val patchFeatures = fields.patches.features()!!
        assertEquals(3, patchFeatures.size)
        assertEquals("#e8763c", patchFeatures.first().getStringProperty("colour"))
        assertEquals(355.0, patchFeatures[1].getNumberProperty("bearing").toDouble(), 0.0)
        assertEquals(Point.fromLngLat(dodd.first.longitude, dodd.first.latitude), patchFeatures.last().geometry())
    }

    @Test fun zeroSpeedKeepsCoverageWithoutDrawingADirection() {
        val cell = FillCell(listOf(Coordinate(48.0, -123.0), Coordinate(48.0, -122.9), Coordinate(48.1, -123.0)), 0.0, 0.0)
        val features = CurrentMapFields(emptyList(), listOf(cell)).features().patches.features()!!
        assertEquals(1, features.size)
        assertEquals("#f5c96b", features.single().getStringProperty("colour"))
    }

    @Test fun absentPatchAndStationDirectionLeaveOnlyCertifiedBackdrop() {
        val vertices = listOf(Coordinate(48.0, -123.0), Coordinate(48.0, -122.9), Coordinate(48.1, -123.0))
        val fields = CurrentMapFields(listOf(FillCell(vertices, 12.0, 13.0)), emptyList()).features()
        assertEquals(0, fields.patches.features()!!.size)
        val features = fields.fill.features()!!
        assertEquals(2, features.size)
        assertEquals("#c93a32", features.first().getStringProperty("colour"))
        assertEquals(13.0, features.last().getNumberProperty("bearing").toDouble(), 0.0)
    }
}
