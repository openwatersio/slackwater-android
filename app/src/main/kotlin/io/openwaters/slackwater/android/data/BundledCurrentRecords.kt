package io.openwaters.slackwater.android.data

import android.content.res.AssetManager
import android.util.JsonReader
import android.util.JsonToken
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject

/** The current catalog is a JSON array. Index IDs once and decode only a requested row and its reference. */
internal class BundledCurrentRecords(private val assets: AssetManager) {
    private fun open() = JsonReader(assets.open("slackwater/currents.json").bufferedReader())
    private val cache = LruCache<String, JSONObject>(16)
    private val positions by lazy {
        open().use { reader ->
            reader.beginArray()
            buildMap<String, Int> {
                var index = 0
                while (reader.hasNext()) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "id") put(reader.nextString(), index) else reader.skipValue()
                    }
                    reader.endObject()
                    index++
                }
                reader.endArray()
            }
        }
    }

    operator fun get(id: String): JSONObject? {
        cache.get(id)?.let { return it }
        val index = positions[id] ?: return null
        return open().use { reader ->
            reader.beginArray()
            repeat(index) { reader.skipValue() }
            (reader.value() as JSONObject).also { cache.put(id, it) }
        }
    }

    private fun JsonReader.value(): Any = when (peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { obj ->
            beginObject()
            while (hasNext()) obj.put(nextName(), value())
            endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
            beginArray()
            while (hasNext()) array.put(value())
            endArray()
        }
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> nextDouble()
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> { nextNull(); JSONObject.NULL }
        else -> throw IllegalArgumentException("Unexpected current record token")
    }
}
