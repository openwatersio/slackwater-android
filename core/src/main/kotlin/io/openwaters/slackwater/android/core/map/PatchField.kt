package io.openwaters.slackwater.android.core.map

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.openwaters.slackwater.CurrentVector
import io.openwaters.slackwater.android.core.station.Coordinate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { "%02x".format(it) }

data class PatchAnchor(val provider: String, val stationId: String)

private data class PatchTriangle(
    val anchor: PatchAnchor,
    val vertices: List<Coordinate>,
    val scale: Double,
    val bearing: Double,
) {
    val bounds = triangleBounds(vertices)
    fun direction(signed: Double) = (bearing + if (signed < 0) 180 else 0) % 360
}

class PatchField private constructor(private val triangles: List<PatchTriangle>) {
    val anchors: Set<PatchAnchor> = triangles.mapTo(linkedSetOf()) { it.anchor }

    companion object {
        fun decode(binary: ByteArray, headerJson: String): PatchField? = try {
            val header = JsonParser.parseString(headerJson).asJsonObject
            require(header.integer("format_version") == 1)
            require(header.string("bin_sha256") == binary.sha256Hex())
            val input = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
            val triangles = buildList {
                for (element in header.get("patches").asJsonArray) {
                    val patch = element.asJsonObject
                    val source = patch.get("anchor").asJsonObject
                    val anchor = PatchAnchor(source.string("provider"), source.string("station_id"))
                    require(patch.integer("offset") == input.position())
                    val count = patch.integer("cell_count")
                    require(count in 0..input.remaining() / 30)
                    repeat(count) { add(input.readTriangle(anchor)) }
                }
            }
            require(!input.hasRemaining())
            PatchField(triangles)
        } catch (_: RuntimeException) {
            null
        }

        private fun JsonObject.integer(key: String): Int {
            val value = get(key).asJsonPrimitive
            require(value.isNumber)
            return value.asBigDecimal.intValueExact()
        }

        private fun JsonObject.string(key: String): String {
            val value = get(key).asJsonPrimitive
            require(value.isString && value.asString.isNotBlank())
            return value.asString
        }

        private fun ByteBuffer.readTriangle(anchor: PatchAnchor): PatchTriangle {
            val vertices = List(3) {
                val lon = float.toDouble()
                val lat = float.toDouble()
                require(lon in -180.0..180.0 && lat in -90.0..90.0)
                Coordinate(lat, lon)
            }
            val (a, b, c) = vertices
            require((b.longitude - a.longitude) * (c.latitude - a.latitude) !=
                (b.latitude - a.latitude) * (c.longitude - a.longitude))
            val scale = f16()
            val bearing = f16()
            val width = f16()
            require(scale.isFinite() && scale > 0 && bearing in 0.0..360.0 && width.isFinite() && width > 0)
            return PatchTriangle(anchor, vertices, scale, bearing)
        }
    }

    // Cache missing readings too. Multiple shipped patches may share the same anchor.
    private fun speeds(resolve: (PatchAnchor) -> Double?): (PatchAnchor) -> Double? {
        val cache = mutableMapOf<PatchAnchor, Double?>()
        return { anchor ->
            if (anchor !in cache) cache[anchor] = resolve(anchor)?.takeIf(Double::isFinite)
            cache[anchor]
        }
    }

    fun cells(bounds: PinBox? = null, resolve: (PatchAnchor) -> Double?): List<FillCell> {
        val speed = speeds(resolve)
        return triangles.mapNotNull { triangle ->
            if (bounds != null && !bounds.overlapsTriangle(triangle.bounds)) return@mapNotNull null
            val signed = speed(triangle.anchor) ?: return@mapNotNull null
            FillCell(triangle.vertices, abs(signed) * triangle.scale, triangle.direction(signed))
        }
    }

    fun sample(point: Coordinate, resolve: (PatchAnchor) -> Double?): CurrentVector? {
        val speed = speeds(resolve)
        for (triangle in triangles) {
            if (!triangle.bounds.contains(point.latitude, point.longitude) || !triangleContains(point, triangle.vertices)) continue
            val signed = speed(triangle.anchor) ?: continue
            return CurrentVector(abs(signed) * triangle.scale, triangle.direction(signed))
        }
        return null
    }

}
