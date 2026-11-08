package io.openwaters.slackwater.android.sharing

import io.openwaters.slackwater.android.BuildConfig
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.openStation
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StationScheduleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun freeScheduleShowsTheWeekAndChecksPremiumBeforeOpeningTheSheet() {
        val access = ReminderAccessStore.get(compose.activity.applicationContext)
        val previous = runBlocking { access.current() }
        try {
            runBlocking { access.update(ReminderEntitlement.Free) }
            compose.openStation("Friday Harbor", "San Juan Island")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("printable-tables-row").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Schedule").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Next week").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("printable-tables-row").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("printable-tables-row").performScrollTo().performClick()
            compose.onNodeWithText(if (BuildConfig.PREMIUM_ENABLED) "Slackwater Premium" else "Support Slackwater").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("printable-tables-row").assertExists()
        } finally {
            runBlocking { access.update(previous) }
        }
    }

    @Test fun publishedLinksAndPrintedProvenanceComeFromTheCatalog() = runBlocking {
        val context = compose.activity.applicationContext
        val catalog = BundledStationCatalog(context.assets)
        val source = StationTableSource(context.assets, AndroidPredictionRepository(context))
        val anchor = Instant.parse("2026-03-08T12:00:00Z")
        suspend fun table(id: String): PrintableTable {
            val place = (catalog.resolve(StationId(id)) as StationResolution.Live).identity
            return source.load(place, PrintSpan.WEEK, anchor, anchor)
        }
        val harbor = table("noaa/9449880")
        assertEquals("NOAA 9449880", harbor.source)
        assertEquals("MLLW", harbor.datum)
        assertEquals("https://slackwater.xyz/tides/friday-harbor", harbor.link)
        assertTrue(harbor.days.isNotEmpty())
        assertFalse(harbor.attribution.isNullOrBlank())
        val norwegian = table("kartverket/ANX")
        assertEquals("Kartverket / Norwegian Mapping Authority, Hydrographic Service ANX", norwegian.source)
        assertEquals("https://slackwater.xyz/tides/andenes", norwegian.link)
        val derived = table("chs-malibu-rapids")
        assertEquals("Point Atkinson", derived.source)
        assertEquals("https://slackwater.xyz/currents/malibu-rapids", derived.link)
        val chs = table("chs-point-atkinson")
        assertEquals("CHS", chs.source)
        assertEquals("LLWLT", chs.datum)
    }
}
