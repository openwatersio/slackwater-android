package io.openwaters.slackwater.android.localization

import android.content.res.Configuration
import android.app.NotificationManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.openwaters.slackwater.android.alerts.AlarmScheduler
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.*
import io.openwaters.slackwater.android.detail.StationDetailScreen
import io.openwaters.slackwater.android.stations.StationListScreen
import io.openwaters.slackwater.android.stations.StationListModel
import java.io.File
import java.time.Instant
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LocalizationTest(private val tag: String, private val favorites: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun locales() = listOf(
            arrayOf("en", "Favorites"), arrayOf("da", "Favoritter"), arrayOf("de", "Favoriten"),
            arrayOf("es-ES", "Favoritos"), arrayOf("fi", "Suosikit"), arrayOf("fr-CA", "Favoris"),
            arrayOf("ga", "Ceanáin"), arrayOf("gd", "Annsachdan"), arrayOf("it", "Preferiti"),
            arrayOf("ja", "お気に入り"), arrayOf("ko", "즐겨찾기"), arrayOf("nb", "Favoritter"),
            arrayOf("nl", "Favorieten"), arrayOf("pt-BR", "Favoritos"), arrayOf("pt-PT", "Favoritos"),
            arrayOf("sv", "Favoriter"),
        )
    }
    @get:Rule val compose = createComposeRule()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val previous = Locale.getDefault()
    private val locale = Locale.forLanguageTag(tag)
    private val configuration get() = Configuration(base.resources.configuration).apply {
        setLocale(this@LocalizationTest.locale)
        fontScale = 1.3f
    }
    private val context get() = base.createConfigurationContext(configuration)

    @Before fun setLocale() { Locale.setDefault(locale) }
    @After fun restoreLocale() { Locale.setDefault(previous) }

    @Test fun packagedResourcesSelectTheLocaleAndFormatTypedArguments() {
        val resources = context.resources
        assertEquals(favorites, context.getString(R.string.list_favorites))
        val scheduler = AlarmScheduler(context, context.getSharedPreferences("localization-alarms", 0))
        scheduler.access()
        assertEquals(context.getString(R.string.reminder_channel), context.getSystemService(NotificationManager::class.java).getNotificationChannel(AlarmScheduler.CHANNEL_ID).name.toString())
        if (tag == "fr-CA" || tag == "es-ES") {
            val regional = Configuration(configuration).apply { setLocale(Locale.forLanguageTag(if (tag == "fr-CA") "fr-FR" else "es-MX")) }
            assertEquals(favorites, base.createConfigurationContext(regional).getString(R.string.list_favorites))
        }
        if (tag != "en") assertNotEquals("Calendar changes are pending. Events already published can remain until Slackwater can update Calendar.", context.getString(R.string.station_calendar_pending))
        val title = context.getString(R.string.removed_gone, "Test Harbour")
        assertTrue(title, title.contains("Test Harbour"))
        val printed = context.getString(R.string.query_tide_m, "12:34", 1.25)
        assertTrue(printed, printed.contains("12:34"))
        assertFalse(printed, printed.contains("%"))
        for (count in listOf(0, 1, 2, 3, 7, 12, 100, 1000000)) {
            assertFalse(resources.getQuantityString(R.plurals.chart_hours, count, count).contains("%"))
            assertFalse(resources.getQuantityString(R.plurals.station_calendar_remove_count, count, count).contains("%"))
        }
        assertEquals(8, resources.getStringArray(R.array.panel_phases).size)
        assertEquals(16, resources.getStringArray(R.array.pdf_compass).size)
    }

    @Test fun translatedListRendersAtLargerFontScale() {
        val localized = context
        val config = configuration
        val now = Instant.parse("2026-11-06T18:00:00Z")
        compose.setContent {
            val owner = checkNotNull(LocalActivityResultRegistryOwner.current)
            val density = Density(LocalDensity.current.density, config.fontScale)
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalDensity provides density, LocalContext provides localized, LocalConfiguration provides config) {
                MaterialTheme { StationListScreen(StationListModel.get(localized), {}, {}, {}, now) }
            }
        }
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText(localized.getString(R.string.list_search)).fetchSemanticsNodes().isNotEmpty()
        }
        if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
            compose.waitUntil(60_000) { StationListModel.get(localized).state.value != null }
            compose.waitForIdle()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val dir = File(base.getExternalFilesDir(null), "localization-evidence").apply { mkdirs() }
            File(dir, "$tag-list.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
        }
    }

    @Test fun translatedDetailRendersAtLargerFontScale() {
        val localized = context
        val config = configuration
        val now = Instant.parse("2026-11-06T18:00:00Z")
        compose.setContent {
            val owner = checkNotNull(LocalActivityResultRegistryOwner.current)
            val density = Density(LocalDensity.current.density, config.fontScale)
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalDensity provides density, LocalContext provides localized, LocalConfiguration provides config) {
                MaterialTheme {
                    StationDetailScreen(StationListModel.get(localized), StationId("noaa/9449880"), now, now, {}, {})
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("detailTimeline").assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
            compose.waitUntil(60_000) {
                compose.mainClock.advanceTimeBy(64)
                compose.onAllNodesWithTag("detailSkyReady", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("detailCommentary", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val dir = File(base.getExternalFilesDir(null), "localization-evidence").apply { mkdirs() }
            File(dir, "$tag-detail.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
        }
    }
}
