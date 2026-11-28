package io.openwaters.slackwater.android.data

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.database.Station
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BundledCurrentRecordsTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test fun indexedJsonAgreesWithTheBinaryCurrentDatabase() {
        assertBundledCurrentInputsAgree(assets)
    }

    @Test fun predictsBothCurrentAxesLikeTheTypeScriptEngine() {
        val at = Instant.parse("2026-12-19T12:00:00Z")
        val station = requireNotNull(BundledCurrentRecords(assets).harmonic("noaa/CHB9904"))
        val point = station.speeds(at, at, 1.0).single()
        // Slackwater's TypeScript engine at the pinned revision, with the bundled station inputs.
        assertEquals(-0.21295381993412227, point.speedKnots, 1e-6)
        assertEquals(0.21338205337380495, point.magnitudeKnots, 1e-6)
        assertEquals(157.26945660434467, requireNotNull(point.directionDegrees), 1e-5)
    }

    @Test fun subordinateCurrentMatchesTheTypeScriptReferenceReduction() {
        val at = Instant.parse("2026-12-19T07:40:00Z")
        val records = BundledCurrentRecords(assets)
        val row = requireNotNull(records["noaa/PCT3896"])
        val reference = requireNotNull(records.harmonic(row.getString("reference")))
        val station = row.subordinateCurrent(reference)
        // The engines find reference events separately before interpolating the subordinate speed.
        assertEquals(-0.5131688336090767, station.speeds(at, at, 1.0).single().speedKnots, 2e-4)
    }
}

internal fun assertBundledCurrentInputsAgree(assets: AssetManager) {
    val database = assets.openStationDatabase("currents.tcdb")
    val records = BundledCurrentRecords(assets)
    val json = assets.open("slackwater/currents.json").bufferedReader().use { JSONArray(it.readText()) }
    val ids = (0 until json.length()).map { json.getJSONObject(it).getString("id") }
    assertEquals(database.size, ids.size)
    assertEquals(database.map { it.id }.toSet(), ids.toSet())
    val subordinatesByReference = mutableMapOf<String, MutableList<JSONObject>>()
    for ((i, id) in ids.withIndex()) {
        val binary = requireNotNull(database.station(id))
        val current = requireNotNull(binary.current)
        val row = requireNotNull(records[id])
        assertEquals("$id indexed row", json.getJSONObject(i).toString(), row.toString())
        assertEquals("$id major axis", row.terms("constituents"), binary.constituents.atCatalogPrecision())
        assertEquals("$id minor axis", row.terms("minorConstituents"), current.minorConstituents.orEmpty().atCatalogPrecision())
        assertEquals("$id mean flow", row.number("meanFlow"), current.meanFlow?.catalogPrecision())
        assertEquals("$id minor mean flow", row.number("minorMeanFlow"), current.minorMeanFlow?.catalogPrecision())
        assertEquals("$id flood direction", row.number("floodDirection"), current.floodDirection?.catalogPrecision())
        assertEquals("$id ebb direction", row.number("ebbDirection"), current.ebbDirection?.catalogPrecision())
        assertEquals("$id azimuth", row.number("azimuth"), current.azimuth?.catalogPrecision())
        assertEquals("$id tide reference", row.optString("tideReference"), current.tideReference.orEmpty())
        val offsets = current.offsets
        assertEquals("$id reference", row.optString("reference"), offsets?.reference.orEmpty())
        assertEquals("$id slack before flood", row.integer("slackBeforeFloodOffset"), offsets?.slackBeforeFlood?.times(60))
        assertEquals("$id slack before ebb", row.integer("slackBeforeEbbOffset"), offsets?.slackBeforeEbb?.times(60))
        assertEquals("$id flood time", row.integer("floodTimeOffset"), offsets?.floodTime?.times(60))
        assertEquals("$id ebb time", row.integer("ebbTimeOffset"), offsets?.ebbTime?.times(60))
        assertEquals("$id flood speed ratio", row.number("floodSpeedRatio"), offsets?.floodSpeedRatio?.catalogPrecision())
        assertEquals("$id ebb speed ratio", row.number("ebbSpeedRatio"), offsets?.ebbSpeedRatio?.catalogPrecision())
        val subordinates = subordinatesByReference.getOrPut(offsets?.reference ?: id) { mutableListOf() }
        if (offsets != null) subordinates += row
    }
    for ((id, subordinates) in subordinatesByReference) {
        val harmonic = requireNotNull(records.harmonic(id)) { "Missing harmonic current reference $id" }
        for (row in subordinates) row.subordinateCurrent(harmonic)
    }
}

private fun JSONObject.number(key: String): Double? = if (isNull(key)) null else getDouble(key)
private fun JSONObject.integer(key: String): Int? = if (isNull(key)) null else getInt(key)

private fun JSONObject.terms(key: String): List<Station.Constituent> {
    val terms = optJSONArray(key) ?: return emptyList()
    return (0 until terms.length()).map { i ->
        val term = terms.getJSONObject(i)
        Station.Constituent(term.getString("name"), term.getDouble("amplitude"), term.getDouble("phase"))
    }
}

private fun List<Station.Constituent>.atCatalogPrecision(): List<Station.Constituent> = map { term ->
    term.copy(amplitude = term.amplitude.catalogPrecision(), phase = term.phase.catalogPrecision())
}

// The JSON stores three decimal places and the FlatBuffer stores float32.
private fun Double.catalogPrecision(): Double = Math.round(this * 1000.0) / 1000.0
