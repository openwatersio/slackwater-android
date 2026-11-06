package io.openwaters.slackwater.android.data

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.StationId
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val millBay = StationId("chs-mill-bay")

    private fun withStore(block: suspend (ModelStore, File) -> Unit) = runBlocking {
        val directory = File(context.cacheDir, "models-${UUID.randomUUID()}")
        try { block(ModelStore(directory), directory) } finally { directory.deleteRecursively() }
    }

    private fun model(offset: Double, constituents: Int = 1) =
        JSONObject().put("offset", offset).put("constituents", JSONArray(List(constituents) { "C$it" }))

    private fun StoredModel.ready() = (this as? StoredModel.Ready ?: throw AssertionError("expected a model, got $this"))

    @Test fun fullIdsWithSeparatorsRoundTripThroughFileNames() = withStore { store, directory ->
        val ids = listOf(
            StationId("current:noaa/CHB9904"), StationId("noaa/9447130"), StationId("chs-mill-bay"),
            StationId("."), StationId(".."), StationId("a%2Fb"), StationId("café/ø:1"),
        )
        for ((index, id) in ids.withIndex()) store.write(id, ModelKind.CURRENT, model(index.toDouble()))
        assertEquals(ids.toSet(), store.storedIds(ModelKind.CURRENT))
        for ((index, id) in ids.withIndex()) {
            val record = store.read(id, ModelKind.CURRENT).ready().record
            assertEquals(index.toDouble(), record.getDouble("offset"), 0.0)
            assertEquals(id.value, record.getString(ModelStore.STATION_FIELD))
        }
        // Every file is a direct child: no ID escapes the directory or makes a subdirectory.
        assertTrue(directory.listFiles()!!.all { it.isFile })
        assertNull(ModelStore.decodeName("a/b"))
        assertNull(ModelStore.decodeName("%2"))
        assertNull(ModelStore.decodeName("%2f"))
    }

    @Test fun kindsAreSeparateFiles() = withStore { store, _ ->
        store.write(millBay, ModelKind.TIDE_PREVIEW, model(1.0))
        assertEquals(StoredModel.Missing, store.read(millBay, ModelKind.TIDE))
        store.write(millBay, ModelKind.TIDE, model(2.0))
        assertEquals(1.0, store.read(millBay, ModelKind.TIDE_PREVIEW).ready().record.getDouble("offset"), 0.0)
        assertEquals(2.0, store.read(millBay, ModelKind.TIDE).ready().record.getDouble("offset"), 0.0)
        assertEquals(setOf(millBay), store.storedIds(ModelKind.TIDE))
        assertEquals(emptySet<StationId>(), store.storedIds(ModelKind.CURRENT))

        val before = store.revision.value
        store.remove(millBay, ModelKind.TIDE_PREVIEW)
        assertEquals(StoredModel.Missing, store.read(millBay, ModelKind.TIDE_PREVIEW))
        assertTrue(store.read(millBay, ModelKind.TIDE) is StoredModel.Ready)
        assertTrue(store.revision.value > before)
    }

    @Test fun unknownFieldsSurviveARewrite() = withStore { store, directory ->
        File(directory.apply { mkdirs() }, "chs-mill-bay.tide.json").writeText(
            """{"schemaVersion":1,"stationId":"chs-mill-bay","offset":1.5,"fromANewerBuild":{"kept":[1,2]}}""",
        )
        val stored = store.read(millBay, ModelKind.TIDE).ready()
        assertEquals(1, stored.version)
        store.write(millBay, ModelKind.TIDE, stored.record.put("offset", 2.5))
        val rewritten = store.read(millBay, ModelKind.TIDE).ready().record
        assertEquals(2.5, rewritten.getDouble("offset"), 0.0)
        assertEquals("""{"kept":[1,2]}""", rewritten.getJSONObject("fromANewerBuild").toString())
        // A caller changing the copy it was handed does not change what the next reader sees.
        stored.record.put("offset", 9.0)
        assertEquals(1.5, stored.record.getDouble("offset"), 0.0)
    }

    @Test fun corruptAndUnsupportedFilesReadAsUnavailableAndStayOnDisk() = withStore { store, directory ->
        directory.mkdirs()
        val cases = mapOf(
            "not json" to StoredModel.Unavailable.Reason.CORRUPT,
            """["a list"]""" to StoredModel.Unavailable.Reason.CORRUPT,
            """{"stationId":"chs-mill-bay"}""" to StoredModel.Unavailable.Reason.CORRUPT,
            """{"schemaVersion":"1","stationId":"chs-mill-bay"}""" to StoredModel.Unavailable.Reason.CORRUPT,
            """{"schemaVersion":0,"stationId":"chs-mill-bay"}""" to StoredModel.Unavailable.Reason.UNSUPPORTED_VERSION,
            """{"schemaVersion":2,"stationId":"chs-mill-bay"}""" to StoredModel.Unavailable.Reason.UNSUPPORTED_VERSION,
            """{"schemaVersion":1,"stationId":"chs-fulford-harbour"}""" to StoredModel.Unavailable.Reason.WRONG_STATION,
            """{"schemaVersion":1}""" to StoredModel.Unavailable.Reason.WRONG_STATION,
            """{"schemaVersion":1,"stationId":"chs-mill-bay","samples":[1e309]}""" to StoredModel.Unavailable.Reason.CORRUPT,
        )
        val file = File(directory, "chs-mill-bay.tide.json")
        for ((text, reason) in cases) {
            file.writeText(text)
            assertEquals(text, StoredModel.Unavailable(reason), store.read(millBay, ModelKind.TIDE))
            assertEquals(text, file.readText())
            assertEquals(setOf(millBay), store.storedIds(ModelKind.TIDE))
        }
        // A fresh fit replaces an unreadable file.
        store.write(millBay, ModelKind.TIDE, model(3.0))
        assertEquals(3.0, store.read(millBay, ModelKind.TIDE).ready().record.getDouble("offset"), 0.0)
    }

    @Test fun anEntryThatCannotBeOpenedIsUnavailableNotMissing() = withStore { store, directory ->
        val blocked = File(directory, "chs-mill-bay.current.json").apply { mkdirs(); File(this, "inside").writeText("x") }
        assertEquals(StoredModel.Unavailable(StoredModel.Unavailable.Reason.UNREADABLE), store.read(millBay, ModelKind.CURRENT))
        assertThrows(IOException::class.java) { runBlocking { store.remove(millBay, ModelKind.CURRENT) } }
        assertTrue(blocked.isDirectory)
    }

    @Test fun refusesToRestampAnotherVersionOrStation() = withStore { store, _ ->
        for (record in listOf(
            model(1.0).put(ModelStore.VERSION_FIELD, 2),
            model(1.0).put(ModelStore.VERSION_FIELD, "1"),
            model(1.0).put(ModelStore.STATION_FIELD, "chs-fulford-harbour"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.write(millBay, ModelKind.TIDE, record) } }
        }
        assertEquals(StoredModel.Missing, store.read(millBay, ModelKind.TIDE))
    }

    @Test fun interruptedReplacementKeepsThePreviousModel() = withStore { store, directory ->
        store.write(millBay, ModelKind.TIDE, model(1.0))
        store.beforeCommit = { throw IOException("process died") }
        assertThrows(IOException::class.java) { runBlocking { store.write(millBay, ModelKind.TIDE, model(2.0)) } }
        assertEquals(1.0, store.read(millBay, ModelKind.TIDE).ready().record.getDouble("offset"), 0.0)
        assertEquals(listOf("chs-mill-bay.tide.json"), directory.list()!!.toList())

        // A fit cancelled while its record is being written stops before the record replaces the stored one.
        coroutineScope {
            val fit = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { store.write(millBay, ModelKind.TIDE, model(3.0)) }
            store.beforeCommit = { fit.cancel() }
            fit.join()
            assertTrue(fit.isCancelled)
        }
        assertEquals(1.0, store.read(millBay, ModelKind.TIDE).ready().record.getDouble("offset"), 0.0)
        assertEquals(listOf("chs-mill-bay.tide.json"), directory.list()!!.toList())

        // Cancelled once the record is in place: the record stays and observers still hear of it.
        store.beforeCommit = {}
        val before = store.revision.value
        coroutineScope {
            val fit = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { store.write(millBay, ModelKind.TIDE, model(4.0)) }
            store.afterCommit = { fit.cancel() }
            fit.join()
            assertTrue(fit.isCancelled)
        }
        assertEquals(4.0, store.read(millBay, ModelKind.TIDE).ready().record.getDouble("offset"), 0.0)
        assertEquals(before + 1, store.revision.value)
    }

    @Test fun concurrentMergesKeepEveryBlock() = withStore { store, _ ->
        val gate = StationId("current:noaa/CHB9904")
        coroutineScope {
            List(24) { block ->
                async(Dispatchers.IO) {
                    store.update(gate, ModelKind.ONLINE_WINDOW) { stored ->
                        val record = (stored as? StoredModel.Ready)?.record ?: JSONObject().put("blocks", JSONArray())
                        record.apply { getJSONArray("blocks").put(block) }
                    }
                }
            }.awaitAll()
        }
        val blocks = store.read(gate, ModelKind.ONLINE_WINDOW).ready().record.getJSONArray("blocks")
        assertEquals((0 until 24).toSet(), List(blocks.length()) { blocks.getInt(it) }.toSet())
        assertEquals(24L, store.revision.value)

        // Declining to change leaves the record and the revision alone.
        store.update(gate, ModelKind.ONLINE_WINDOW) { null }
        assertEquals(24L, store.revision.value)
        assertEquals(24, store.read(gate, ModelKind.ONLINE_WINDOW).ready().record.getJSONArray("blocks").length())
    }

    @Test fun revisionCountsEveryChangeThatReachedDisk() = withStore { store, _ ->
        coroutineScope {
            List(16) { i ->
                async(Dispatchers.IO) { store.write(StationId("chs-$i"), ModelKind.TIDE, model(i.toDouble())) }
            }.awaitAll()
        }
        assertEquals(16L, store.revision.value)
        store.remove(StationId("chs-absent"), ModelKind.TIDE)
        assertEquals(16L, store.revision.value)
        store.remove(StationId("chs-3"), ModelKind.TIDE)
        assertEquals(17L, store.revision.value)
        val infinite = JSONObject("""{"samples":[1e309]}""")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.write(millBay, ModelKind.TIDE, infinite) } }
        assertEquals(17L, store.revision.value)
    }

    @Test fun temporaryFilesFromADeadProcessAreIgnoredThenSwept() = withStore { _, directory ->
        directory.mkdirs()
        File(directory, "chs-mill-bay.tide.json").writeText("""{"schemaVersion":1,"stationId":"chs-mill-bay","offset":1.0}""")
        val leftover = File(directory, ".tmp-${UUID.randomUUID()}").apply { writeText("""{"schemaVersion":1,"stati""") }
        val store = ModelStore(directory)
        assertEquals(setOf(millBay), store.storedIds(ModelKind.TIDE))
        assertEquals(1.0, store.read(millBay, ModelKind.TIDE).ready().record.getDouble("offset"), 0.0)
        store.write(StationId("chs-fulford-harbour"), ModelKind.TIDE, model(2.0))
        assertFalse(leftover.exists())
    }

    @Test fun readersNeverSeeAPartialRecordDuringReplacement() = withStore { store, _ ->
        // Large enough that a write in place would be visible part way through.
        val size = 20_000
        store.write(millBay, ModelKind.TIDE, model(0.0, size))
        coroutineScope {
            val writer = async(Dispatchers.IO) {
                for (i in 1..40) store.write(millBay, ModelKind.TIDE, model(i.toDouble(), size))
            }
            val readers = List(4) {
                async(Dispatchers.IO) {
                    var reads = 0
                    while (!writer.isCompleted || reads == 0) {
                        val record = store.read(millBay, ModelKind.TIDE).ready().record
                        assertEquals(size, record.getJSONArray("constituents").length())
                        reads++
                    }
                }
            }
            (readers + writer).awaitAll()
        }
    }

    @Test fun sharedStoreLivesInTheNoBackupDirectory() {
        val store = ModelStore.get(context)
        assertSame(store, ModelStore.get(context))
        runBlocking {
            val id = StationId("test/${UUID.randomUUID()}")
            store.write(id, ModelKind.TIDE, model(1.0))
            try {
                val file = File(File(context.noBackupFilesDir, "models"), "${ModelStore.encodeName(id.value)}.tide.json")
                assertTrue(file.isFile)
            } finally {
                store.remove(id, ModelKind.TIDE)
            }
        }
    }
}
