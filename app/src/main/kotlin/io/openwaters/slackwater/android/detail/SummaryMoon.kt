package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import io.openwaters.almanac.*
import java.time.*
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MoonContext(
    val apsides: List<MoonApsis> = emptyList(), val lunar: List<LunarEclipse> = emptyList(), val solar: List<SolarEclipse> = emptyList(),
)

@Composable
internal fun rememberMoonContext(day: LocalDate, zone: ZoneId, observer: Observer): MoonContext {
    return key(day, zone, observer) {
        val result by produceState(MoonContext()) {
            value = withContext(Dispatchers.Default) {
                val start = day.minusDays(1).atStartOfDay(zone).toInstant()
                val end = day.plusDays(2).atStartOfDay(zone).toInstant()
                try {
                    MoonContext(moonApsides(start.minus(Duration.ofDays(2)), end.plus(Duration.ofDays(2))),
                        lunarEclipses(start, end).filter {
                            val v = lunarEclipseVisibility(it, observer)
                            val c = v.contactsVisible
                            v.visibleAtPeak || c.p1 || c.p4 || listOf(c.u1, c.u2, c.u3, c.u4).any { contact -> contact == true }
                        }, solarEclipses(start, end, observer))
                } catch (_: AlmanacOutOfRangeException) { MoonContext() }
            }
        }
        result
    }
}

@Composable
internal fun MoonDisc(time: Instant, observer: Observer, moon: MoonIllumination, eclipse: String?) {
    val orientation = remember(time.epochSecond / 60, observer) { moonOrientation(time, observer) }
    Canvas(Modifier.size(30.dp)) {
        val radius = size.minDimension / 2
        drawCircle(Color(0xFF374254), radius)
        val path = Path()
        for (step in 0..64) {
            val y = step / 32f - 1f
            val x = sqrt(max(0f, 1 - y * y))
            if (step == 0) path.moveTo(center.x, 0f)
            else path.lineTo(center.x + radius * x, center.y + radius * y)
        }
        for (step in 64 downTo 0) {
            val y = step / 32f - 1f
            val x = sqrt(max(0f, 1 - y * y)) * (1 - 2 * moon.fraction).toFloat()
            path.lineTo(center.x + radius * x, center.y + radius * y)
        }
        path.close()
        rotate((orientation.brightLimbAngleDeg - orientation.parallacticAngleDeg - 90).toFloat()) {
            drawPath(path, if (eclipse == null) Color(0xFFEFE4CC) else Color(0xFFAD7356))
        }
    }
}
