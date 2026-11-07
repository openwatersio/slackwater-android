package io.openwaters.slackwater.android.core.map

import com.google.gson.JsonParser
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.EngineAdapter
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Run with :core:nationalTideDirectionSweep; the full catalog takes longer than the regular suite. */
class NationalTideDirectionSweep {
    @Test fun hybridCoverageAndNearTurnLag() {
        val rows = File(System.getProperty("mapTideRecords")).reader().use { reader ->
            JsonParser.parseReader(reader).asJsonArray.map { it.asJsonObject }
        }
        val harmonics = rows.filterNot { it.has("reference") }.associate { row ->
            val constants = row["constituents"].asJsonArray.map { entry ->
                val c = entry.asJsonObject
                HarmonicConstituent(c["name"].asString, c["amplitude"].asDouble, c["phase"].asDouble)
            }
            row["id"].asString to (Station(constants, row["datumOffset"].asDouble) to
                tideRangeProxy(constants.associate { it.name to it.amplitude }))
        }
        val base = Instant.ofEpochSecond(1_785_000_000)
        val times = listOf(0 to 0, 7 to 11, 13 to 23, 20 to 37, 27 to 49).flatMap { (day, minute) ->
            listOf(0, 6, 12, 18).map { hour -> base.plusSeconds(day * 86400L + hour * 3600L + minute * 60L) }
        }
        var resolved = 0
        var nearTurn = 0
        val mismatches = mutableListOf<String>()
        for (row in rows) {
            val id = row["id"].asString
            val harmonic = harmonics[id]
            val heights: (Instant, Instant, Double) -> List<TidePoint>
            val extremes: (Instant, Instant) -> List<TideExtreme>
            if (harmonic != null) {
                heights = harmonic.first::heights
                extremes = harmonic.first::extremes
            } else {
                val offsets = row["offsets"].asJsonObject
                val time = offsets["time"].asJsonObject
                val h = offsets["height"].asJsonObject
                val hi = h["high"].asDouble
                val lo = h["low"].asDouble
                val height = if (h["type"].asString == "ratio") HeightOffset.Ratio(hi, lo) else HeightOffset.Fixed(hi, lo)
                val sub = EngineAdapter.subordinateTide(harmonics.getValue(row["reference"].asString).first,
                    time["high"].asInt, time["low"].asInt, height)
                heights = sub::heights
                extremes = sub::extremes
            }
            for (now in times) {
                val end = now.plusSeconds(108000)
                val turns = extremes(now, end)
                val expected = if (turns.firstOrNull { it.time > now }?.kind == ExtremeKind.LOW) "falling" else "rising"
                val pin = tidePinState(now, false, harmonic?.second ?: 0.0, heights, { a, b ->
                    if (a == now && b == end) turns else extremes(a, b)
                }, Double::toString)
                if (pin.state == "unknown") continue
                resolved++
                if (pin.state != expected) {
                    if (extremes(now.minusSeconds(1800), now.plusSeconds(1800)).isNotEmpty()) nearTurn++
                    else mismatches += "$id at $now: ${pin.state}, expected $expected"
                }
            }
        }
        val checked = rows.size * times.size
        println("Resolved $resolved/$checked, near-turn lag $nearTurn, away-from-turn mismatches ${mismatches.size}")
        assertTrue(resolved > checked * 0.99)
        assertTrue(mismatches.take(10).toString(), mismatches.isEmpty())
        assertTrue(nearTurn < resolved * 0.01)
    }
}
