package io.openwaters.slackwater.android.core.map

import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import org.junit.Assert.*
import org.junit.Test

class ChartGridTest {
    private fun station(latitude: Double, longitude: Double) =
        StationIdentity(StationId("sample"), "Sample", "", latitude, longitude, StationKind.Tide)

    @Test fun withoutFixOrStationsOnlyWorldIsNeeded() {
        assertEquals(setOf(ChartPackSpec("world", -85.0511, -180.0, 85.0511, 180.0, 0, 4)),
            desiredChartPacks(null, emptyList()))
    }

    @Test fun stationSharesFixCellAndKeepsItsOwnDetailTier() {
        val station = station(0.0, 0.0)
        val packs = desiredChartPacks(Coordinate(0.0, 0.0), listOf(station, station))
        assertEquals(11, packs.size)
        val cells = packs.filter { it.key.startsWith("area/") }
        assertEquals(9, cells.size)
        assertTrue(cells.all { it.minZoom == 5 && it.maxZoom == 8 })
        val center = cells.single { it.key == "area/5/16/16" }
        assertEquals(-11.17840187, center.south, 1e-7)
        assertEquals(0.0, center.north, 0.0)
        assertEquals(0.0, center.west, 0.0)
        assertEquals(11.25, center.east, 0.0)
        val detail = packs.single { it.key == "station/sample" }
        assertEquals(9..12, detail.minZoom..detail.maxZoom)
        assertEquals(20.0 / 111, detail.east, 1e-9)
        assertEquals(-detail.east, detail.west, 1e-9)
        assertEquals(setOf("world", center.key, detail.key),
            desiredChartPacks(null, listOf(station)).map { it.key }.toSet())
    }

    @Test fun fixAtPolesClampsRowsAndWrapsColumns() {
        for ((latitude, rows) in listOf(90.0 to 0..1, -90.0 to 30..31)) {
            val cells = desiredChartPacks(Coordinate(latitude, 180.0), emptyList()).map { it.key } - "world"
            assertEquals(rows.flatMap { y -> listOf(30, 31, 0).map { x -> "area/5/$x/$y" } }.toSet(), cells.toSet())
        }
    }

    @Test fun stationDetailSplitsAtEitherSideOfDatelineAndWidensAtLatitude() {
        for (longitude in listOf(-179.9, 179.9)) {
            val details = desiredChartPacks(null, listOf(station(60.0, longitude))).filter { it.minZoom == 9 }
            assertEquals(setOf("station/sample", "station/sample/wrap"), details.map { it.key }.toSet())
            assertTrue(details.all { it.west >= -180 && it.east <= 180 && it.west < it.east })
            assertEquals(80.0 / 111, details.sumOf { it.east - it.west }, 1e-9)
        }
    }

    @Test fun stationBoundsIntersectTheMercatorExtent() {
        for (latitude in listOf(-85.0, 85.0, -90.0, 90.0)) {
            val detail = desiredChartPacks(null, listOf(station(latitude, 0.0))).filter { it.minZoom == 9 }
            assertEquals(if (kotlin.math.abs(latitude) == 85.0) 1 else 0, detail.size)
            assertTrue(detail.all { it.south >= -85.0511 && it.north <= 85.0511 && it.south < it.north })
        }
    }
}
