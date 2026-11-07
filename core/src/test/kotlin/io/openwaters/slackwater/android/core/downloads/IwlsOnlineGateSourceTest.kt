package io.openwaters.slackwater.android.core.downloads

import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.station.currentGateZone
import io.openwaters.slackwater.iwls.IwlsException
import io.openwaters.slackwater.iwls.IwlsFetch
import io.openwaters.slackwater.iwls.IwlsResponse
import io.openwaters.slackwater.iwls.IwlsTransport
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IwlsOnlineGateSourceTest {
    @get:Rule val folder = TemporaryFolder()

    private val gate = StationIdentity(StationId("sechelt"), "Sechelt Rapids", "BC", 49.738333, -123.898333,
        StationKind.CanadianCurrent, timezone = "America/Vancouver")
    private val id = "63aef40a6a2b9417c0350313"
    private val stations = """[{"id":"$id","officialName":"Sechelt Rapids","latitude":49.738333,"longitude":-123.898333,"timeSeries":[{"code":"wcsp1"},{"code":"wcdp1"}]}]"""
    private val anchor = Instant.parse("2026-08-22T21:00:00Z")
    private val first = Instant.parse("2026-08-22T00:00:00Z")

    @Test fun usesBothOfficialSeriesAndLeavesUnpairedInstantsUncovered() = runBlocking {
        // Sechelt's recorded IWLS flood-to-ebb transition, with one direction removed.
        val speeds = listOf(3.895, 2.922, 1.971, 1.083, 0.29, 0.379)
        val directions = listOf(135, 135, null, 135, 135, 315)
        val end = Instant.parse("2026-09-21T07:00:00Z")
        val plan = IwlsFetch.chunkPlan(32.0, end)
        val calls = mutableListOf<URL>()
        val source = IwlsOnlineGateSource(folder.root, IwlsTransport { url ->
            calls += url
            val body = when {
                url.path.endsWith("/stations") -> stations
                url.path.endsWith("/metadata") -> """{"floodDirection":135,"ebbDirection":315}"""
                else -> {
                    val code = if (url.query.startsWith("time-series-code=wcsp1&")) "wcsp1" else "wcdp1"
                    val chunk = plan.single { IwlsFetch.dataUrl(code, id, it).toString() == url.toString() }
                    val values = if (code == "wcsp1") speeds else directions
                    values.mapIndexedNotNull { i, v ->
                        val time = first.plusSeconds(900L * i)
                        if (v == null || time !in chunk.start..chunk.end) null else """{"eventDate":"$time","value":$v}"""
                    }.joinToString(",", "[", "]")
                }
            }
            IwlsResponse(200, body, null)
        })
        val fetched = source.fetch(gate, anchor)
        assertEquals(plan.flatMap { listOf(IwlsFetch.dataUrl("wcsp1", id, it), IwlsFetch.dataUrl("wcdp1", id, it)) }
            .map { it.toString() }, calls.drop(2).map { it.toString() })
        assertEquals(135.0, fetched.floodDirection, 0.0)
        assertEquals(315.0, fetched.ebbDirection, 0.0)
        assertEquals(listOf(0, 1, 3, 4, 5).map { first.plus(Duration.ofMinutes(15L * it)) }, fetched.points.map { it.time })
        assertEquals(3.895, fetched.points.first().speedKnots, 1e-10)
        assertEquals(-0.379, fetched.points.last().speedKnots, 1e-10)
    }

    @Test fun invalidOrMissingGateTimezoneUsesUtcAcrossDevices() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            for (timezone in listOf(null, "Invalid/Zone")) {
                assertEquals(ZoneOffset.UTC, gate.copy(timezone = timezone).currentGateZone())
            }
            assertEquals(ZoneId.of("America/Vancouver"), gate.currentGateZone())
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun missingEbbDirectionStopsBeforeSeriesFetch() {
        val source = IwlsOnlineGateSource(folder.root, IwlsTransport { url ->
            val body = when {
                url.path.endsWith("/stations") -> stations
                url.path.endsWith("/metadata") -> """{"floodDirection":135}"""
                else -> error("Unexpected series request: $url")
            }
            IwlsResponse(200, body, null)
        })
        assertThrows(IwlsException.Permanent::class.java) { runBlocking { source.fetch(gate, anchor) } }
    }
}
