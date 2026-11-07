package io.openwaters.slackwater.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.android.core.station.StationId
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class TidePreviewDownloadsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val station = StationId("chs-point-atkinson")
    private val now = Instant.parse("2026-11-07T00:00:00Z")
    private val points = listOf(TidePoint(now, 2.0), TidePoint(now.plusSeconds(300), 2.5))

    @Test fun transientIntentReadErrorDoesNotEndQueueObservation() = withQueue { worker, catalog, _, models ->
        val reads = AtomicInteger()
        val store = DownloadIntentStore(readOnlyData {
            if (reads.incrementAndGet() in setOf(1, 3)) throw java.io.IOException("temporary read error")
            preferencesOf(stringSetPreferencesKey("previewRequested") to setOf(station.value))
        }, catalog)
        val queue = TidePreviewDownloads(worker, store, models, catalog,
            MutableStateFlow(TideDownloadState.WAITING_FOR_NETWORK), AndroidPredictionRepository(context, models)) { _, _ ->
            error("offline request must not fetch")
        }
        queue.activityStarted()
        queue.states.first { it[station] == TideDownloadState.WAITING_FOR_NETWORK }
        assertTrue(reads.get() >= 4)
    }

    @Test fun incompatibleIntentCompletesWithOneError() = runBlocking {
        val reads = AtomicInteger()
        val store = DownloadIntentStore(readOnlyData {
            reads.incrementAndGet()
            preferencesOf(intPreferencesKey("version") to 99)
        }, BundledStationCatalog(context.assets))
        val observations = withTimeout(5_000) { store.state.toList() }
        assertTrue(observations.single().exceptionOrNull() is java.io.IOException)
        assertEquals(1, reads.get())
    }

    private fun readOnlyData(read: () -> Preferences) = object : DataStore<Preferences> {
        override val data = flow { emit(read()) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            error("unexpected write")
    }

    private fun withQueue(block: suspend (
        CoroutineScope, BundledStationCatalog, DownloadIntentStore, ModelStore,
    ) -> Unit) = runBlocking {
        withTimeout(15_000) {
            val directory = File(context.cacheDir, "queue-${UUID.randomUUID()}").apply { mkdirs() }
            val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val catalog = BundledStationCatalog(context.assets)
                val store = DownloadIntentStore.open(File(directory, "requests.preferences_pb"), catalog, worker)
                val models = ModelStore(File(directory, "models"))
                block(worker, catalog, store, models)
            } finally {
                worker.coroutineContext.job.cancelAndJoin()
                directory.deleteRecursively()
            }
        }
        Unit
    }

    @Test fun restoredRequestWaitsForAllowedNetworkAndPublishesPreview() = withQueue { worker, catalog, store, models ->
        store.requestDownloads(listOf(station))
        store.requestPreviews(listOf(station))
        val network = MutableStateFlow<TideDownloadState?>(TideDownloadState.WAITING_FOR_NETWORK)
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, network, AndroidPredictionRepository(context, models)) { _, _ ->
            calls.incrementAndGet()
            points
        }
        queue.activityStarted()
        queue.states.first { it[station] == TideDownloadState.WAITING_FOR_NETWORK }
        network.value = TideDownloadState.DATA_SAVER
        queue.states.first { it[station] == TideDownloadState.DATA_SAVER }
        assertEquals(0, calls.get())
        network.value = null
        store.state.first { station !in it.getOrThrow().previewRequested }
        assertTrue(station in store.state.first().getOrThrow().requested)
        val record = (models.read(station, ModelKind.TIDE_PREVIEW) as StoredModel.Ready).record
        assertEquals(now.toString(), record.getJSONArray("samples").getJSONObject(0).getString("time"))
        assertEquals(2.5, record.getJSONArray("samples").getJSONObject(1).getDouble("height"), 0.0)
        assertEquals(StoredModel.Missing, models.read(station, ModelKind.TIDE))
        assertEquals(1, calls.get())
    }

    @Test fun cancellationDuringFetchPreservesOldPreviewAndExplicitRetryResumes() = withQueue { worker, catalog, store, models ->
        store.requestDownloads(listOf(station))
        models.write(station, ModelKind.TIDE_PREVIEW, org.json.JSONObject().put("old", true))
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null), AndroidPredictionRepository(context, models)) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                try { awaitCancellation() } finally { throw java.io.IOException("cancelled transport") }
            }
            points
        }
        queue.activityStarted()
        queue.request(station)
        started.await()
        queue.cancel(station)
        val cancelled = store.state.first().getOrThrow()
        assertFalse(station in cancelled.cancelled)
        assertFalse(station in cancelled.previewRequested)
        assertTrue(station in cancelled.requested)
        assertTrue((models.read(station, ModelKind.TIDE_PREVIEW) as StoredModel.Ready).record.getBoolean("old"))
        queue.request(station)
        store.state.first { station !in it.getOrThrow().previewRequested }
        assertEquals(2, calls.get())
        assertTrue((models.read(station, ModelKind.TIDE_PREVIEW) as StoredModel.Ready).record.has("samples"))
    }

    @Test fun permanentFailureReleasesDurableRequestAndManualRetryRestartsIt() = withQueue { worker, catalog, store, models ->
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null),
            AndroidPredictionRepository(context, models)) { _, _ ->
            if (calls.incrementAndGet() == 1) throw io.openwaters.slackwater.iwls.IwlsException.Permanent("unavailable")
            points
        }
        queue.activityStarted()
        queue.request(station)
        queue.states.first { it[station] == TideDownloadState.RETRY }
        assertFalse(station in store.state.first().getOrThrow().previewRequested)
        queue.request(station)
        store.state.first { station !in it.getOrThrow().previewRequested }
        assertEquals(2, calls.get())
    }

    @Test fun publicationBeforeProcessDeathReleasesRestoredRequestOffline() = withQueue { worker, catalog, store, models ->
        val fit = StationId("chs-mill-bay")
        val instant = Instant.now()
        val samples = org.json.JSONArray()
        for (offset in listOf(-300L, 300L)) samples.put(org.json.JSONObject()
            .put("height", 1.0).put("time", instant.plusSeconds(offset).toString()))
        models.write(station, ModelKind.TIDE_PREVIEW, org.json.JSONObject().put("samples", samples))
        models.write(fit, ModelKind.TIDE, org.json.JSONObject(
            """{"offset":1.387,"constituents":[{"name":"M2","amplitude":1.5,"phase":10}]}"""))
        store.requestPreviews(listOf(station, fit))
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog,
            MutableStateFlow(TideDownloadState.WAITING_FOR_NETWORK), AndroidPredictionRepository(context, models)) { _, _ ->
            calls.incrementAndGet()
            points
        }
        queue.activityStarted()
        store.state.first { it.getOrThrow().previewRequested.isEmpty() }
        assertEquals(0, calls.get())
        assertEquals(setOf(station), models.storedIds(ModelKind.TIDE_PREVIEW))
    }

    @Test fun lastActivityStoppingCancelsTransferAndForegroundResumesDurableIntent() = withQueue { worker, catalog, store, models ->
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val queue = TidePreviewDownloads(worker, store, models, catalog,
            MutableStateFlow(null), AndroidPredictionRepository(context, models)) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            points
        }
        queue.request(station)
        queue.activityStarted()
        queue.activityStarted()
        started.await()
        queue.activityStopped()
        assertEquals(TideDownloadState.DOWNLOADING, queue.states.value[station])
        queue.activityStopped()
        cancelled.await()
        queue.states.first { it[station] == TideDownloadState.QUEUED }
        assertTrue(station in store.state.first().getOrThrow().previewRequested)
        assertEquals(StoredModel.Missing, models.read(station, ModelKind.TIDE_PREVIEW))
        queue.activityStarted()
        store.state.first { it.getOrThrow().previewRequested.isEmpty() }
        assertEquals(2, calls.get())
    }
}
