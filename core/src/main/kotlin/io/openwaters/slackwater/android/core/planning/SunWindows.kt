package io.openwaters.slackwater.android.core.planning

import io.openwaters.almanac.*
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import java.time.Instant

enum class SunWindow { DAYLIGHT, GOLDEN_HOUR, BLUE_HOUR }

object SunWindows {
    private data class Edge(val time: Instant, val opens: Boolean)

    fun spans(window: SunWindow, span: TimeSpan, latitude: Double, longitude: Double): List<TimeSpan> {
        val observer = observer(latitude, longitude) ?: return emptyList()
        val start = span.start.minusSeconds(86_400)
        val end = span.end.plusSeconds(86_400)
        fun horizon() = sunEvents(start, end, observer).mapNotNull {
            when (it.kind) {
                SunEventKind.RISE -> Edge(it.time, true)
                SunEventKind.SET -> Edge(it.time, false)
                else -> null
            }
        }
        fun crossings(degrees: Double, opensOnRise: Boolean) =
            sunAltitudeEvents(start, end, observer, degrees).map {
                Edge(it.time, (it.kind == AltitudeEventKind.RISING) == opensOnRise)
            }
        val edges = when (window) {
            SunWindow.DAYLIGHT -> horizon()
            SunWindow.GOLDEN_HOUR -> horizon() + crossings(6.0, false)
            SunWindow.BLUE_HOUR -> crossings(-8.0, true) + crossings(-4.0, false)
        }
        return buildList {
            var open: Instant? = null
            for (edge in edges.sortedBy { it.time }) {
                if (edge.opens) open = edge.time else {
                    open?.let { add(TimeSpan(it, edge.time)) }
                    open = null
                }
            }
        }
    }

    fun eclipseContacts(span: TimeSpan, latitude: Double, longitude: Double): List<Instant> {
        val observer = observer(latitude, longitude) ?: return emptyList()
        // Almanac searches by peak. Include peaks just beyond the range whose first contact is inside.
        return lunarEclipses(span.start, span.end.plusSeconds(86_400)).mapNotNull { eclipse ->
            val visibility = lunarEclipseVisibility(eclipse, observer)
            val contacts = visibility.contactsVisible
            val visible = visibility.visibleAtPeak || contacts.p1 || contacts.p4 ||
                listOf(contacts.u1, contacts.u2, contacts.u3, contacts.u4).any { it == true }
            (eclipse.u1 ?: eclipse.p1).takeIf { visible && it in span }
        }
    }

    private fun observer(latitude: Double, longitude: Double): Observer? =
        if (latitude in -90.0..90.0 && longitude in -180.0..180.0) Observer(latitude, longitude) else null
}
