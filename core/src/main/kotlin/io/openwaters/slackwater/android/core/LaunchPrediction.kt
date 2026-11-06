package io.openwaters.slackwater.android.core

import io.openwaters.slackwater.HarmonicConstituent
import io.openwaters.slackwater.Station
import io.openwaters.almanac.Observer
import io.openwaters.almanac.sunAltAz
import java.time.Clock

data class LaunchReading(val heightMetres: Double, val sunAltitudeDegrees: Double)
fun launchReading(constituents: List<HarmonicConstituent>, offset: Double, latitude: Double, longitude: Double, clock: Clock): LaunchReading {
    val time = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    val height = Station(constituents, offset).heights(time, time, 1.0).first().heightMetres
    return LaunchReading(height, sunAltAz(time, Observer(latitude, longitude)).altDeg)
}
