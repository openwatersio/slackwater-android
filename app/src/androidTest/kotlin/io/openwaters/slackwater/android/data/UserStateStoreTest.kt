package io.openwaters.slackwater.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.*
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
        try { block(file) } finally {
            file.delete()
            File(file.parentFile, file.nameWithoutExtension + ".downloads.preferences_pb").delete()
        }
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

    @Test fun namesakeChoicesFollowIdsAcrossRenameAndSuccessorOnReopen() = withFile { file ->
        fun station(id: String, name: String, kind: StationKind) =
            StationIdentity(StationId(id), name, "", 0.0, 0.0, kind)
        val near = station("noaa/near", "Bay", StationKind.Tide)
        val far = station("chs-far", "Bay", StationKind.CanadianTide)
        val gate = station("chs-gate", "Bay", StationKind.DerivedGate)
        val other = station("noaa/other", "Cove", StationKind.Tide)
        fun fixture(rows: List<StationIdentity>, successors: Map<StationId, StationSuccessor> = emptyMap()) =
            object : StationCatalog {
                val index = StationIdentityIndex(rows, successors)
                override suspend fun identities() = index.identities
                override suspend fun resolve(id: StationId) = index.resolve(id)
            }
        withScope { scope ->
            val store = UserStateStore.open(file, fixture(listOf(near, far, gate, other)), scope)
            val group = listOf(near, far)
            assertEquals(near, store.places.first().getOrThrow().preferredNearbyMember(group))
            store.chooseStation(near.id)
            store.chooseStation(gate.id)
            store.chooseStation(other.id)
            store.chooseStation(far.id)
            val places = store.places.first().getOrThrow()
            assertEquals(setOf(far.id, gate.id, other.id), places.chosenIds)
            assertEquals(far, places.preferredNearbyMember(group))
            assertTrue(places.favorites.isEmpty() && places.recents.isEmpty())
        }
        val moved = far.copy(id = StationId("noaa/replacement"), name = "Cove")
        withScope { scope ->
            val store = UserStateStore.open(file, fixture(listOf(near, moved, gate, other),
                mapOf(far.id to StationSuccessor(moved.id))), scope)
            val places = store.places.first().getOrThrow()
            assertEquals(setOf(moved.id, gate.id, other.id), places.chosenIds)
            assertEquals(other, places.preferredNearbyMember(listOf(other, moved)))
            assertEquals(moved, places.preferredNearbyMember(listOf(moved, other)))
            store.chooseStation(other.id)
            val reselected = store.places.first().getOrThrow()
            assertEquals(setOf(other.id, gate.id), reselected.chosenIds)
            assertEquals(other, reselected.preferredNearbyMember(listOf(moved, other)))
        }
    }

    @Test fun downloadIntentSurvivesReopenAndScopeChanges() = withFile { file ->
        val former = StationId("noaa/8218617")
        val millBay = StationId("chs-mill-bay")
        val other = StationId("chs-fulford-harbour")
        withScope { scope ->
            val raw = PreferenceDataStoreFactory.create(scope = scope) { File(file.parentFile, file.nameWithoutExtension + ".downloads.preferences_pb") }
            raw.edit {
                it[androidx.datastore.preferences.core.stringSetPreferencesKey("requested")] = setOf(former.value)
                it[androidx.datastore.preferences.core.stringSetPreferencesKey("cancelled")] = setOf(millBay.value, other.value)
            }
        }
        withScope { scope ->
            val store = UserStateStore.open(file, catalog, scope).downloads
            val migrated = store.state.first().getOrThrow()
            assertTrue(migrated.previewRequested.isEmpty())
            assertEquals(setOf(millBay), migrated.requested)
            assertEquals(setOf(millBay, other), migrated.cancelled)
            assertTrue(migrated.allowsRestore(millBay, ready = false))
            assertFalse(migrated.allowsRestore(other, ready = false))
            assertTrue(migrated.allowsRestore(other, ready = true))
            store.requestPreviews(listOf(former))
            store.requestDownloads(listOf(former))
            store.trimDownloads(listOf(millBay, other))
        }
        withScope { scope ->
            val store = UserStateStore.open(file, catalog, scope).downloads
            assertEquals(DownloadIntent(setOf(millBay), setOf(other), setOf(millBay)), store.state.first().getOrThrow())
            store.resumeAutomaticDownloads(listOf(other))
            store.releaseDownloadRequests(listOf(former))
            assertEquals(setOf(millBay), store.state.first().getOrThrow().previewRequested)
            store.releasePreviews(listOf(former))
            assertEquals(DownloadIntent(), store.state.first().getOrThrow())
        }
    }

    @Test fun unsupportedDownloadFilePreservesIntentAndLeavesFavoritesUsable() = withFile { file ->
        val intentFile = File(file.parentFile, file.nameWithoutExtension + ".downloads.preferences_pb")
        withScope { scope ->
            PreferenceDataStoreFactory.create(scope = scope) { intentFile }.edit { it[intPreferencesKey("version")] = 2 }
        }
        val before = intentFile.readBytes()
        withScope { scope ->
            val store = UserStateStore.open(file, catalog, scope)
            assertTrue(store.downloads.state.first().exceptionOrNull() is IOException)
            try {
                store.downloads.requestDownloads(listOf(StationId("chs-mill-bay")))
                fail("Expected unsupported version to prevent write")
            } catch (_: IOException) { }
            store.setFavorite(StationId("chs-mill-bay"), true)
            assertEquals(listOf(StationId("chs-mill-bay")), store.places.first().getOrThrow().favorites)
        }
        assertArrayEquals(before, intentFile.readBytes())
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
