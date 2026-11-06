package io.openwaters.slackwater.android.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.openwaters.slackwater.android.core.station.StationId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** One file per station and kind. A station's tide preview and its full tide model can coexist. */
enum class ModelKind(internal val suffix: String) {
    TIDE("tide"),
    TIDE_PREVIEW("tide-preview"),
    CURRENT("current"),
    ONLINE_WINDOW("online"),
}

sealed interface StoredModel {
    data object Missing : StoredModel

    /** [record] is the whole stored object, including fields this build does not know, so a rewrite keeps them. */
    class Ready internal constructor(val version: Int, private val text: String) : StoredModel {
        val record: JSONObject get() = JSONObject(text)
    }

    /** The file stays on disk: a newer build can still read an unsupported version, and a write replaces it. */
    data class Unavailable(val reason: Reason) : StoredModel {
        enum class Reason { CORRUPT, UNREADABLE, UNSUPPORTED_VERSION, WRONG_STATION }
    }
}

/**
 * Models and windows fitted or fetched on this device, as versioned JSON records written by atomic replacement,
 * so a reader such as a widget sees the old record or the new one and never a partial file.
 *
 * Every record carries [VERSION_FIELD] and [STATION_FIELD]. This build reads versions up to [SCHEMA_VERSION].
 * The files live in the no-backup directory, so they are excluded from cloud backup and device transfer.
 *
 * Records keep fields this build does not know, so a field that describes the fit or its data (a window, a recipe
 * stamp) needs a new [SCHEMA_VERSION]: an older build rewriting the record would otherwise keep a stale value.
 *
 * A record stays under the ID it was fitted for and is never read for that station's successor, whose datums can
 * differ. Use one store per directory, in one process: [get] is that store, and its writes are serialized in memory.
 * An [IOException] after the rename (a failed directory sync) means the new record is in place but may not survive
 * a power loss.
 */
class ModelStore internal constructor(private val directory: File) {
    private var sweptTemporaries = false
    private val _revision = MutableStateFlow(0L)

    /** Increases after every replacement or removal that reached disk, for views that show whether a model is stored. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    suspend fun read(id: StationId, kind: ModelKind): StoredModel = withContext(Dispatchers.IO) { readFile(id, kind) }

    private fun readFile(id: StationId, kind: ModelKind): StoredModel {
        val file = fileFor(id, kind)
        val bytes = try {
            file.readBytes()
        } catch (_: FileNotFoundException) {
            // Also thrown for a file that exists but cannot be opened, which must not read as never fitted.
            return if (file.exists()) StoredModel.Unavailable(StoredModel.Unavailable.Reason.UNREADABLE) else StoredModel.Missing
        }
        return decode(bytes, id)
    }

    /**
     * Replaces the station's record of [kind]. The record's own [VERSION_FIELD], when present, must be one this
     * build writes, so a record read from a newer build is never restamped as an older one.
     */
    suspend fun write(id: StationId, kind: ModelKind, record: JSONObject) {
        val bytes = encode(id, record)
        withContext(Dispatchers.IO) {
            alertInputLock.withLock {
                prepare()
                replace(fileFor(id, kind), bytes)
            }
        }
    }

    /**
     * Reads, changes and writes the station's record of [kind] with no other write in between, for records that
     * merge new data into old, such as online windows. Returning null leaves the stored file as it is.
     */
    suspend fun update(id: StationId, kind: ModelKind, change: (StoredModel) -> JSONObject?) {
        withContext(Dispatchers.IO) {
            alertInputLock.withLock {
                val next = change(readFile(id, kind)) ?: return@withLock
                val bytes = encode(id, next)
                prepare()
                replace(fileFor(id, kind), bytes)
            }
        }
    }

    private fun encode(id: StationId, record: JSONObject): ByteArray {
        val stamped = JSONObject(record.toString() ?: throw IllegalArgumentException("Model holds a non-finite number"))
        if (stamped.has(VERSION_FIELD) && stamped.opt(VERSION_FIELD) != SCHEMA_VERSION) {
            throw IllegalArgumentException("Cannot write a model of version ${stamped.opt(VERSION_FIELD)}")
        }
        if (stamped.has(STATION_FIELD) && stamped.optString(STATION_FIELD) != id.value) {
            throw IllegalArgumentException("Model for ${stamped.opt(STATION_FIELD)} written as ${id.value}")
        }
        stamped.put(VERSION_FIELD, SCHEMA_VERSION).put(STATION_FIELD, id.value)
        return stamped.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun remove(id: StationId, kind: ModelKind) {
        withContext(Dispatchers.IO) {
            alertInputLock.withLock {
                val file = fileFor(id, kind)
                if (!file.delete()) {
                    if (file.exists()) throw IOException("Cannot remove $file")
                    return@withLock
                }
                _revision.update { it + 1 }
                syncDirectory()
            }
        }
    }

    /** Stations with a file of [kind], whether or not it is readable. */
    suspend fun storedIds(kind: ModelKind): Set<StationId> = withContext(Dispatchers.IO) {
        val ending = ".${kind.suffix}.json"
        directory.list().orEmpty().asSequence()
            .filter { it.endsWith(ending) && !it.startsWith(TEMPORARY_PREFIX) }
            .mapNotNull { decodeName(it.dropLast(ending.length))?.let(::StationId) }
            .toSet()
    }

    private fun fileFor(id: StationId, kind: ModelKind) = File(directory, "${encodeName(id.value)}.${kind.suffix}.json")

    // A temporary file left by a process that died mid-write is never renamed into place, so it is only clutter.
    private fun prepare() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        if (sweptTemporaries) return
        directory.listFiles { file -> file.name.startsWith(TEMPORARY_PREFIX) }?.forEach { it.delete() }
        sweptTemporaries = true
    }

    private suspend fun replace(target: File, bytes: ByteArray) {
        val temporary = File(directory, "$TEMPORARY_PREFIX${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            beforeCommit()
            currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        // Published before anything else can throw, so a caller cancelled from here on still notifies observers.
        _revision.update { it + 1 }
        afterCommit()
        syncDirectory()
    }

    // The rename is durable only once the directory entry is flushed; without it a power loss can undo the write.
    private fun syncDirectory() {
        try {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (error: ErrnoException) {
            throw IOException("Cannot sync $directory", error)
        }
    }

    // Test hooks on either side of the rename that replaces the old record.
    internal var beforeCommit: () -> Unit = {}
    internal var afterCommit: () -> Unit = {}

    companion object {
        const val SCHEMA_VERSION = 1
        const val VERSION_FIELD = "schemaVersion"
        const val STATION_FIELD = "stationId"
        private const val TEMPORARY_PREFIX = ".tmp-"

        private var shared: ModelStore? = null

        @Synchronized
        fun get(context: Context): ModelStore =
            shared ?: ModelStore(File(context.applicationContext.noBackupFilesDir, "models")).also { shared = it }

        internal fun decode(bytes: ByteArray, id: StationId): StoredModel {
            val record = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: JSONException) {
                return StoredModel.Unavailable(StoredModel.Unavailable.Reason.CORRUPT)
            }
            val version = record.opt(VERSION_FIELD) as? Int
                ?: return StoredModel.Unavailable(StoredModel.Unavailable.Reason.CORRUPT)
            if (version !in 1..SCHEMA_VERSION) {
                return StoredModel.Unavailable(StoredModel.Unavailable.Reason.UNSUPPORTED_VERSION)
            }
            if (record.opt(STATION_FIELD) != id.value) {
                return StoredModel.Unavailable(StoredModel.Unavailable.Reason.WRONG_STATION)
            }
            // Android's parser accepts 1e309 as infinity, which toString() then cannot write.
            val text = record.toString() ?: return StoredModel.Unavailable(StoredModel.Unavailable.Reason.CORRUPT)
            return StoredModel.Ready(version, text)
        }

        // Station IDs hold '/' and ':'. Every byte outside [A-Za-z0-9_-] becomes %XX, so the name is one path
        // segment, never "." or "..", and decodes back to the exact ID.
        internal fun encodeName(id: String): String = buildString {
            for (byte in id.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() == '_' || c.toChar() == '-')) {
                    append(c.toChar())
                } else {
                    append('%').append(String.format(Locale.ROOT, "%02X", c))
                }
            }
        }

        internal fun decodeName(name: String): String? {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < name.length) {
                val c = name[i]
                if (c == '%') {
                    val hex = name.substring(i + 1, minOf(i + 3, name.length))
                    if (hex.length != 2) return null
                    out.write(hex.toIntOrNull(16) ?: return null)
                    i += 3
                } else {
                    out.write(c.code)
                    i++
                }
            }
            val id = out.toString(Charsets.UTF_8.name())
            return id.takeIf { encodeName(it) == name }
        }
    }
}
