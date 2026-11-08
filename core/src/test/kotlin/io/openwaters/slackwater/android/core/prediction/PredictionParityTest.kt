package io.openwaters.slackwater.android.core.prediction

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.openwaters.slackwater.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

class PredictionParityTest {
    private fun fixture(name: String) = File(System.getProperty("predictionFixtures"), "$name.json")
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    private fun JsonObject.n(key: String) = get(key).asDouble
    private fun JsonObject.t(key: String) = Instant.parse(get(key).asString)
    private fun JsonObject.rows(key: String) = getAsJsonArray(key).map { it.asJsonObject }
    private fun JsonObject.span() = TimeSpan(t("start"), t("end"))
    private fun JsonObject.constants(key: String = "constituents") = rows(key).map {
        HarmonicConstituent(it["name"].asString, it.n("amplitude"), it.n("phase"))
    }
    private fun JsonObject.current() = CurrentStation(constants(), n("floodDirection"), n("ebbDirection"), n("offset"))
    private fun JsonObject.subordinate(ref: CurrentStation) = SubordinateCurrentStation(ref,
        n("slackBeforeFloodOffset"), n("slackBeforeEbbOffset"), n("floodTimeOffset"), n("ebbTimeOffset"),
        n("floodSpeedRatio"), n("ebbSpeedRatio"), get("floodDirection")?.takeUnless { it.isJsonNull }?.asDouble, get("ebbDirection")?.takeUnless { it.isJsonNull }?.asDouble)
    private fun near(a: Instant, b: Instant, seconds: Double) =
        assertTrue("$a vs $b", Duration.between(a,b).abs().toNanos() / 1e9 < seconds)
    private fun eventKind(row: JsonObject) = when (row["kind"].asString) {
        "slack" -> CurrentEventKind.SLACK
        "maxFlood" -> CurrentEventKind.MAX_FLOOD
        "maxEbb" -> CurrentEventKind.MAX_EBB
        else -> error("Unknown fixture event")
    }

    @Test fun harmonicTideTimelineAndTurns() {
        val f = fixture("prediction-victoria")
        val result = EngineAdapter.tide(Station(f.constants()), f.span(), f.n("step"))
        val expected = f.rows("points")
        assertEquals(expected.size, result.points.size)
        for ((p,row) in result.points.zip(expected)) {
            near(p.time,row.t("time"),0.5)
            assertEquals(row.n("height"),p.heightMetres,1e-6)
        }
        for (name in listOf("extremes-victoria","extremes-midnight")) {
            val e = fixture(name)
            val events = EngineAdapter.tide(Station(if (e.has("constituents")) e.constants() else f.constants()), e.span()).events
            assertEquals(e.rows("extremes").size, events.size)
            for ((turn,row) in events.zip(e.rows("extremes"))) {
                assertEquals(row["kind"].asString, turn.kind.name.lowercase())
                near(turn.time,row.t("time"),60.0)
                assertEquals(row.n("height"),turn.heightMetres,0.02)
            }
        }
    }

    @Test fun harmonicAndSubordinateCurrentTimelineAndTurns() {
        val f = fixture("currents-parity")
        for (key in listOf("harmonic","subordinate")) {
            val model = f.getAsJsonObject(key)
            fun predict(span: TimeSpan) = if (key == "harmonic") EngineAdapter.current(model.current(),span)
                else EngineAdapter.current(model.subordinate(model.getAsJsonObject("reference").current()),span)
            val points = predict(f.span()).points
            assertEquals(model.rows("timeline").size, points.size)
            for ((p,row) in points.zip(model.rows("timeline"))) {
                near(p.time,row.t("time"),0.5)
                assertEquals(row.n("speed"),p.speedKnots,1e-6)
            }
            val rows = model.rows("events")
            val events = predict(TimeSpan(rows.first().t("time").minusSeconds(3600),rows.last().t("time").plusSeconds(3600))).events
            assertEquals(rows.size,events.size)
            for ((e,row) in events.zip(rows)) {
                assertEquals(eventKind(row),e.kind)
                near(e.time,row.t("time"),60.0)
                assertEquals(row.n("speed"),e.speedKnots,0.02)
            }
        }
    }

    @Test fun vectorTimeline() {
        val f = fixture("currents-vector")
        val station = CurrentStation(f.constants(),f.n("floodDirection"),f.n("ebbDirection"),f.n("meanFlow"),
            f.constants("minorConstituents"),f.n("minorMeanFlow"),f.n("azimuth"))
        val points = EngineAdapter.current(station,f.span(),f.n("timeFidelity")).points
        assertEquals(f.rows("timeline").size,points.size)
        for ((p,row) in points.zip(f.rows("timeline"))) {
            near(p.time,row.t("time"),0.001)
            assertEquals(row.n("speed"),p.speedKnots,1e-6)
            assertEquals(row.n("magnitude"),p.magnitudeKnots,1e-6)
            assertEquals(row.n("direction"),p.directionDegrees!!,1e-5)
        }
    }

    @Test fun publishedNoaaVectors() {
        val f = fixture("currents-vector-noaa")
        val station = CurrentStation(f.constants(), offsetKnots = f.n("meanFlow"),
            minorConstituents = f.constants("minorConstituents"), minorOffsetKnots = f.n("minorMeanFlow"),
            azimuthDegrees = f.n("azimuth"))
        val points = EngineAdapter.current(station,f.span(),f.n("timeFidelity")).points
        assertEquals(f.rows("timeline").size, points.size)
        for ((point,row) in points.zip(f.rows("timeline"))) {
            near(point.time,row.t("time"),0.001)
            assertTrue(abs(row.n("magnitude") - point.magnitudeKnots) < 0.15)
            if (row.n("magnitude") >= 0.75) {
                val diff = abs(row.n("direction") - point.directionDegrees!!) % 360
                assertTrue(minOf(diff,360-diff) < 5)
            }
        }
    }

    @Test fun chsGoldenRecipeWindows() {
        val f = fixture("chs-current-golden")
        assertEquals(ChsCurrentFit.STAMP,f["stamp"].asString)
        val span = f.getAsJsonObject("events").span()
        for (model in f.rows("windows")) {
            val series = EngineAdapter.current(CurrentStation(model.constants(),offsetKnots = model.n("offset")),span)
            val expected = model.getAsJsonArray("velocities")
            assertEquals(expected.size(),series.points.size)
            for ((p,v) in series.points.zip(expected)) assertEquals(v.asDouble,p.speedKnots,1e-6)
            val slacks = series.events.filter { it.kind == CurrentEventKind.SLACK && it.time < span.end }
            val times = model.getAsJsonArray("slacks")
            assertEquals(times.size(),slacks.size)
            for ((e,v) in slacks.zip(times)) near(e.time,Instant.parse(v.asString),1.0)
            val peaks = series.events.filter { it.kind != CurrentEventKind.SLACK && it.time < span.end }
            assertEquals(model.rows("extrema").size,peaks.size)
            for ((e,row) in peaks.zip(model.rows("extrema"))) {
                near(e.time,row.t("time"),1.0)
                assertEquals(eventKind(row),e.kind)
                assertEquals(row.n("speed"),e.speedKnots,0.001)
            }
        }
    }

    @Test fun officialCurrentGoldens() {
        fun check(row: JsonObject, minutes: Int, tolerance: Double, floor: Double = 0.0, predict: (TimeSpan)->PredictionSeries.Current) {
            val events = row.rows("events")
            val series = predict(TimeSpan(events.minOf { it.t("time") }.minusSeconds(3600),events.maxOf { it.t("time") }.plusSeconds(3600)))
            val peaks = events.filter { eventKind(it) != CurrentEventKind.SLACK && abs(it.n("speed")) >= floor }
            assertTrue(peaks.isNotEmpty())
            for (peak in peaks) {
                val nearest = series.events.filter { it.kind == eventKind(peak) }.minBy { Duration.between(it.time,peak.t("time")).abs() }
                near(nearest.time,peak.t("time"),minutes * 60.0)
                assertEquals(abs(peak.n("speed")),abs(nearest.speedKnots),tolerance)
            }
        }
        val h = fixture("currents-golden-harmonic")
        check(h,20,0.30) { EngineAdapter.current(h.current(),it) }
        for (row in fixture("currents-golden-home").rows("stations")) check(row,20,0.35,0.75) { EngineAdapter.current(row.current(),it) }
        val sub = fixture("currents-golden-subordinate")
        val ref = CurrentStation(sub.constants("refConstituents"),sub.n("refFloodDirection"),sub.n("refEbbDirection"),sub.n("refOffset"))
        check(sub,30,0.40) { EngineAdapter.current(sub.subordinate(ref),it) }
        val inputs = fixture("currents-sample").rows("stations").associateBy { it["id"].asString }
        for (row in fixture("currents-golden-sub-batch").rows("stations")) {
            val record = inputs.getValue(row["id"].asString)
            val station = record.subordinate(inputs.getValue(record["reference"].asString).current())
            check(row,30,0.40) { EngineAdapter.current(station,it) }
        }
    }

    @Test fun stationWithoutConstituentsReturnsOnlyItsDatum() {
        val time = Instant.parse("1950-01-01T12:00:00Z")
        val station = Station(emptyList(), 1.387)
        assertEquals(1.387, station.heights(time, time, 1.0).single().heightMetres, 1e-9)
    }

    @Test fun datumShiftAddsMetresToEveryHeight() {
        val fixture = fixture("prediction-victoria")
        val base = EngineAdapter.tide(Station(fixture.constants()), fixture.span())
        val shifted = EngineAdapter.tide(Station(fixture.constants(), 1.387), fixture.span())
        assertTrue(base.points.isNotEmpty())
        for ((before, after) in base.points.zip(shifted.points)) {
            assertEquals(before.heightMetres + 1.387, after.heightMetres, 1e-6)
        }
    }

    @Test fun subordinateOffsetsAreMinutes() {
        val fixture = fixture("prediction-victoria")
        val reference = Station(fixture.constants())
        val subordinate = EngineAdapter.subordinateTide(reference, 5, -3, HeightOffset.Fixed(0.0, 0.0))
        val span = fixture.span()
        val turns = EngineAdapter.tide(subordinate, span).events
        val expected = EngineAdapter.tide(reference, span).events.filter {
            it.time > span.start.plusSeconds(300) && it.time < span.end.minusSeconds(300)
        }
        assertTrue(expected.map { it.kind }.distinct().size == 2)
        for (turn in expected) {
            val shift = if (turn.kind.name == "HIGH") 300L else -180L
            val closest = turns.filter { it.kind == turn.kind }.minBy { Duration.between(turn.time, it.time).abs() }
            near(turn.time.plusSeconds(shift), closest.time, 1.0)
        }
    }

    @Test fun realWorldDatumAndSubordinateOffsets() {
        fun check(series: PredictionSeries.Tide, expected: List<JsonObject>) {
            for (row in expected) {
                val turn = series.events.filter { it.kind.name.lowercase() == row["kind"].asString }.minBy { Duration.between(it.time,row.t("time")).abs() }
                near(turn.time,row.t("time"),900.0)
                assertEquals(row.n("height"),turn.heightMetres,0.15)
            }
        }
        val f = fixture("realworld-friday-harbor")
        fun padded(row: JsonObject) = TimeSpan(row.t("start").minusSeconds(3600),row.t("end").plusSeconds(3600))
        check(EngineAdapter.tide(Station(f.constants(),f.n("offset")),padded(f)), f.rows("official"))
        val subs = fixture("realworld-subordinates")
        for (row in subs.rows("cases")) {
            val offsets = row.getAsJsonObject("offsets")
            val h = offsets.getAsJsonObject("height")
            val t = offsets.getAsJsonObject("time")
            val height = if (h["type"].asString == "ratio") HeightOffset.Ratio(h.n("high"),h.n("low")) else HeightOffset.Fixed(h.n("high"),h.n("low"))
            val station = EngineAdapter.subordinateTide(Station(row.constants(),row.n("offset")),t["high"].asInt,t["low"].asInt,height)
            check(EngineAdapter.tide(station,padded(subs)),row.rows("official"))
        }
    }
}
