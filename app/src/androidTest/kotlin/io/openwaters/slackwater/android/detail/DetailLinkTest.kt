package io.openwaters.slackwater.android.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DetailLinkTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shareAtNowAndShareAMomentUseThePublishedStationAndItsOffset() = kotlinx.coroutines.runBlocking {
        val path = "https://slackwater.xyz/tides/friday-harbor"
        val event = Instant.parse("2026-11-06T16:00:37Z")
        val records = DetailRecords(context.assets)
        assertEquals(path, detailShareLink(path, null, event, ZoneId.of("America/Los_Angeles")))
        assertEquals(path, detailShareLink(path, event, event.plusSeconds(200), ZoneId.of("UTC")))
        val link = detailShareLink(path, event, event.minusSeconds(201), ZoneId.of("America/Los_Angeles"))
        assertEquals("$path/2026-11-06T08:00-08:00", link)
        assertEquals(StationId("noaa/9449880") to Instant.parse("2026-11-06T16:00:00Z"), records.destination(Uri.parse(link)))
        assertNull(records.destination(Uri.parse("$path/not-a-time")))
        assertEquals(StationId("kartverket/AES") to null, records.destination(Uri.parse("https://slackwater.xyz/tides/aalesund")))
        assertEquals(StationId("chs-malibu-rapids") to null, records.destination(Uri.parse("https://slackwater.xyz/currents/malibu-rapids")))
        assertEquals(StationId("current:noaa/CHB9904") to null, records.destination(Uri.parse("https://slackwater.xyz/currents/0-15-nm-wsw-of-pier-no-2")))
    }

    @Test fun internalLinksResolveEveryBundledIdentity() = kotlinx.coroutines.runBlocking {
        val catalog = io.openwaters.slackwater.android.data.BundledStationCatalog(context.assets)
        val records = DetailRecords(context.assets)
        for (station in catalog.identities()) {
            assertEquals(catalog.resolve(station.id).id to null, records.destination(internalStationLink(station.id)))
        }
        fun asset(name: String) = context.assets.open("slackwater/$name.json").bufferedReader().use { it.readText() }
        for (name in listOf("station-tombstones", "chs-tombstones", "unavailable-stations")) {
            val rows = org.json.JSONArray(asset(name))
            for (i in 0 until rows.length()) {
                val id = StationId(rows.getJSONObject(i).getString("id"))
                assertEquals(catalog.resolve(id).id to null, records.destination(internalStationLink(id)))
            }
        }
        val successors = org.json.JSONObject(asset("station-successors"))
        for (key in successors.keys()) {
            val id = StationId(key)
            assertEquals(catalog.resolve(id).id to null, records.destination(internalStationLink(id)))
        }
        assertEquals("slackwater://station/current%3Anoaa%2FCHB9904",
            internalStationLink(StationId("current:noaa/CHB9904")).toString())
        assertEquals(StationId("chs-mill-bay") to null, records.destination(internalStationLink(StationId("noaa/8218617"))))
        assertEquals(StationId("chs-aulatsivik-point") to null, records.destination(internalStationLink(StationId("chs-aulatsivik-point"))))
        assertNull(records.destination(internalStationLink(StationId("no-such-station"))))
        assertNull(records.destination(Uri.parse("slackwater://station/")))
        assertNull(records.destination(Uri.parse("slackwater://other/noaa/9449880")))
    }

    @Test fun implicitStationLinkOpensAndAnUnknownInternalIdLeavesItInPlace() {
        val uri = internalStationLink(StationId("noaa/9449880"))
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(context.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { activity ->
                val next = Intent(Intent.ACTION_VIEW, internalStationLink(StationId("noaa/9447130")))
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, next)
                val unknown = Intent(Intent.ACTION_VIEW, internalStationLink(StationId("unknown")))
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, unknown)
            }
            compose.waitUntil(10_000) {
                var settled = false
                scenario.onActivity { settled = !it.linkPending }
                settled
            }
            compose.onNodeWithText("This station link could not be opened.").assertDoesNotExist()
            scenario.onActivity {
                assertEquals(internalStationLink(StationId("noaa/9447130")), it.intent.data)
                // ActivityScenario identifies its activity by the original launch intent during teardown.
                it.intent = intent
            }
        }
    }

    @Test fun datedLinkLaunchesTheStationAtItsLocalTime() {
        val uri = Uri.parse("https://slackwater.xyz/tides/friday-harbor/2026-11-06T08:00-08:00")
        ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)).use {
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Friday Harbor").assertIsDisplayed()
            val value = compose.onNodeWithTag("detailTimeline").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue("Expected station-local 8 AM for a 16:00 UTC link, got $value", value.contains("8:00"))
            it.onActivity { activity ->
                val bad = Intent(Intent.ACTION_VIEW, Uri.parse("https://slackwater.xyz/tides/unknown-station"), context, MainActivity::class.java)
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, bad)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("This station link could not be opened.").fetchSemanticsNodes().isNotEmpty() }
            it.onActivity { activity -> assertEquals(uri, activity.intent.data) }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Friday Harbor").assertIsDisplayed()
        }
    }
}
