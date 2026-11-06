package io.openwaters.slackwater.android.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

    @Test fun shareAtNowAndShareAMomentUseThePublishedStationAndItsOffset() {
        val path = "https://slackwater.xyz/tides/friday-harbor"
        val event = Instant.parse("2026-11-06T16:00:37Z")
        val records = DetailRecords(context.assets)
        assertEquals(path, detailShareLink(path, null, ZoneId.of("America/Los_Angeles")))
        val link = detailShareLink(path, event, ZoneId.of("America/Los_Angeles"))
        assertEquals("$path/2026-11-06T08:00-08:00", link)
        assertEquals(StationId("noaa/9449880") to Instant.parse("2026-11-06T16:00:00Z"), records.destination(Uri.parse(link)))
        assertNull(records.destination(Uri.parse("$path/not-a-time")))
        assertEquals(StationId("kartverket/AES") to null, records.destination(Uri.parse("https://slackwater.xyz/tides/aalesund")))
        assertEquals(StationId("chs-malibu-rapids") to null, records.destination(Uri.parse("https://slackwater.xyz/currents/malibu-rapids")))
        assertEquals(StationId("current:noaa/CHB9904") to null, records.destination(Uri.parse("https://slackwater.xyz/currents/0-15-nm-wsw-of-pier-no-2")))
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
