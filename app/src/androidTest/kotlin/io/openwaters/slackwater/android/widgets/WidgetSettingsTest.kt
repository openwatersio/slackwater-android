package io.openwaters.slackwater.android.widgets

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetSettingsTest {
    @Test fun concreteChoicesSurviveReadsAndDeleteIndependently() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = -110
        val second = -111
        try {
            saveWidgetChoice(context, first, "noaa/8218617")
            saveWidgetChoice(context, second, "current:noaa/CHB9904")
            assertEquals(StationId("chs-mill-bay"), widgetStation(context, first))
            deleteWidgetSettings(context, first)
            assertNull(widgetStation(context, first))
            assertEquals(StationId("current:noaa/CHB9904"), widgetStation(context, second))
        } finally {
            deleteWidgetSettings(context, first)
            deleteWidgetSettings(context, second)
        }
    }

    @Test fun permissionChangesPurgeCachedStationsAndLastRemovalClearsChoices() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = base.getSharedPreferences("widget-permission-test", Context.MODE_PRIVATE)
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = isolated
        }
        isolated.edit().clear().apply()
        try {
            saveWidgetChoice(context, 7, NearestStation.TIDE.key)
            saveWidgetChoice(context, 8, "concrete")
            assertTrue(hasNearestChoice(context, 7))
            assertFalse(hasNearestChoice(context, 8))
            for (mode in NearestStation.entries) {
                isolated.edit().putString("PRECISE:${mode.key}", "precise")
                    .putString("APPROXIMATE:${mode.key}", "coarse").apply()
            }
            retainWidgetPermission(isolated, LocationPermission.APPROXIMATE)
            for (mode in NearestStation.entries) {
                assertFalse(isolated.contains("PRECISE:${mode.key}"))
                assertTrue(isolated.contains("APPROXIMATE:${mode.key}"))
            }
            retainWidgetPermission(isolated, LocationPermission.NOT_GRANTED)
            assertEquals(setOf("widget:7", "widget:8"), isolated.all.keys)
            retainWidgetPermission(isolated, LocationPermission.PRECISE)
            assertFalse(isolated.contains("PRECISE:${NearestStation.TIDE.key}"))
            isolated.edit().putString("PRECISE:${NearestStation.TIDE.key}", "fresh").apply()
            clearWidgetSettings(context)
            assertTrue(isolated.all.isEmpty())
        } finally {
            base.deleteSharedPreferences("widget-permission-test")
        }
    }

    @Test fun missingWidgetStationOpensTheAppForLocationOrThePickerForConfiguration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = -112
        try {
            val tap = widgetTap(context, id, StationId("current:noaa/CHB9904"))
            assertEquals("slackwater://station/current%3Anoaa%2FCHB9904", tap.data.toString())
            assertEquals(android.content.Intent.ACTION_VIEW, tap.action)
            saveWidgetChoice(context, id, NearestStation.TIDE.key)
            assertEquals(io.openwaters.slackwater.android.MainActivity::class.java.name,
                widgetTap(context, id, null).component?.className)
            saveWidgetChoice(context, id, "unknown-station")
            assertNull(widgetStation(context, id))
            assertEquals(WidgetConfigurationActivity::class.java.name,
                widgetTap(context, id, null).component?.className)
        } finally {
            deleteWidgetSettings(context, id)
        }
    }

    @Test fun nearestChoicesSelectTheirSeriesAndRespectChosenNamesakes() {
        fun station(id: String, name: String, kind: StationKind, lon: Double) =
            StationIdentity(StationId(id), name, "", 0.0, lon, kind)
        val nearby = station("tide", "Bay", StationKind.Tide, 0.0)
        val chosen = station("namesake", "Bay", StationKind.Tide, 2.0)
        val current = station("current", "Bay", StationKind.Current, 1.0)
        val ids = nearestWidgetStations(listOf(nearby, current, chosen), Coordinate(0.0, 0.0), setOf(chosen.id))
        assertEquals(chosen.id, ids[NearestStation.ANY])
        assertEquals(chosen.id, ids[NearestStation.TIDE])
        assertEquals(current.id, ids[NearestStation.CURRENT])
    }
}
