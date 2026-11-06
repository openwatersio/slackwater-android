package io.openwaters.slackwater.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.station.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AlertRuleStoreTest {
    private suspend fun withStoreScope(block: suspend (CoroutineScope) -> Unit) {
        val job = SupervisorJob()
        try { block(CoroutineScope(job + Dispatchers.IO)) } finally { job.cancelAndJoin() }
    }

    @Test fun migratesKnownRulesAndKeepsOpaqueRecordsAcrossEditsAndExpiry() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(app.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val key = stringPreferencesKey("alertRules")
        val old = StationId("station/old")
        val identity = StationIdentity(StationId("station/new"), "New", "", 0.0, 0.0, StationKind.Tide)
        val catalog = object : StationCatalog {
            override suspend fun identities() = listOf(identity)
            override suspend fun resolve(id: StationId) = when (id) {
                old -> StationResolution.Successor(old, StationResolution.Live(identity), null)
                else -> StationResolution.Live(identity)
            }
        }
        val id = UUID.randomUUID()
        val known = """{"version":1,"id":"$id","stationID":"station/old","trigger":"tideCrossing","heightM":2,"rising":true,"lead":0,"enabled":true,"daylightOnly":true}"""
        val unknown = known.replace("\"heightM\":2", "\"future\": [ 2.000, \"\\u00E9\" ], \"heightM\":2")
        val now = Instant.parse("2026-07-01T12:00:00Z")
        try {
            withStoreScope { scope ->
                val raw = PreferenceDataStoreFactory.create(scope = scope) { file }
                raw.edit { it[key] = JSONArray(listOf(known, unknown)).toString() }
            }
            withStoreScope { scope ->
                val store = AlertRuleStore.open(file, catalog, scope)
                val moved = store.rules.first().single()
                assertEquals(identity.id, moved.stationId)
                assertEquals(SunWindow.DAYLIGHT, moved.sun)
                assertTrue(moved.heightNeedsReview)
                assertFalse(moved.enabled)
                val edited = moved.copy(once = now, enabled = true, heightNeedsReview = false)
                store.upsert(edited)
                assertTrue(store.rules.first().single().heightNeedsReview)
                assertFalse(store.rules.first().single().enabled)
                store.confirmHeight(edited, heightM = 1.1)
                store.expire(now)
                assertFalse(store.rules.first().single().heightNeedsReview)
            }
            withStoreScope { scope ->
                val store = AlertRuleStore.open(file, catalog, scope)
                assertEquals(AlertTrigger.TideCrossing(1.1, true), store.rules.first().single().trigger)
                assertTrue(store.rules.first().single().enabled)
                store.expire(now.plusNanos(1))
                assertTrue(store.rules.first().isEmpty())
                store.remove(id)
            }
            withStoreScope { scope ->
                val raw = PreferenceDataStoreFactory.create(scope = scope) { file }
                val stored = JSONArray(raw.data.first()[key]!!)
                assertEquals(1, stored.length())
                assertArrayEquals(unknown.toByteArray(), stored.getString(0).toByteArray())
            }
        } finally { file.delete() }
    }
}
