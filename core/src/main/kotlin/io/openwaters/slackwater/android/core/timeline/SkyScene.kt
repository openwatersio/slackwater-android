package io.openwaters.slackwater.android.core.timeline

import io.openwaters.almanac.*
import io.openwaters.slackwater.android.core.planning.SunWindows
import java.time.Instant
import kotlin.math.*

data class SkyBody(
    val x: Double,
    val altitude: Double,
    val hold: Boolean = false,
    val facesSouth: Boolean = false
)

data class SkyStar(val body: SkyBody, val magnitude: Double)
data class SkyFrame(
    val sun: SkyBody, val moon: SkyBody, val phase: Double, val limbDegrees: Double,
    val obscuration: Double, val umbra: Double, val wash: Double, val stars: List<SkyStar>
)

class SkyScene(
    private val latitude: Double,
    longitude: Double,
    start: Instant,
    end: Instant,
    days: List<TimelineDay>
) {
    private val observer = Observer(latitude, longitude)
    private val north = latitude >= 0
    private val first = maxOf(start.minusSeconds(86400), Instant.parse("1950-01-01T00:00:00Z"))
    private val last = minOf(end.plusSeconds(86400), Instant.parse("2100-12-31T23:59:59Z"))
    private val solar = if (first < last) solarEclipses(first, last, observer) else emptyList()
    private val lunar = if (first < last) lunarEclipses(first, last).filter {
        SunWindows.visibleAtStation(
            it,
            observer
        )
    } else emptyList()
    val peaks = (solar.map { it.peak } + lunar.map { it.peak }).sorted()

    private data class Arc(
        val start: Instant,
        val end: Instant,
        val rise: Double,
        val set: Double,
        val south: Boolean,
        val circumpolar: Boolean,
    )

    private fun arcs(
        rises: List<Instant>,
        sets: List<Instant>,
        position: (Instant) -> AltAz,
        declination: (Instant) -> Double
    ) =
        sets.mapNotNull { set ->
            val rise = rises.lastOrNull { it < set } ?: return@mapNotNull null
            if (rise < first || set > last) return@mapNotNull null
            val mid = Instant.ofEpochMilli((rise.toEpochMilli() + set.toEpochMilli()) / 2)
            val dec = declination(mid)
            val poleward = if (north) dec else -dec
            Arc(
                rise, set, position(rise).azDeg, position(set).azDeg, (dec < latitude),
                poleward > 90 - abs(latitude) || set.epochSecond - rise.epochSecond >= 86400
            )
        }

    private val sunArcs = arcs(
        days.mapNotNull { it.sunrise }, days.mapNotNull { it.sunset },
        { sunAltAz(it, observer) }, { sunPosition(it).decDeg })
    private val moonArcs = arcs(
        days.mapNotNull { it.moonrise }, days.mapNotNull { it.moonset },
        { moonAltAz(it, observer) }, { moonPosition(it).decDeg })

    private fun coordinate(az: Double, south: Boolean) =
        if (south) 180.0 - az else (az + 180.0) % 360.0 - 180.0

    private fun nearest(arcs: List<Arc>, at: Instant) =
        arcs.minByOrNull {
            abs(
                at.epochSecond - at.epochSecond.coerceIn(
                    it.start.epochSecond,
                    it.end.epochSecond
                )
            )
        }

    private fun body(pos: AltAz, arc: Arc?, south: Boolean = arc?.south ?: north): SkyBody {
        val left = arc?.let { coordinate(it.set, south) } ?: -180.0
        val right = arc?.let { coordinate(it.rise, south) } ?: 180.0
        val x = if (right > left) (coordinate(pos.azDeg, south) - left) / (right - left)
        else 0.5 + coordinate(pos.azDeg, south) / 360
        return SkyBody(x, pos.altDeg, arc != null && !arc.circumpolar, south)
    }

    private fun fraction(at: Instant, a: Instant?, peak: Instant, b: Instant?): Double {
        if (a == null || b == null || at < a || at > b) return 0.0
        val edge = if (at <= peak) a else b
        val length = peak.toEpochMilli() - edge.toEpochMilli()
        return if (length == 0L) 1.0 else ((at.toEpochMilli() - edge.toEpochMilli()).toDouble() / length).coerceIn(
            0.0,
            1.0
        )
    }

    fun frame(at: Instant): SkyFrame? {
        if (at < first || at > last) return null
        val sunArc = nearest(sunArcs, at)
        val sun = body(sunAltAz(at, observer), sunArc)
        val moon = body(moonAltAz(at, observer), nearest(moonArcs, at))
        val sol = solar.firstOrNull { at >= it.c1 && at <= it.c4 }
        val lun = lunar.firstOrNull { at >= it.p1 && at <= it.p4 }
        val orientation = moonOrientation(at, observer)
        val obscuration = if (sol == null) 0.0 else solarObscuration(at, observer)
        val stars =
            if (sun.altitude < -6 || obscuration > 0.9) brightStars.mapNotNull { (ra, dec, mag) ->
                val pos = starAltAz(ra, dec, at, observer)
                if (pos.altDeg < -10) null else SkyStar(body(pos, sunArc, north), mag)
            } else emptyList()
        return SkyFrame(
            sun,
            moon,
            moonIllumination(at).fraction,
            orientation.brightLimbAngleDeg - orientation.parallacticAngleDeg - 90,
            obscuration,
            lun?.let { it.magUmbral * fraction(at, it.u1, it.peak, it.u4) } ?: 0.0,
            lun?.let { min(1.0, it.magPenumbral) * fraction(at, it.p1, it.peak, it.p4) } ?: 0.0,
            stars)
    }

    // J2000 rows from the Yale Bright Star Catalog via d3-celestial and the pinned iOS stars.json.
    private companion object {
        val brightStars by lazy {
            requireNotNull(SkyScene::class.java.getResourceAsStream("/io/openwaters/slackwater/android/core/timeline/bright-stars.csv")).bufferedReader()
                .useLines { lines ->
                    lines.map { row ->
                        val (ra, dec, mag) = row.split(',').map(String::toDouble)
                        Triple(ra, dec, mag)
                    }.toList()
                }
        }
    }
}
