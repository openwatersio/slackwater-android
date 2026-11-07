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

    @Test fun holesAndShortResponsesFailCoverageValidation() {
        for (response in listOf(samples(skip = 10..11), samples(last = 3 * 96))) {
            val source = IwlsTidePreviewSource(folder.root, IwlsTransport { url ->
                IwlsResponse(200, if (url.path.endsWith("/data")) response else list, null)
            })
            assertThrows(IwlsException.Transient::class.java) { runBlocking { source.fetch(station, now) } }
        }
    }
}
