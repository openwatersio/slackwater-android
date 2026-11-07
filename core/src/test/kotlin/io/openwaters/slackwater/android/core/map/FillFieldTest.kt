package io.openwaters.slackwater.android.core.map

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import io.openwaters.slackwater.CurrentStation
import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.android.core.station.Coordinate
import java.io.File
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class FillFieldTest {
    private val fixture = File(System.getProperty("fillFixture"))
    private val bin get() = File(fixture, "fill-fixture.bin").readBytes()
    private val json get() = File(fixture, "fill-fixture.json").readText()

    @Test fun packedAxesMatchIndependentStationEvaluations() {
        val field = requireNotNull(FillField.decode(bin, json))
        val east = CurrentStation(listOf(HarmonicConstituent("M2", 1.2001953125, 360.0)), offsetKnots = 0.5)
        val north = CurrentStation(listOf(HarmonicConstituent("K1", 0.60009765625, 0.0999755859375)), offsetKnots = -0.300048828125)
        for (epoch in listOf(1787227200L, 1787248800L)) {
            val time = Instant.ofEpochSecond(epoch)
            val cells = field.cells(time)
            assertEquals(3, cells.size)
            val first = cells.first()
            assertEquals(-123.0, first.polygon.first().longitude, 1e-5)
            assertEquals(48.7, first.polygon.first().latitude, 1e-5)
            val angle = Math.toRadians(first.bearingDeg)
            assertEquals(east.speeds(time, time.plusSeconds(1), 1.0).first().speedKnots, first.speedKn * sin(angle), 1e-9)
            assertEquals(north.speeds(time, time.plusSeconds(1), 1.0).first().speedKnots, first.speedKn * cos(angle), 1e-9)
            assertEquals(listOf(first), field.cells(time, PinBox(48.702, -122.998, 48.704, -122.996)))
            assertEquals(first.speedKn, field.sample(first.polygon.first(), time)!!.magnitudeKnots, 0.0)
            assertNull(field.sample(Coordinate(48.709, -122.991), time))
        }
    }

    @Test fun shippedSalishElementMatchesIosGolden() {
        val dir = File(System.getProperty("mapStationIndex")).parentFile
        val field = requireNotNull(FillField.decode(File(dir, "fill-salish.bin").readBytes(), File(dir, "fill-salish.json").readText()))
        val cells = field.cells(Instant.ofEpochSecond(1788220800L))
        assertEquals(13168, cells.size)
        assertEquals(1.4969466423542093, cells[3597].speedKn, 1e-6)
        assertEquals(35.747437152706084, cells[3597].bearingDeg, 1e-6)
    }

    @Test fun rejectsDigestMismatchAndMalformedChunks() {
        assertNull(FillField.decode(bin.copyOf().also { it[1] = 1 }, json))
        fun header(change: JsonObject.() -> Unit) = JsonParser.parseString(json).asJsonObject.apply(change).toString()
        fun malformed(index: Int, value: Byte) {
            val changed = bin.copyOf().also { it[index] = value }
            assertNull(FillField.decode(changed, header { addProperty("bin_sha256", changed.sha256Hex()) }))
        }
        malformed(0, 4)
        malformed(29, 127)
        malformed(30, -1)
        for (bad in listOf(
            header { remove("bin_sha256") },
            header { addProperty("format_version", "1") },
            header { addProperty("element_count", "3") },
            header { getAsJsonArray("offsets").set(0, JsonPrimitive("0")) },
            header { getAsJsonArray("offsets").set(1, JsonPrimitive(-1)) },
            header { getAsJsonObject("constituents").addProperty("0", "Unknown") },
            header { getAsJsonObject("constituents").addProperty("00", "M2") },
        )) assertNull(FillField.decode(bin, bad))
        for (bad in listOf("{}", "[]", "{{", "null")) assertNull(FillField.decode(bin, bad))
    }
}
