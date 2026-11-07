package io.openwaters.slackwater.android.data

import android.content.res.AssetManager
import android.util.LruCache
import io.openwaters.slackwater.android.core.map.pinRank
import java.io.FileInputStream
import java.nio.channels.FileChannel
import org.json.JSONObject

internal class BundledCurrentRecords(private val assets: AssetManager) {
    private data class Entry(val offset: Int, val size: Int, val rank: Double)

    private val cache = LruCache<String, JSONObject>(32)
    private val index by lazy {
        assets.open("slackwater/current-index.tsv").bufferedReader().useLines { lines ->
            lines.associate { line ->
                val (id, offset, size, subordinate, signal) = line.split('\t')
                id to Entry(offset.toInt(), size.toInt(), pinRank(subordinate == "1", signal.toDouble()))
            }
        }
    }
    private val catalog by lazy {
        assets.openFd("slackwater/currents.json").use { asset ->
            FileInputStream(asset.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
            }
        }
    }

    fun rank(id: String): Double? = index[id]?.rank

    operator fun get(id: String): JSONObject? {
        cache.get(id)?.let { return it }
        val entry = index[id] ?: return null
        val bytes = ByteArray(entry.size)
        catalog.duplicate().apply { position(entry.offset); get(bytes) }
        return JSONObject(bytes.toString(Charsets.UTF_8)).also { cache.put(id, it) }
    }
}
