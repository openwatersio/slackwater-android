package io.openwaters.slackwater.android.core.map

import io.openwaters.slackwater.CurrentVector
import io.openwaters.slackwater.android.core.station.Coordinate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PatchFieldTest {
    private val anchor = PatchAnchor("chs", "chs-example")
    private fun patch(offset: Int) =
        """{"anchor":{"provider":"chs","station_id":"chs-example"},"offset":$offset,"cell_count":1}"""
    private fun headerFor(bytes: ByteArray) = """{"bin_sha256":"${bytes.sha256Hex()}","format_version":1,"patches":[${patch(0)},${patch(30)}]}"""
    private val binary = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (lat in listOf(48f, 49f)) {
            putFloat(-123f).putFloat(lat)
            putFloat(-122f).putFloat(lat)
            putFloat(-122f).putFloat(lat + 1)
            putShort(0x3800).putShort(0x55a0).putShort(0x5a40) // scale 0.5, flood 90°, width 200 m
        }
    }.array()

    private val header = headerFor(binary)

    @Test fun preservesCertifiedTrianglesAndSignedProjection() {
        val field = requireNotNull(PatchField.decode(binary, header))
        val calls = mutableListOf<PatchAnchor>()
        val cells = field.cells { calls.add(it); -4.0 }
        assertEquals(listOf(anchor), calls)
        assertEquals(setOf(anchor), field.anchors)
        assertEquals(2, cells.size)
        assertEquals(2.0, cells.first().speedKn, 0.0)
        assertEquals(270.0, cells.first().bearingDeg, 0.0)
        assertEquals(cells.take(1), field.cells(PinBox(48.01, -122.9, 48.02, -122.8)) { -4.0 })
        assertEquals(CurrentVector(2.0, 90.0), field.sample(cells.first().polygon.first()) { 4.0 })
        assertNull(field.sample(Coordinate(48.9, -122.9)) { error("outside triangle") })
        calls.clear()
        assertTrue(field.cells { calls.add(it); null }.isEmpty())
        assertEquals(listOf(anchor), calls)
        assertTrue(field.cells { Double.NaN }.isEmpty())
        assertEquals(2, field.cells { 0.0 }.size)
    }

    @Test fun rejectsInvalidHeadersAndGeometry() {
        for (bad in listOf("{}", header.replace("\"format_version\":1", "\"format_version\":2"),
                header.replace("\"offset\":30", "\"offset\":29"), header.replace("\"cell_count\":1", "\"cell_count\":1e100"))) {
            assertNull(PatchField.decode(binary, bad))
        }
        assertNull(PatchField.decode(binary.copyOf(59), header))
        assertNull(PatchField.decode(binary.copyOf(61), header))
        fun corrupt(change: (ByteBuffer) -> Unit): ByteArray = binary.copyOf().also {
            change(ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN))
        }
        assertNull(PatchField.decode(corrupt { it.putFloat(0, -124f) }, header))
        for (invalid in listOf(corrupt { it.putFloat(0, Float.NaN) }, corrupt { it.putShort(24, 0x7c00) },
                corrupt { it.putFloat(20, 48f) })) {
            assertNull(PatchField.decode(invalid, headerFor(invalid)))
        }
    }

    @Test fun shippedTacomaGoldenAndEmptyDeceptionPass() {
        val resources = File(System.getProperty("mapStationIndex")).parentFile
        val field = requireNotNull(PatchField.decode(File(resources, "patches-salish.bin").readBytes(),
            File(resources, "patches-salish.json").readText()))
        val tacoma = PatchAnchor("noaa", "PUG1527")
        assertEquals(setOf(tacoma, PatchAnchor("chs", "chs-seymour-narrows")), field.anchors)
        val cells = field.cells { if (it == tacoma) -2.0 else null }
        assertEquals(56, cells.size)
        assertEquals(2.0 * 0.80517578125, cells.first().speedKn, 0.0)
        assertEquals(337.0, cells.first().bearingDeg, 0.0)
    }
}
