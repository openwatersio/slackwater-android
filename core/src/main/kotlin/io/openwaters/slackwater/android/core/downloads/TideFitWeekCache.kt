package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.iwls.ChsChunk
import io.openwaters.slackwater.iwls.ChsSample
import io.openwaters.slackwater.iwls.IwlsChunkCacheEntry
import io.openwaters.slackwater.iwls.IwlsSampleGrid
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** One foreground fit at a time writes these disposable, epoch-aligned IWLS weeks. */
internal class TideFitWeekCache(private val directory: File) {
    suspend fun load(id: String, chunk: ChsChunk, grid: IwlsSampleGrid, now: Instant, bypassEmpty: Boolean = false,
                     fetch: suspend () -> List<ChsSample>): List<ChsSample> {
        if (!chunk.isCacheable) return fetch()
        val station = UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8))
        val file = File(directory, "v1-$station-${chunk.start.epochSecond}-${grid.sampleMinutes}")
        try {
            if (file.isFile) DataInputStream(file.inputStream().buffered()).use { input ->
                val fetched = Instant.ofEpochMilli(input.readLong())
                val count = input.readInt()
                if (count >= 0 && file.length() == 12L + count * 16L) {
                    val samples = List(count) { ChsSample(input.readLong(), input.readDouble()) }
                    val entry = IwlsChunkCacheEntry.create(chunk, samples, grid, fetched)
                    if ((!bypassEmpty || samples.isNotEmpty()) && entry?.isReusable(chunk, grid, now) == true) return samples
                }
            }
        } catch (_: IOException) { /* Refetch an unreadable week. */ }
        val samples = fetch()
        if (IwlsChunkCacheEntry.create(chunk, samples, grid, now) == null) return samples
        try {
            directory.mkdirs()
            val temporary = File.createTempFile("week-", ".tmp", directory)
            try {
                DataOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.writeLong(now.toEpochMilli())
                    output.writeInt(samples.size)
                    for (sample in samples) {
                        output.writeLong(sample.tMillis)
                        output.writeDouble(sample.value)
                    }
                }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        } catch (_: IOException) { /* The current fit can use the downloaded samples. */ }
        return samples
    }
}
