package io.openwaters.slackwater.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationSeries
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ListPreferencesStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun <T> withScope(block: suspend (CoroutineScope) -> T): T {
        val job = SupervisorJob()
        return try {
            block(CoroutineScope(job + Dispatchers.IO))
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun withFile(block: suspend (File) -> Unit) = runBlocking {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        try { block(file) } finally { file.delete() }
    }

    @Test fun filterAndAskSurviveReopen() = withFile { file ->
        withScope { scope ->
            val store = ListPreferencesStore.open(file, scope)
            assertEquals(ListPreferences(), store.preferences.first().getOrThrow())
            store.setSeriesFilter(StationSeries.Current)
            store.markLocationAsked()
        }
        withScope { scope ->
            val store = ListPreferencesStore.open(file, scope)
            assertEquals(ListPreferences(StationSeries.Current, locationAsked = true, tourSeenStored = true), store.preferences.first().getOrThrow())
            // Tapping the active chip returns to All.
            store.setSeriesFilter(null)
            store.setSeriesFilter(StationSeries.Tide)
            assertEquals(StationSeries.Tide, store.preferences.first().getOrThrow().seriesFilter)
            store.setSeriesFilter(null)
            assertEquals(ListPreferences(locationAsked = true, tourSeenStored = true), store.preferences.first().getOrThrow())
        }
    }

    @Test fun anInstallFromBeforeTheTourHasSeenItAndAFirstRunKeepsItUnseen() = withFile { file ->
        withScope { scope ->
            // An install that asked for location before the tour existed has no tour flag at all.
            PreferenceDataStoreFactory.create(scope = scope) { file }.edit { it[booleanPreferencesKey("locationAsked")] = true }
        }
        withScope { scope ->
            val older = ListPreferencesStore.open(file, scope)
            assertEquals(ListPreferences(locationAsked = true, tourSeen = true), older.preferences.first().getOrThrow())
        }
        file.delete()
        withScope { scope ->
            val fresh = ListPreferencesStore.open(file, scope)
            // This build's first ask records the tour as still to be seen, whichever write lands first.
            fresh.markLocationAsked()
            fresh.markTourUnseen()
            val read = fresh.preferences.first().getOrThrow()
            assertTrue(read.locationAsked && !read.tourSeen && read.tourSeenStored)
            fresh.markTourSeen()
            // Recording a tour still to be seen never undoes one already seen.
            fresh.markTourUnseen()
            assertTrue(fresh.preferences.first().getOrThrow().tourSeen)
        }
    }

    @Test fun anUnknownFilterReadsAsAll() = withFile { file ->
        withScope { scope ->
            PreferenceDataStoreFactory.create(scope = scope) { file }.edit { it[stringPreferencesKey("seriesFilter")] = "rivers" }
        }
        withScope { scope ->
            assertNull(ListPreferencesStore.open(file, scope).preferences.first().getOrThrow().seriesFilter)
        }
    }

    @Test fun aCorruptFileIsReplaced() = withFile { file ->
        file.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37))
        withScope { scope ->
            val store = ListPreferencesStore.open(file, scope)
            assertEquals(ListPreferences(), store.preferences.first().getOrThrow())
            store.markLocationAsked()
            assertTrue(store.preferences.first().getOrThrow().locationAsked)
        }
    }
}
