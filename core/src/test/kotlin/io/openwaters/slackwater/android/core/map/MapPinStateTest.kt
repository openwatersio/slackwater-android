package io.openwaters.slackwater.android.core.map

import com.google.gson.JsonParser
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.station.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class MapPinStateTest {
    private val now = Instant.ofEpochSecond(1_785_000_000)

    @Test fun measuredTideFloorsDecideWhenTheExactSearchRuns() {
        fun state(delta: Double, proxy: Double): String {
            return tidePinState(now, false, proxy, { start, end, step ->
                assertEquals(now, start)
                assertEquals(now.plusSeconds(1800), end)
                assertEquals(1800.0, step, 0.0)
                listOf(TidePoint(start, 0.0), TidePoint(end, delta))
            }, { start, end ->
                assertEquals(now.plusSeconds(108000), end)
                listOf(TideExtreme(start, 1.0, ExtremeKind.HIGH), TideExtreme(end, 0.0, ExtremeKind.LOW))
            }, { error("No low-zoom reading") }).state
        }
        assertEquals("rising", state(0.01, 25.0))
        assertEquals("falling", state(0.00999, 1.0))
        assertEquals("falling", state(0.01, 25.01))
        assertEquals("falling", state(1.0, 0.0))
        assertEquals(15.0411, tideRangeProxy(mapOf("K1" to 1.0, "UNKNOWN" to 10.0)), 0.0)
        assertEquals(MapPinState(), tidePinState(now, true, 0.0, { _, _, _ -> error("subordinate") },
            { _, _ -> emptyList() }, { error("no reading") }))
    }

    @Test fun victoriaHybridFollowsTheNextTurnAcrossThePinnedLunarMonthProbes() {
        val file = File(System.getProperty("predictionFixtures"), "prediction-victoria.json")
        val constants = file.reader().use { JsonParser.parseReader(it).asJsonObject["constituents"].asJsonArray }
            .map { element ->
                val row = element.asJsonObject
                HarmonicConstituent(row["name"].asString, row["amplitude"].asDouble, row["phase"].asDouble)
            }
        val station = Station(constants)
        val proxy = tideRangeProxy(constants.associate { it.name to it.amplitude })
        for ((day, minutes) in listOf(0 to 0, 7 to 11, 13 to 23, 20 to 37, 27 to 49)) {
            for (hour in listOf(0, 6, 12, 18)) {
                val time = now.plusSeconds(day * 86400L + hour * 3600L + minutes * 60)
                val next = station.extremes(time, time.plusSeconds(108000)).first { it.time > time }
                val state = tidePinState(time, false, proxy, station::heights, station::extremes, Double::toString)
                assertEquals("at $time", if (next.kind == ExtremeKind.HIGH) "rising" else "falling", state.state)
            }
        }
    }

    @Test fun gaugeNeedsOpposingBracketingTurnsConsistentWithTheTone() {
        fun state(nextKind: ExtremeKind) = tidePinState(now, true, 1.0, { start, end, _ ->
            listOf(TidePoint(start, 1.5), TidePoint(end, 2.0))
        }, { start, end -> listOf(TideExtreme(start, 1.0, ExtremeKind.LOW), TideExtreme(end, 3.0, nextKind)) },
            { "$it m" })
        assertEquals(MapPinState("rising", "1.5 m", gauge = 2), state(ExtremeKind.HIGH))
        assertNull(state(ExtremeKind.LOW).gauge)
    }

    @Test fun majorAxisColorAndDoddVectorKeepTheirDistinctSlackBehavior() {
        fun pin(speed: Double?) = currentPinState(speed, 355.0, 155.0, true) { "$it kn" }
        assertEquals(MapPinState(), pin(null))
        assertEquals(MapPinState("#88b868", "0.0 kn", 355.0), pin(0.0))
        assertEquals("#f8d997", pin(0.01).state)
        assertEquals("#ef9f77", pin(-8.0).state)
        assertEquals(pin(-6.0).state, pin(6.0).state)
        assertEquals(CurrentVector(6.0, 155.0), doddMapVector(-6.0, 355.0, 155.0, now))
        assertEquals(CurrentVector(6.0, 355.0), doddMapVector(6.0, 355.0, 155.0, now))
        for (missing in listOf(null, 0.0)) assertNull(doddMapVector(missing, 355.0, 155.0, now))
        assertNull(doddMapVector(6.0, null, null, now))
    }

    @Test fun subordinateSpeedsReuseReferenceEventsAndMatchTheEngineCurve() {
        val reference = CurrentStation(listOf(HarmonicConstituent("M2", 2.359, 165.5)))
        val station = SubordinateCurrentStation(reference, 120.0, -180.0, 240.0, -300.0, 0.9, 1.1)
        val cache = ReferenceCurrentEvents()
        val bucket = Instant.ofEpochSecond(now.epochSecond / 1800 * 1800)
        var loads = 0
        for (delta in listOf(0L, 300, 1200, 1800)) {
            val time = bucket.plusSeconds(delta)
            val events = cache.events(StationId("ref"), time) { start, end ->
                loads++
                reference.events(start, end)
            }
            val exact = station.speeds(time, time.plusSeconds(1), 1.0).first().speedKnots
            assertEquals(exact, subordinateCurrentSpeed(time, events, station)!!, 0.002)
        }
        assertEquals(2, loads)
        assertNull(subordinateCurrentSpeed(now, emptyList(), station))
    }

    @Test fun shippedSubordinatesMatchTheEngineAcrossAFullCycleAndBucketBoundaries() {
        val file = File(System.getProperty("mapStationIndex")).resolveSibling("currents.json")
        val rows = file.reader().use { JsonParser.parseReader(it).asJsonArray }
            .associate { it.asJsonObject["id"].asString to it.asJsonObject }
        // Mixed Atlantic and Pacific references, including the largest shipped time correction.
        for (id in listOf("noaa/ACT5176", "noaa/PCT5756", "noaa/ACT7311", "noaa/ACT3196", "noaa/ACT3366", "noaa/PCT5861")) {
            val row = rows.getValue(id)
            val referenceId = row["reference"].asString
            val source = rows.getValue(referenceId)
            val constants = source["constituents"].asJsonArray.map {
                val c = it.asJsonObject
                HarmonicConstituent(c["name"].asString, c["amplitude"].asDouble, c["phase"].asDouble)
            }
            val reference = CurrentStation(constants, offsetKnots = source["meanFlow"].asDouble)
            val station = SubordinateCurrentStation(reference, row["slackBeforeFloodOffset"].asDouble,
                row["slackBeforeEbbOffset"].asDouble, row["floodTimeOffset"].asDouble, row["ebbTimeOffset"].asDouble,
                row["floodSpeedRatio"].asDouble, row["ebbSpeedRatio"].asDouble)
            val cache = ReferenceCurrentEvents()
            for (hours in 0 until 24 step 3) {
                for (seconds in listOf(-1, 0, 1)) {
                    val time = Instant.ofEpochSecond(now.epochSecond / 1800 * 1800 + hours * 3600 + seconds)
                    val events = cache.events(StationId(referenceId), time, reference::events)
                    val engine = station.speeds(time, time, 1.0).single().speedKnots
                    assertEquals("$id at $time", engine, subordinateCurrentSpeed(time, events, station)!!, 0.002)
                }
            }
        }
    }

    @Test fun realStationDensityStaysBoundedAndHarbourRetainsDetail() {
        val index = File(System.getProperty("mapStationIndex")).reader().use { JsonParser.parseReader(it).asJsonObject }
        val candidates = listOf("tides" to StationKind.Tide, "currents" to StationKind.Current).flatMap { (key, kind) ->
            index[key].asJsonArray.map {
                val row = it.asJsonObject
                PinCandidate(StationIdentity(StationId(row["id"].asString), row["name"].asString, "",
                    row["latitude"].asDouble, row["longitude"].asDouble, kind), 0.0)
            }
        }
        val coast = PinBox(25.0, -95.0, 45.0, -66.0)
        val counts = listOf(3.0, 5.0, 7.0).map { visibleStations(coast, it, candidates).size }
        assertTrue("coast $counts", counts.all { it in 1..<900 })
        assertEquals(counts.sorted(), counts)
        val harbour = PinBox(32.70, -80.05, 32.87, -79.83)
        val kept = visibleStations(harbour, 12.5, candidates).size
        val inBox = visibleStations(harbour, 12.5, candidates, decimate = false).size
        assertTrue("harbour $kept / $inBox", kept > inBox * 0.8)
        println("Coast z3/5/7 = $counts, harbour z12.5 = $kept/$inBox")
    }

    @Test fun wrappedCameraCoverageAndRankedGridPreserveTheSelectedStation() {
        val crossing = PinBox(-5.0, 175.0, 5.0, -175.0)
        assertFalse(PinBox(-10.0, -179.0, 10.0, 179.0).covers(crossing))
        assertTrue(crossing.padded().covers(crossing))
        assertTrue(crossing.contains(0.0, -179.0))
        assertFalse(crossing.contains(0.0, 0.0))
        assertTrue(PinBox(-80.0, -175.0, 80.0, 175.0).padded().covers(crossing))
        fun candidate(id: String, lon: Double, rank: Double) = PinCandidate(
            StationIdentity(StationId(id), id, "", 0.0, lon, StationKind.Tide), rank)
        val ref = candidate("ref", 178.0, pinRank(false, 1.0))
        val sub = candidate("sub", 178.0001, pinRank(true, 10.0))
        val outside = candidate("out", 0.0, 0.0)
        val rows = listOf(sub, outside, ref)
        assertEquals(listOf(ref), visibleStations(crossing, 5.0, rows))
        assertEquals(listOf(ref, sub), visibleStations(crossing, 5.0, rows, sub.station.id))
        assertEquals(listOf(sub, ref), visibleStations(crossing, 5.0, rows, decimate = false))
    }
}
