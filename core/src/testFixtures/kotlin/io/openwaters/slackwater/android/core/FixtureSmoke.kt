package io.openwaters.slackwater.android.core

import io.openwaters.slackwater.*
import io.openwaters.almanac.*
import java.time.Instant
import kotlin.math.abs

// Inputs and tolerances: pinned engine realworld-friday-harbor and Almanac parity/altaz fixtures.
object FixtureSmoke {
    fun verify() {
        val station = Station(listOf(
            HarmonicConstituent("M2", 0.557, 10.6),
            HarmonicConstituent("S2", 0.132, 35.7),
            HarmonicConstituent("N2", 0.12, 343.0),
            HarmonicConstituent("K1", 0.76, 280.4),
            HarmonicConstituent("M4", 0.016, 124.6),
            HarmonicConstituent("O1", 0.434, 258.7),
            HarmonicConstituent("M6", 0.005, 237.2),
            HarmonicConstituent("MK3", 0.012, 27.5),
            HarmonicConstituent("S4", 0.002, 157.0),
            HarmonicConstituent("MN4", 0.007, 92.0),
            HarmonicConstituent("NU2", 0.024, 350.1),
            HarmonicConstituent("S6", 0.001, 255.9),
            HarmonicConstituent("MU2", 0.017, 266.6),
            HarmonicConstituent("2N2", 0.013, 309.4),
            HarmonicConstituent("OO1", 0.03, 325.7),
            HarmonicConstituent("LAM2", 0.008, 60.0),
            HarmonicConstituent("S1", 0.024, 26.8),
            HarmonicConstituent("M1", 0.025, 298.0),
            HarmonicConstituent("J1", 0.042, 315.1),
            HarmonicConstituent("MM", 0.009, 168.8),
            HarmonicConstituent("SSA", 0.022, 227.5),
            HarmonicConstituent("SA", 0.076, 274.5),
            HarmonicConstituent("MSF", 0.007, 265.1),
            HarmonicConstituent("MF", 0.021, 156.4),
            HarmonicConstituent("RHO", 0.013, 245.9),
            HarmonicConstituent("Q1", 0.073, 250.7),
            HarmonicConstituent("T2", 0.011, 39.9),
            HarmonicConstituent("R2", 0.002, 313.6),
            HarmonicConstituent("2Q1", 0.008, 253.5),
            HarmonicConstituent("P1", 0.235, 278.0),
            HarmonicConstituent("2SM2", 0.002, 289.1),
            HarmonicConstituent("M3", 0.001, 127.1),
            HarmonicConstituent("L2", 0.021, 52.4),
            HarmonicConstituent("2MK3", 0.009, 19.5),
            HarmonicConstituent("K2", 0.035, 44.0),
            HarmonicConstituent("M8", 0.001, 105.2),
            HarmonicConstituent("MS4", 0.01, 134.0)
        ), 1.387)
        val events = station.extremes(Instant.parse("2026-07-15T00:00:00Z"), Instant.parse("2026-07-17T23:59:00Z"))
        check(events.size == 12)
        check(events.isNotEmpty())
        check(events.any { it.kind == ExtremeKind.HIGH && abs(it.time.epochSecond - Instant.parse("2026-07-15T02:52:00Z").epochSecond) < 900 && abs(it.heightMetres - 2.568) < 0.15 })
        check(events.any { it.kind == ExtremeKind.LOW && abs(it.time.epochSecond - Instant.parse("2026-07-15T06:58:00Z").epochSecond) < 900 && abs(it.heightMetres - 2.128) < 0.15 })
        check(events.any { it.kind == ExtremeKind.HIGH && abs(it.time.epochSecond - Instant.parse("2026-07-15T10:47:00Z").epochSecond) < 900 && abs(it.heightMetres - 2.353) < 0.15 })
        check(events.any { it.kind == ExtremeKind.LOW && abs(it.time.epochSecond - Instant.parse("2026-07-15T19:02:00Z").epochSecond) < 900 && abs(it.heightMetres - -0.891) < 0.15 })
        check(events.any { it.kind == ExtremeKind.HIGH && abs(it.time.epochSecond - Instant.parse("2026-07-16T03:24:00Z").epochSecond) < 900 && abs(it.heightMetres - 2.551) < 0.15 })
        check(events.any { it.kind == ExtremeKind.LOW && abs(it.time.epochSecond - Instant.parse("2026-07-16T08:07:00Z").epochSecond) < 900 && abs(it.heightMetres - 1.925) < 0.15 })
        check(events.any { it.kind == ExtremeKind.HIGH && abs(it.time.epochSecond - Instant.parse("2026-07-16T11:55:00Z").epochSecond) < 900 && abs(it.heightMetres - 2.163) < 0.15 })
        check(events.any { it.kind == ExtremeKind.LOW && abs(it.time.epochSecond - Instant.parse("2026-07-16T19:48:00Z").epochSecond) < 900 && abs(it.heightMetres - -0.682) < 0.15 })
        check(events.any { it.kind == ExtremeKind.HIGH && abs(it.time.epochSecond - Instant.parse("2026-07-17T03:55:00Z").epochSecond) < 900 && abs(it.heightMetres - 2.519) < 0.15 })
        check(events.any { it.kind == ExtremeKind.LOW && abs(it.time.epochSecond - Instant.parse("2026-07-17T09:15:00Z").epochSecond) < 900 && abs(it.heightMetres - 1.66) < 0.15 })
        check(events.any { it.kind == ExtremeKind.HIGH && abs(it.time.epochSecond - Instant.parse("2026-07-17T13:09:00Z").epochSecond) < 900 && abs(it.heightMetres - 1.926) < 0.15 })
        check(events.any { it.kind == ExtremeKind.LOW && abs(it.time.epochSecond - Instant.parse("2026-07-17T20:30:00Z").epochSecond) < 900 && abs(it.heightMetres - -0.373) < 0.15 })
        val sun = sunAltAz(Instant.ofEpochMilli(-631152000000L), Observer(48.4284, -123.3656))
        check(abs(sun.altDeg - 3.08428) <= 0.00001)
        check(abs(sun.azDeg - 229.649566) <= 0.00001)
    }
}
