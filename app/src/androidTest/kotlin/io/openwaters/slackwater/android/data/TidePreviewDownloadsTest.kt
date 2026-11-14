package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.HarmonicFit
import io.openwaters.slackwater.android.core.downloads.CanadianCurrentModel
import io.openwaters.slackwater.android.core.prediction.PredictionPurpose
import io.openwaters.slackwater.android.core.prediction.PredictionResult
import io.openwaters.slackwater.android.core.prediction.StationCapabilities
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.TidePoint
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.iwls.IwlsException
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
            MutableStateFlow(TideDownloadState.WAITING_FOR_NETWORK), AndroidPredictionRepository(context, models), fit = { _, _, _ -> }) { _, _ ->
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

    @Test fun restoredCurrentRequestPublishesProvisionalThenFinalWithoutDowngrading() = withQueue { worker, catalog, store, models ->
        val id = StationId("chs-johnstone-strait-central")
        val gate = requireNotNull(BundledCurrentGates(context.assets)[id])
        val repository = AndroidPredictionRepository(context, models)
        val early = CanadianCurrentModel(id, 60.0, 30.0, 210.0,
            HarmonicFit(listOf(HarmonicConstituent("M2", 2.0, 25.0)), 0.2, 0.0, emptyList()))
        val provisionalReady = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        store.requestDownloads(listOf(id, station))
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null), repository,
            currentGate = { it == id }, fit = { gateStation, _, _ ->
                assertEquals(id, gateStation.id)
                models.publishCurrentFit(gate, early)
                provisionalReady.complete(Unit)
                finish.await()
                models.publishCurrentFit(gate, early.copy(fitDays = 210.0))
            }) { _, _ -> error("Current gate requested a tide preview") }
        queue.activityStarted()
        provisionalReady.await()
        suspend fun capability() = (repository.load(id, TimeSpan(now, now.plusSeconds(3600)),
            PredictionPurpose.DISPLAY) as PredictionResult.Ready).capabilities
        assertEquals(StationCapabilities.PROVISIONAL_CURRENT, capability())
        finish.complete(Unit)
        store.state.first { id !in it.getOrThrow().requested }
        val revision = models.revision.value
        models.publishCurrentFit(gate, early)
        assertEquals(revision, models.revision.value)
        assertEquals(gate.compatibleFit(id.value, early.recipe, 210.0)?.capability, capability())
        assertTrue(station in store.state.first().getOrThrow().requested)
    }

    @Test fun restoredGateReferenceFitsWithoutAPreviewAndUnlocksGateDetail() = withQueue { worker, catalog, store, models ->
        val gate = StationId("chs-malibu-rapids")
        val references = derivedGatesByReference(context.assets)
        assertTrue(gate in references.getValue(station))
        store.requestDownloads(listOf(station))
        val repository = AndroidPredictionRepository(context, models)
        val network = MutableStateFlow<TideDownloadState?>(TideDownloadState.DATA_SAVER)
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, network, repository,
            referencePortIds = { references.keys }, fit = { port, _, _ ->
                assertEquals(station, port.id)
                calls.incrementAndGet()
                models.write(station, ModelKind.TIDE, org.json.JSONObject(
                    """{"offset":2.0,"constituents":[{"name":"M2","amplitude":1.0,"phase":15}]}"""))
            }) { _, _ -> error("A derived gate requires a full reference fit") }
        queue.activityStarted()
        queue.states.first { it[station] == TideDownloadState.DATA_SAVER }
        assertEquals(0, calls.get())
        network.value = null
        store.state.first { station !in it.getOrThrow().requested }
        val result = repository.load(gate, TimeSpan(now, now.plusSeconds(86400)), PredictionPurpose.DISPLAY)
        assertEquals(StationCapabilities.DERIVED_TIMING, (result as PredictionResult.Ready).capabilities)
        network.value = TideDownloadState.WAITING_FOR_NETWORK
        store.requestDownloads(listOf(station))
        store.state.first { station !in it.getOrThrow().requested }
        assertEquals(1, calls.get())
    }

    @Test fun directPreviewFailureLeavesTheDerivedGateFitRunnable() = withQueue { worker, catalog, store, models ->
        val fetching = CompletableDeferred<Unit>()
        val failPreview = CompletableDeferred<Unit>()
        val fitStarted = CompletableDeferred<Unit>()
        val finishFit = CompletableDeferred<Unit>()
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null),
            AndroidPredictionRepository(context, models), referencePortIds = { setOf(station) },
            fit = { _, _, _ -> fitStarted.complete(Unit); finishFit.await() }) { _, _ ->
            fetching.complete(Unit)
            failPreview.await()
            throw IwlsException.Permanent("Incomplete tide preview")
        }
        queue.activityStarted()
        queue.request(station)
        fetching.await()
        store.requestDownloads(listOf(station))
        failPreview.complete(Unit)
        fitStarted.await()
        val pending = store.state.first().getOrThrow()
        assertTrue(station in pending.requested)
        assertFalse(station in pending.previewRequested)
        finishFit.complete(Unit)
        store.state.first { station !in it.getOrThrow().requested }
    }

    @Test fun directRequestDuringReferenceFitPublishesPreviewBeforeManualFit() = withQueue { worker, catalog, store, models ->
        val referenceStarted = CompletableDeferred<Unit>()
        val referenceCancelled = CompletableDeferred<Unit>()
        val manualStarted = CompletableDeferred<Unit>()
        val finishManual = CompletableDeferred<Unit>()
        store.requestDownloads(listOf(station))
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null),
            AndroidPredictionRepository(context, models), referencePortIds = { setOf(station) },
            fit = { _, _, manual ->
                if (!manual) {
                    referenceStarted.complete(Unit)
                    try { awaitCancellation() } finally { referenceCancelled.complete(Unit) }
                }
                manualStarted.complete(Unit)
                finishManual.await()
            }) { _, at -> listOf(TidePoint(at.minusSeconds(300), 1.0), TidePoint(at.plusSeconds(300), 2.0)) }
        queue.activityStarted()
        referenceStarted.await()
        queue.request(station)
        manualStarted.await()
        assertTrue(referenceCancelled.isCompleted)
        assertTrue(models.read(station, ModelKind.TIDE_PREVIEW) is StoredModel.Ready)
        val pending = store.state.first().getOrThrow()
        assertTrue(station in pending.requested && station in pending.tideFitRequested)
        finishManual.complete(Unit)
        store.state.first { it.getOrThrow().let { state -> station !in state.requested && station !in state.previewRequested } }
    }

    @Test fun managerCancellationClearsBothRequestsForAReferencePort() = withQueue { worker, catalog, store, models ->
        val queue = TidePreviewDownloads(worker, store, models, catalog,
            MutableStateFlow(TideDownloadState.DATA_SAVER), AndroidPredictionRepository(context, models),
            referencePortIds = { setOf(station) }, fit = { _, _, _ -> error("Unexpected fit") }) { _, _ -> error("Unexpected preview") }
        queue.activityStarted()
        for (referenceFirst in listOf(false, true)) {
            if (referenceFirst) {
                store.requestDownloads(listOf(station))
                queue.states.first { it[station] == TideDownloadState.DATA_SAVER }
            }
            queue.request(station)
            if (!referenceFirst) store.requestDownloads(listOf(station))
            queue.cancelFromManager(station)
            val intent = store.state.first().getOrThrow()
            assertFalse(station in intent.requested || station in intent.previewRequested || station in intent.tideFitRequested)
            assertNull(queue.states.value[station])
        }
    }

    @Test fun newlyValidatedNetworkWakesTransientBackoff() = withQueue { worker, catalog, store, models ->
        val network = TideDownloadNetwork()
        network.update(online = true, restricted = false, validated = false)
        val calls = AtomicInteger()
        val fetching = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val queue = TidePreviewDownloads(worker, store, models, catalog, network.paused,
            AndroidPredictionRepository(context, models), network.recovery, fit = { _, _, _ -> }) { _, _ ->
            if (calls.incrementAndGet() == 1) throw IwlsException.Transient("No route")
            fetching.complete(Unit)
            response.await()
            points
        }
        queue.activityStarted()
        queue.request(station)
        queue.states.first { it[station] == TideDownloadState.RETRY }
        network.update(online = true, restricted = false, validated = true)
        network.update(online = true, restricted = false, validated = true)
        assertEquals(1L, network.recovery.value)
        fetching.await()
        network.update(online = true, restricted = false, validated = false)
        network.update(online = true, restricted = false, validated = true)
        response.complete(Unit)
        queue.states.first { it.isEmpty() }
        assertEquals(2, calls.get())
        assertTrue(models.read(station, ModelKind.TIDE_PREVIEW) is StoredModel.Ready)
    }

    @Test fun foregroundRequestPublishesPreviewBeforeFittingAndResumesWithoutFetchingItAgain() = withQueue { worker, catalog, store, models ->
        val fitting = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val previewCalls = AtomicInteger()
        val network = MutableStateFlow<TideDownloadState?>(null)
        val queue = TidePreviewDownloads(worker, store, models, catalog, network,
            AndroidPredictionRepository(context, models), fit = { _, _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    fitting.complete(Unit)
                    awaitCancellation()
                }
                resumed.complete(Unit)
                finish.await()
                models.write(station, ModelKind.TIDE, org.json.JSONObject(
                    """{"offset":2.0,"constituents":[{"name":"M2","amplitude":1.0,"phase":15}]}"""))
            }) { _, at ->
            previewCalls.incrementAndGet()
            listOf(TidePoint(at.minusSeconds(300), 2.0), TidePoint(at.plusSeconds(300), 2.5))
        }
        store.requestDownloads(listOf(station)) // A derived gate also needs this reference tide.
        queue.activityStarted()
        queue.request(station)
        fitting.await()
        assertTrue(models.read(station, ModelKind.TIDE_PREVIEW) is StoredModel.Ready)
        assertEquals(StoredModel.Missing, models.read(station, ModelKind.TIDE))
        network.value = TideDownloadState.DATA_SAVER
        queue.states.first { it[station] == TideDownloadState.DATA_SAVER }
        assertTrue(station in store.state.first().getOrThrow().requested)
        network.value = null
        resumed.await()
        finish.complete(Unit)
        store.state.first { station !in it.getOrThrow().tideFitRequested }
        assertTrue(models.read(station, ModelKind.TIDE) is StoredModel.Ready)
        assertTrue(station in store.state.first().getOrThrow().requested)
        queue.request(station)
        store.state.first { station !in it.getOrThrow().tideFitRequested }
        assertEquals(3, attempts.get())
        assertEquals(1, previewCalls.get())
    }

    @Test fun explicitRequestPromotesAnInFlightRestoredPreview() = withQueue { worker, catalog, store, models ->
        store.requestPreviews(listOf(station))
        val fetching = CompletableDeferred<Unit>()
        val releasePreview = CompletableDeferred<Unit>()
        val manualFit = CompletableDeferred<Boolean>()
        val fetches = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null),
            AndroidPredictionRepository(context, models), fit = { _, _, manual ->
                assertTrue(models.read(station, ModelKind.TIDE_PREVIEW) is StoredModel.Ready)
                manualFit.complete(manual)
            }) { _, at ->
            fetches.incrementAndGet()
            fetching.complete(Unit)
            releasePreview.await()
            listOf(TidePoint(at.minusSeconds(300), 1.0), TidePoint(at.plusSeconds(300), 2.0))
        }
        queue.activityStarted()
        fetching.await()
        queue.request(station)
        assertTrue(station in store.state.first().getOrThrow().tideFitRequested)
        releasePreview.complete(Unit)
        assertTrue(manualFit.await())
        store.state.first { station !in it.getOrThrow().previewRequested }
        assertEquals(1, fetches.get())
    }

    @Test fun restoredRequestWaitsForAllowedNetworkAndPublishesPreview() = withQueue { worker, catalog, store, models ->
        store.requestDownloads(listOf(station))
        store.requestPreviews(listOf(station))
        val network = MutableStateFlow<TideDownloadState?>(TideDownloadState.WAITING_FOR_NETWORK)
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, network, AndroidPredictionRepository(context, models), fit = { _, _, _ -> }) { _, _ ->
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

    @Test fun withdrawnAndUnknownPreviewsReleaseOnlyTheirOwnRequest() = withQueue { worker, catalog, store, models ->
        val ids = setOf(StationId("chs-aulatsivik-point"), StationId("missing-station"))
        store.requestDownloads(ids)
        store.requestPreviews(ids)
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null),
            AndroidPredictionRepository(context, models), fit = { _, _, _ -> }) { _, _ ->
            calls.incrementAndGet()
            points
        }
        queue.activityStarted()
        val restored = store.state.first { it.getOrThrow().previewRequested.isEmpty() }.getOrThrow()
        assertEquals(ids, restored.requested)
        assertEquals(0, calls.get())
    }

    @Test fun cancellationDuringFetchPreservesOldPreviewAndExplicitRetryResumes() = withQueue { worker, catalog, store, models ->
        store.requestDownloads(listOf(station))
        models.write(station, ModelKind.TIDE_PREVIEW, org.json.JSONObject().put("old", true))
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val queue = TidePreviewDownloads(worker, store, models, catalog, MutableStateFlow(null), AndroidPredictionRepository(context, models), fit = { _, _, _ -> }) { _, _ ->
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
            AndroidPredictionRepository(context, models), fit = { _, _, _ -> }) { _, _ ->
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
            MutableStateFlow(TideDownloadState.WAITING_FOR_NETWORK), AndroidPredictionRepository(context, models), fit = { _, _, _ -> }) { _, _ ->
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
            MutableStateFlow(null), AndroidPredictionRepository(context, models), fit = { _, _, _ -> }) { _, _ ->
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
