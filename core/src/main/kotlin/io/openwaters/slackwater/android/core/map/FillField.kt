package io.openwaters.slackwater.android.core.map

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.openwaters.slackwater.constituentAstronomy
import io.openwaters.slackwater.CurrentStation
import io.openwaters.slackwater.CurrentVector
import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.android.core.station.Coordinate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class FillCell(val polygon: List<Coordinate>, val speedKn: Double, val bearingDeg: Double)

internal fun ByteBuffer.f16(): Double {
    val bits = short.toInt() and 0xffff
    val exponent = (bits ushr 10) and 31
    val mantissa = bits and 1023
    require(exponent != 31) { "Non-finite field value" }
    val magnitude = if (exponent == 0) Math.scalb(mantissa.toDouble(), -24)
        else Math.scalb((1024 + mantissa).toDouble(), exponent - 25)
    return if (bits and 0x8000 == 0) magnitude else -magnitude
}

internal fun triangleBounds(vertices: List<Coordinate>) = PinBox(
    vertices.minOf { it.latitude }, vertices.minOf { it.longitude },
    vertices.maxOf { it.latitude }, vertices.maxOf { it.longitude },
)

internal fun PinBox.overlapsTriangle(bounds: PinBox): Boolean =
    south <= bounds.north && north >= bounds.south &&
        (if (west <= east) west <= bounds.east && east >= bounds.west
         else west <= bounds.east || east >= bounds.west)

fun triangleContains(point: Coordinate, vertices: List<Coordinate>): Boolean {
    if (vertices.size != 3) return false
    var positive = false
    var negative = false
    for (i in vertices.indices) {
        val a = vertices[i]
        val b = vertices[(i + 1) % 3]
        val side = (point.longitude - b.longitude) * (a.latitude - b.latitude) -
            (a.longitude - b.longitude) * (point.latitude - b.latitude)
        positive = positive || side > 0
        negative = negative || side < 0
    }
    return !(positive && negative)
}

class FillField private constructor(private val elements: List<Element>, private val names: Map<Int, String>) {
    val elementCount: Int get() = elements.size

    private class Axis(val offset: Double, val ids: IntArray, val cosine: DoubleArray, val sine: DoubleArray) {
        fun evaluate(basis: Basis): Double {
            var result = offset
            for (i in ids.indices) result += cosine[i] * basis.cosine[ids[i]] + sine[i] * basis.sine[ids[i]]
            return result
        }
    }

    // The mesh shares one astronomical argument per constituent. Evaluate the engine's
    // unit harmonics once per instant, then apply each element's amplitude and phase lag.
    private class Basis(names: Map<Int, String>, time: Instant) {
        val cosine = DoubleArray(256)
        val sine = DoubleArray(256)
        init {
            for ((id, name) in names) {
                fun unit(phase: Double) = CurrentStation(listOf(HarmonicConstituent(name, 1.0, phase)))
                    .speeds(time, time.plusSeconds(1), 1.0).first().speedKnots
                cosine[id] = unit(0.0)
                sine[id] = unit(90.0)
            }
        }
    }

    private class Element(val polygon: List<Coordinate>, val u: Axis, val v: Axis) {
        val bounds = triangleBounds(polygon)
        fun cell(basis: Basis): FillCell {
            val east = u.evaluate(basis)
            val north = v.evaluate(basis)
            return FillCell(polygon, hypot(east, north), (Math.toDegrees(atan2(east, north)) + 360) % 360)
        }
    }

    fun sample(point: Coordinate, time: Instant): CurrentVector? {
        val element = elements.firstOrNull {
            it.bounds.contains(point.latitude, point.longitude) && triangleContains(point, it.polygon)
        } ?: return null
        val cell = element.cell(Basis(names, time))
        return CurrentVector(cell.speedKn, cell.bearingDeg)
    }

    fun cells(time: Instant, bbox: PinBox? = null): List<FillCell> {
        val basis by lazy(LazyThreadSafetyMode.NONE) { Basis(names, time) }
        return elements.mapNotNull { if (bbox == null || bbox.overlapsTriangle(it.bounds)) it.cell(basis) else null }
    }

    companion object {
        fun decode(bin: ByteArray, headerJson: String): FillField? = try {
            val header = JsonParser.parseString(headerJson).asJsonObject
            require(header["format_version"].integer() == 1)
            val digest = header["bin_sha256"].asJsonPrimitive
            require(digest.isString && digest.asString == bin.sha256Hex())
            val offsets = header["offsets"].asJsonArray.map { it.integer() }
            require(offsets.size - 1 == header["element_count"].integer() && offsets.first() == 0 && offsets.last() == bin.size)
            require(offsets.zipWithNext().all { (start, end) -> end > start })
            val names = mutableMapOf<Int, String>()
            for ((key, value) in requireNotNull(header["constituents"]).asJsonObject.entrySet()) {
                val id = key.toInt()
                require(id in 0..255 && key == id.toString() && value.asJsonPrimitive.isString)
                val name = value.asString
                require(constituentAstronomy(name, Instant.EPOCH) != null)
                names[id] = name
            }
            val elements = offsets.zipWithNext { start, end ->
                val bytes = ByteBuffer.wrap(bin, start, end - start).order(ByteOrder.LITTLE_ENDIAN)
                require(bytes.u8() == 3)
                val polygon = List(3) {
                    val lon = bytes.float.toDouble()
                    val lat = bytes.float.toDouble()
                    require(lon in -180.0..180.0 && lat in -90.0..90.0)
                    Coordinate(lat, lon)
                }
                val (a, b, c) = polygon
                require((b.longitude - a.longitude) * (c.latitude - a.latitude) !=
                    (b.latitude - a.latitude) * (c.longitude - a.longitude))
                val uOffset = bytes.f16()
                val vOffset = bytes.f16()
                val element = Element(polygon, bytes.axis(uOffset, names), bytes.axis(vOffset, names))
                require(!bytes.hasRemaining())
                element
            }
            FillField(elements, names)
        } catch (_: RuntimeException) {
            null
        }

        private fun JsonElement.integer(): Int {
            val number = asJsonPrimitive
            require(number.isNumber)
            return number.asBigDecimal.intValueExact()
        }

        private fun ByteBuffer.u8(): Int = get().toInt() and 255
        private fun ByteBuffer.axis(offset: Double, names: Map<Int, String>): Axis {
            val count = u8()
            val ids = IntArray(count)
            val cosine = DoubleArray(count)
            val sine = DoubleArray(count)
            for (i in ids.indices) {
                val id = u8()
                require(id in names)
                ids[i] = id
                val amplitude = f16()
                val phase = Math.toRadians(f16())
                cosine[i] = amplitude * cos(phase)
                sine[i] = amplitude * sin(phase)
            }
            return Axis(offset, ids, cosine, sine)
        }
    }
}
