package io.openwaters.slackwater.android.calendars

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.planning.CalendarEventKind
import io.openwaters.slackwater.android.core.planning.StationCalendarEvent
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CalendarEventFormatTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val german = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.GERMANY) })
    private val start = Instant.parse("2026-08-10T12:01:00Z")
    private val event = StationCalendarEvent(StationId("noaa:test"), "English", start, start, "English",
        ZoneId.of("America/Vancouver"), CalendarEventKind.HIGH_TIDE, heightM = 1.25)

    @Test fun tideUsesLocalizedNumbersAndStationTime() {
        val tide = german.localizeCalendarEvent(event)
        assertEquals("Hochwasser 1,25 m", tide.title)
        assertEquals("05:01 · 1,25 m", tide.notes)
        assertEquals(event, tide.copy(title = event.title, notes = event.notes))
        val low = german.localizeCalendarEvent(event.copy(kind = CalendarEventKind.LOW_TIDE, imperial = true))
        assertEquals("Niedrigwasser 4,1 ft", low.title)
    }

    @Test fun windowsNoWindowAndPlainInstantsKeepTheirOwnNotes() {
        val slack = event.copy(kind = CalendarEventKind.SLACK, heightM = null)
        val missed = slack.copy(thresholdKnots = 0.5)
        val open = missed.copy(kind = CalendarEventKind.SLACK_WINDOW)
        val end = start.plusSeconds(600)
        val range = open.copy(end = end, windowEnd = end)
        val eclipse = slack.copy(kind = CalendarEventKind.LUNAR_ECLIPSE)
        val rows = german.localizeCalendarEvents(listOf(slack, missed, open, range, eclipse))
        assertEquals(listOf("Stauwasser", "Stauwasser", "Stauwasserfenster", "Stauwasserfenster", "Mondfinsternis"), rows.map { it.title })
        assertEquals(listOf("05:01", german.getString(R.string.calendar_no_window, "05:01", "0,5 kn"),
            german.getString(R.string.calendar_window_start, "05:01", "0,5 kn"),
            german.getString(R.string.calendar_window_range, "05:01", "05:11", "0,5 kn"), "05:01"), rows.map { it.notes })
    }
}
