package io.openwaters.slackwater.android.data

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test

class BundledStationCatalogTest {
    @Test fun resolvesSavedPlacesFromPackagedIdentities() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val catalog = BundledStationCatalog(assets)
        suspend fun resolve(id: String) = catalog.resolve(StationId(id))
        val old = StationId("noaa/8218617")
        val replacement = StationId("chs-mill-bay")
        val retired = StationId("chs-aulatsivik-point")
        val unknown = StationId("missing")
        assertEquals(replacement, (catalog.resolve(old) as StationResolution.Successor).target.id)
        assertEquals(StationKind.Current, (resolve("current:noaa/CHB9904") as StationResolution.Live).identity.kind)
        assertTrue(catalog.resolve(retired) is StationResolution.Tombstone)
        val restricted = resolve("ticon/aigio-aig-grc-cmems") as StationResolution.Restricted
        assertEquals("cc-by-nc-4.0", restricted.identity.license)
        val indexIds = catalog.identities().map { it.id }.toSet()
        val successors = assets.open("slackwater/station-successors.json").bufferedReader().use { JSONObject(it.readText()) }
        for (former in successors.keys()) {
            assertFalse(StationId(former) in indexIds)
            val entry = successors.getJSONObject(former)
            val moved = resolve(former) as StationResolution.Successor
            assertEquals(StationId(entry.getString("id")), moved.target.id)
            assertEquals(if (entry.isNull("datumShiftM")) null else entry.getDouble("datumShiftM"), moved.datumShiftMetres)
        }
        val saved = listOf(old, retired, replacement, unknown)
        val migrated = catalog.remapSavedIds(saved)
        assertEquals(listOf(replacement, retired, unknown), migrated)
        assertEquals(migrated, catalog.remapSavedIds(migrated))
    }
}
