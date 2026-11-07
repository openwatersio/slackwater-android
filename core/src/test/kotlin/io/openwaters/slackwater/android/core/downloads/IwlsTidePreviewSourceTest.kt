package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.iwls.IwlsException
import io.openwaters.slackwater.iwls.IwlsResponse
import io.openwaters.slackwater.iwls.IwlsTransport
import java.net.URL
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IwlsTidePreviewSourceTest {
    @get:Rule val folder = TemporaryFolder()

    private val station = StationIdentity(StationId("local"), "Test Port", "BC", 49.0, -123.0,
        StationKind.CanadianTide, timezone = "America/Vancouver")
    private val now = Instant.parse("2026-03-08T20:00:00Z")
    private val midnight = Instant.parse("2026-03-08T08:00:00Z")
    private val start = midnight.minus(Duration.ofDays(2))
    private val list = """[{"id":"current","officialName":"Test Port","latitude":49,"longitude":-123,"timeSeries":[{"code":"wcsp1"}]},{"id":"water","officialName":"Other name","latitude":49,"longitude":-123.01,"timeSeries":[{"code":"wlp"}]}]"""

    private fun samples(skip: IntRange = IntRange.EMPTY, last: Int = 7 * 96) = (0..last)
        .filter { it !in skip }.joinToString(",", "[", "]") {
            """{"eventDate":"${start.plusSeconds(it * 900L)}","value":${it * 0.01}}"""
        }

    @Test fun resolvesByPositionAndSeriesAndUsesStationMidnightAcrossDst() = runBlocking {
        val urls = mutableListOf<URL>()
        val source = IwlsTidePreviewSource(folder.root, IwlsTransport { url ->
            urls += url
            IwlsResponse(200, if (url.path.endsWith("/data")) samples() else list, null)
        })
        val points = source.fetch(station, now)
        assertEquals(start, points.first().time)
        assertEquals(start.plus(Duration.ofDays(7)), points.last().time)
        assertEquals("/api/v1/stations", urls[0].path)
        assertEquals("/api/v1/stations/water/data", urls[1].path)
        val query = URLDecoder.decode(urls[1].query, "UTF-8")
        assertEquals("time-series-code=wlp&from=${start}&to=${points.last().time}&resolution=FIFTEEN_MINUTES", query)

        val fresh = IwlsTidePreviewSource(folder.root, IwlsTransport { url ->
            assertEquals("/api/v1/stations/water/data", url.path)
            IwlsResponse(200, samples(), null)
        })
        assertEquals(points, fresh.fetch(station, now))
    }

    @Test fun staleListSurvivesRefreshFailureButNeverSwallowsCancellation() {
        val cached = folder.newFile("IwlsStations.json")
        cached.writeText(list)
        cached.setLastModified(now.minus(Duration.ofDays(7)).toEpochMilli())
        var requests = 0
        val source = IwlsTidePreviewSource(folder.root, IwlsTransport { url ->
            requests++
            if (url.path.endsWith("/data")) IwlsResponse(200, samples(), null) else IwlsResponse(404, null, null)
        })
        runBlocking { source.fetch(station, now) }
        assertEquals(2, requests)
        val cancelled = IwlsTidePreviewSource(folder.root, IwlsTransport {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            throw CancellationException("cancelled")
        })
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.fetch(station, now) } }
    }

    @Test fun cachedMissAndTruncatedCacheRefreshOnce() {
        val cache = folder.newFile("IwlsStations.json")
        for (body in listOf("[]", list.dropLast(1), "{}")) {
            cache.writeText(body)
            cache.setLastModified(now.toEpochMilli())
            val paths = mutableListOf<String>()
            val source = IwlsTidePreviewSource(folder.root, IwlsTransport { url ->
                paths += url.path
                IwlsResponse(200, if (url.path.endsWith("/data")) samples() else list, null)
            })
            runBlocking { source.fetch(station, now) }
            assertEquals(listOf("/api/v1/stations", "/api/v1/stations/water/data"), paths)
            assertEquals(list, cache.readText())
        }
    }

    @Test fun malformedRefreshFallsBackOnlyToAResolvedStation() {
        val cache = folder.newFile("IwlsStations.json")
        cache.writeText(list)
        cache.setLastModified(now.minus(Duration.ofDays(8)).toEpochMilli())
        val truncated = list.dropLast(1)
        val source = IwlsTidePreviewSource(folder.root, IwlsTransport { url ->
            IwlsResponse(200, if (url.path.endsWith("/data")) samples() else truncated, null)
        })
        runBlocking { source.fetch(station, now) }
        assertEquals(list, cache.readText())
        cache.writeText("[]")
        val offline = IwlsTidePreviewSource(folder.root, IwlsTransport {
            throw javax.net.ssl.SSLHandshakeException("offline during refresh")
        })
        assertThrows(IwlsException.Transient::class.java) { runBlocking { offline.fetch(station, now) } }
    }

    @Test fun holesAndShortResponsesRequireManualRetry() {
        for (response in listOf(samples(skip = 10..11), samples(last = 3 * 96))) {
            val source = IwlsTidePreviewSource(folder.root, IwlsTransport { url ->
                IwlsResponse(200, if (url.path.endsWith("/data")) response else list, null)
            })
            assertThrows(IwlsException.Permanent::class.java) { runBlocking { source.fetch(station, now) } }
        }
    }
}
