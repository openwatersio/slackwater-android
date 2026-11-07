package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.CurrentPoint
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationId
import java.io.IOException
import java.time.Duration
import java.time.Instant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class OnlineWindowBlock(
    val points: List<CurrentPoint>,
    val floodDirection: Double,
    val ebbDirection: Double,
) {
    init {
        require(points.isNotEmpty() && floodDirection.isFinite() && ebbDirection.isFinite())
        require(points.all { it.speedKnots.isFinite() && it.time.nano % 1_000_000 == 0 })
        require(points.zipWithNext().all { (a, b) -> a.time < b.time })
    }

    val span get() = TimeSpan(points.first().time, points.last().time)

    internal fun record() = JSONObject()
        .put("times", JSONArray(points.map { point ->
            if (point.time.nano == 0) point.time.epochSecond else point.time.toEpochMilli() / 1000.0
        }))
        .put("speeds", JSONArray(points.map { it.speedKnots }))
        .put("floodDirection", floodDirection)
        .put("ebbDirection", ebbDirection)
}

class OnlineWindowStore(private val models: ModelStore) {
    suspend fun coverage(id: StationId): List<TimeSpan> {
        val record = (models.read(id, ModelKind.ONLINE_WINDOW) as? StoredModel.Ready)?.record ?: return emptyList()
        return readableBlocks(record).map { it.span }
    }

    suspend fun covers(id: StationId, span: TimeSpan) = coverage(id).any { span.start in it && span.end in it }

    suspend fun save(id: StationId, block: OnlineWindowBlock, today: Instant) {
        val cutoff = minOf(today.minus(Duration.ofDays(60)), block.span.start)
        models.update(id, ModelKind.ONLINE_WINDOW) { previous ->
            val record = when (previous) {
                is StoredModel.Ready -> previous.record
                StoredModel.Missing -> JSONObject()
                is StoredModel.Unavailable -> {
                    if (previous.reason != StoredModel.Unavailable.Reason.CORRUPT) {
                        throw IOException("Cannot update online window: ${previous.reason}")
                    }
                    JSONObject()
                }
            }
            val existing = if (previous is StoredModel.Ready) readableBlocks(record) else emptyList()
            val saved = normalize(existing + block, cutoff)
            for (key in listOf("times", "speeds", "floodDirection", "ebbDirection")) record.remove(key)
            record.put("blocks", JSONArray(saved.map { it.record() }))
        }
    }

    companion object {
        private fun readableBlocks(record: JSONObject): List<OnlineWindowBlock> = try {
            blocks(record)
        } catch (_: JSONException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: java.time.DateTimeException) {
            emptyList()
        }

        internal fun blocks(record: JSONObject): List<OnlineWindowBlock> {
            val rows = if (record.has("blocks")) record.getJSONArray("blocks") else JSONArray().put(record)
            val decoded = (0 until rows.length()).mapNotNull { i ->
                try {
                    decodeBlock(rows.getJSONObject(i))
                } catch (_: JSONException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: ArithmeticException) {
                    null
                }
            }
            require(decoded.isNotEmpty() || rows.length() == 0)
            return normalize(decoded)
        }

        private fun decodeBlock(row: JSONObject): OnlineWindowBlock {
            val times = row.getJSONArray("times")
            val speeds = row.getJSONArray("speeds")
            require(times.length() == speeds.length())
            val points = (0 until times.length()).map { n ->
                val value = (times.get(n) as? Number ?: throw JSONException("Expected numeric sample time")).toString()
                val millis = value.toBigDecimal().movePointRight(3).longValueExact()
                CurrentPoint(Instant.ofEpochMilli(millis), speeds.getDouble(n))
            }
            return OnlineWindowBlock(points, row.getDouble("floodDirection"), row.getDouble("ebbDirection"))
        }

        // Later entries win overlapping timestamps and supply the bearings for the joined trace.
        private fun normalize(blocks: List<OnlineWindowBlock>, cutoff: Instant = Instant.MIN): List<OnlineWindowBlock> {
            val samples = java.util.TreeMap<Instant, Pair<CurrentPoint, Int>>()
            blocks.forEachIndexed { index, block ->
                for (point in block.points) if (point.time >= cutoff) samples[point.time] = point to index
            }
            val result = mutableListOf<OnlineWindowBlock>()
            var points = mutableListOf<CurrentPoint>()
            var newest = -1
            for ((point, index) in samples.values) {
                val previous = points.lastOrNull()
                if (previous != null && Duration.between(previous.time, point.time) > Duration.ofMinutes(15)) {
                    result += blocks[newest].copy(points = points)
                    points = mutableListOf()
                    newest = -1
                }
                points += point
                newest = maxOf(newest, index)
            }
            if (points.isNotEmpty()) result += blocks[newest].copy(points = points)
            return result
        }
    }
}
