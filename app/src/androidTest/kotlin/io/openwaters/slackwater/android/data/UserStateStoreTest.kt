package io.openwaters.slackwater.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationId
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class UserStateStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog = BundledStationCatalog(context.assets)

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

    @Test fun favoritesAndRecentsSurviveReopenAndMigrateInOrder() = withFile { file ->
        val old = StationId("noaa/8218617")
        val replacement = StationId("chs-mill-bay")
        val retired = StationId("chs-aulatsivik-point")
        val current = StationId("current:noaa/CHB9904")
        val extra = stringPreferencesKey("unrelatedSetting")
        withScope { scope ->
            val raw = PreferenceDataStoreFactory.create(scope = scope) { file }
            raw.edit {
                it[stringPreferencesKey("favorites")] = """["${old.value}","${retired.value}","${replacement.value}","${current.value}"]"""
                it[stringPreferencesKey("recents")] = """["${current.value}","${old.value}"]"""
                it[extra] = "preserve"
            }
        }
        withScope { scope ->
            val store = UserStateStore.open(file, catalog, scope)
            assertEquals(SavedPlaces(listOf(replacement, retired, current), listOf(current, replacement)), store.places.first().getOrThrow())
            store.forgetFavorite(retired)
            store.setFavorite(old, false)
        }
        withScope { scope ->
            val store = UserStateStore.open(file, catalog, scope)
            assertEquals(SavedPlaces(listOf(current), listOf(replacement, current)), store.places.first().getOrThrow())
            for (i in 1..8) store.recordVisit(StationId("unknown/$i"))
            store.recordVisit(StationId("unknown/4"))
            store.removeRecent(StationId("unknown/6"))
            assertEquals(listOf(4, 8, 7, 5, 3).map { StationId("unknown/$it") }, store.places.first().getOrThrow().recents)
        }
        withScope { scope ->
            val raw = PreferenceDataStoreFactory.create(scope = scope) { file }
            assertEquals("preserve", raw.data.first()[extra])
        }
    }

    @Test fun concurrentStarsAreRetainedAndReplacementKeepsItsSlot() = withFile { file ->
        withScope { scope ->
            val store = UserStateStore.open(file, catalog, scope)
            val ids = (1..12).map { StationId("current:noaa/A:$it") }
            coroutineScope { ids.forEach { launch { store.setFavorite(it, true) } } }
            val order = store.places.first().getOrThrow().favorites
            assertEquals(ids.toSet(), order.toSet())
            assertEquals(ids.size, order.size)
            val new = StationId("station/new")
            store.replaceFavorite(order[1], new)
            store.replaceFavorite(order[2], order[0])
            assertEquals(listOf(order[0], new) + order.drop(3), store.places.first().getOrThrow().favorites)
        }
    }

    @Test fun invalidSavedDataSurvivesFailedReadAndWrite() = withFile { file ->
        for (version in listOf(1, 2)) {
            withScope { scope ->
                PreferenceDataStoreFactory.create(scope = scope) { file }.edit {
                    it[intPreferencesKey("savedPlacesVersion")] = version
                    it[stringPreferencesKey("favorites")] = if (version == 1) "broken" else """["keep"]"""
                }
            }
            val before = file.readBytes()
            withScope { scope ->
                val store = UserStateStore.open(file, catalog, scope)
                assertTrue(store.places.first().exceptionOrNull() is IOException)
                try { store.recordVisit(StationId("new")); fail("Expected write failure") } catch (_: IOException) { }
            }
            assertArrayEquals(before, file.readBytes())
        }
    }
}
