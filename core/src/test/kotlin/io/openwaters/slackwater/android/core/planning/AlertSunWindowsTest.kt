package io.openwaters.slackwater.android.core.planning

import io.openwaters.slackwater.android.core.prediction.TimeSpan
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AlertSunWindowsTest {
    @Test fun goldenAndBlueHoursFlankDaylight() {
        val span = TimeSpan(Instant.parse("2026-06-10T00:00:00Z"), Instant.parse("2026-06-13T00:00:00Z"))
        fun windows(type: SunWindow) = SunWindows.spans(type, span, 48.5, -123.0)
        val golden = windows(SunWindow.GOLDEN_HOUR)
        val blue = windows(SunWindow.BLUE_HOUR)
        val days = windows(SunWindow.DAYLIGHT).filter { it.start in span && it.end in span }
        assertTrue(days.isNotEmpty())
        for (day in days) {
            assertTrue(golden.single { it.start == day.start }.end < golden.single { it.end == day.end }.start)
            assertTrue(blue.any { it.end in day.start.minusSeconds(3600)..day.start })
            assertTrue(blue.any { it.start in day.end..day.end.plusSeconds(3600) })
        }
    }

    @Test fun highLatitudeBlueNightSpansMidnight() {
        val span = TimeSpan(Instant.parse("2026-06-21T00:00:00Z"), Instant.parse("2026-06-22T00:00:00Z"))
        assertTrue(SunWindows.spans(SunWindow.BLUE_HOUR, span, 60.0, 0.0).any { span.end in it })
        assertTrue(SunWindows.spans(SunWindow.BLUE_HOUR, span, 80.0, 0.0).isEmpty())
    }
}
