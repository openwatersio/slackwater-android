package io.openwaters.slackwater.android.alerts

import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.openwaters.slackwater.android.core.planning.ReminderEntitlement
import io.openwaters.slackwater.android.data.alertInputLock
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReminderAccessStoreTest {
    @get:Rule val directory = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    private val start = Instant.parse("2026-11-05T16:15:00Z")
    private val end = start.plusSeconds(86_400)
    private val utc = ZoneOffset.UTC

    private suspend fun openStore(
        convert: suspend () -> Unit = {},
        action: suspend (ReminderAccessStore, DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
                File(directory.root, "access.preferences_pb")
            }
            action(ReminderAccessStore(data, convert), data)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun simultaneousStartsAreClaimedOnceAndExpirySurvivesRestart() = runBlocking {
        openStore { store, _ ->
            val starts = List(12) { async(Dispatchers.IO) { store.startFreeDay(start, utc) } }
            assertEquals(1, starts.awaitAll().count { it })
        }
        openStore { store, _ ->
            val entitlement = store.current()
            assertFalse(entitlement.activeAt(start.minusNanos(1)))
            assertTrue(entitlement.activeAt(start))
            assertTrue(entitlement.activeAt(end.minusNanos(1)))
            assertFalse(entitlement.activeAt(end))
            assertFalse(store.startFreeDay(end, utc))
            assertTrue(store.startFreeDay(Instant.parse("2026-12-01T00:00:00Z"), utc))
        }
    }

    @Test fun legacyExpirySurvivesPurchaseAndLapse() = runBlocking {
        openStore { store, data ->
            data.edit { it[stringPreferencesKey("freeDayEnd")] = end.toString() }
            assertEquals(start, store.freeDayRecord.first().started)
            store.syncPurchase(true)
            assertEquals(ReminderEntitlement.Purchased, store.current())
            assertFalse(store.startFreeDay(start, utc))
            store.syncPurchase(false)
            assertTrue(store.current().activeAt(start))
            assertFalse(store.startFreeDay(end, utc))
        }
    }

    @Test fun claimIsReadableByOlderBuildsAndFromEitherSurvivingTimestamp() = runBlocking {
        openStore { store, data ->
            assertTrue(store.startFreeDay(start, utc))
            val startKey = stringPreferencesKey("freeDayStart")
            val expiryKey = stringPreferencesKey("freeDayEnd")
            assertEquals(end, Instant.parse(data.data.first()[expiryKey]))
            data.edit { it[startKey] = "invalid" }
            assertEquals(start, store.freeDayRecord.first().started)
            data.edit {
                it[startKey] = start.toString()
                it[booleanPreferencesKey(expiryKey.name)] = true
            }
            assertEquals(start, store.freeDayRecord.first().started)
        }
    }

    @Test fun unusableClaimHistoryBlocksOnlyTrialAccessAndSurvivesPurchaseSync() = runBlocking {
        openStore { store, data ->
            data.edit {
                it[intPreferencesKey("freeDayStart")] = 123
                it[stringPreferencesKey("freeDayEnd")] = "invalid"
            }
            assertEquals(ReminderEntitlement.Free, store.current())
            assertFalse(store.startFreeDay(start, utc))
            assertFalse(store.freeDayRecord.first().historyReadable)
            store.syncPurchase(true)
            assertEquals(ReminderEntitlement.Purchased, store.current())
            assertTrue(store.freeDayRecord.first().purchased)
            store.syncPurchase(false)
            assertEquals(ReminderEntitlement.Free, store.current())
            assertFalse(store.startFreeDay(start, utc))
            assertEquals(123, data.data.first()[intPreferencesKey("freeDayStart")])
        }
    }

    @Test fun failedCalendarPromotionRetainsItsMarkerAcrossLapseAndRestart() = runBlocking {
        openStore(convert = { throw IOException("interrupted calendar write") }) { store, _ ->
            assertTrue(store.startFreeDay(start, utc))
            assertTrue(runCatching { store.syncPurchase(true) }.exceptionOrNull() is IOException)
            assertEquals(ReminderEntitlement.Purchased, store.current())
            assertTrue(runCatching { store.syncPurchase(false) }.exceptionOrNull() is IOException)
            assertFalse(store.freeDayRecord.first().purchased)
            assertFalse(store.current().activeAt(end))
        }
        var conversions = 0
        openStore(convert = { conversions++ }) { store, _ ->
            assertFalse(store.freeDayRecord.first().purchased)
            alertInputLock.withLock {
                store.finishPendingCalendarPurchase()
                store.finishPendingCalendarPurchase()
            }
            assertEquals(1, conversions)
            assertEquals(start, store.freeDayRecord.first().started)
            assertFalse(store.current().activeAt(end))
        }
    }
}
