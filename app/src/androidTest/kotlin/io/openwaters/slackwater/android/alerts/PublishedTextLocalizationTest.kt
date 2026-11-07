package io.openwaters.slackwater.android.alerts

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.calendars.localizedStationCalendarTitle
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationKind
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PublishedTextLocalizationTest {
    private fun german(): Context {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.GERMANY))
        }
        return app.createConfigurationContext(config)
    }

    @Test fun englishNotificationKeepsItsStandaloneEventWording() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.US)) }
        val context = app.createConfigurationContext(config)
        assertEquals("High tide", alertNotificationLabel(context, AlertTrigger.TideExtreme(true), false))
        assertEquals("Low tide", alertNotificationLabel(context, AlertTrigger.TideExtreme(false), false))
        assertEquals("Rising past 1.5 m", alertNotificationLabel(context, AlertTrigger.TideCrossing(1.5, true), false))
        assertEquals("Falling past 1.5 m", alertNotificationLabel(context, AlertTrigger.TideCrossing(1.5, false), false))
        assertEquals("Slack window opens", alertNotificationLabel(context, AlertTrigger.SlackWindowOpens, false))
        assertEquals("Eclipse begins", alertNotificationLabel(context, AlertTrigger.Eclipse, false))
    }

    @Test fun notificationUsesConfiguredLanguageAndDecimalSeparator() {
        val context = german()
        assertEquals("Hochwasser", alertNotificationLabel(context, AlertTrigger.TideExtreme(true), false))
        assertEquals("Niedrigwasser", alertNotificationLabel(context, AlertTrigger.TideExtreme(false), false))
        assertEquals("Max. Flutstrom", alertNotificationLabel(context, AlertTrigger.CurrentPeak(true), false))
        assertEquals("Max. Ebbstrom", alertNotificationLabel(context, AlertTrigger.CurrentPeak(false), false))
        assertEquals("Steigend über 1,25 m", alertNotificationLabel(context, AlertTrigger.TideCrossing(1.25, true), false))
        assertEquals("Fallend unter -1,25 m", alertNotificationLabel(context, AlertTrigger.TideCrossing(-1.25, false), false))
    }

    @Test fun noWindowFallsBackToSlackAndEclipseKeepsItsMeaning() {
        val context = german()
        assertEquals("Stauwasserfenster öffnet sich", alertNotificationLabel(context, AlertTrigger.SlackWindowOpens, false))
        assertEquals("Stauwasser", alertNotificationLabel(context, AlertTrigger.SlackWindowOpens, true))
        assertEquals("Stauwasser", alertNotificationLabel(context, AlertTrigger.Slack, false))
        assertEquals("Finsternis beginnt", alertNotificationLabel(context, AlertTrigger.Eclipse, false))
    }

    @Test fun calendarDisplayNameUsesStationSeriesWithoutChangingItsName() {
        val context = german()
        val station = StationIdentity(StationId("noaa:test"), "Friday Harbor", "", 48.54, -123.01, StationKind.Tide)
        val english = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.US)) }
        assertEquals("Friday Harbor Tides", localizedStationCalendarTitle(context.createConfigurationContext(english), station))
        assertEquals("Gezeiten von Friday Harbor", localizedStationCalendarTitle(context, station))
        assertEquals("Strömungen bei Friday Harbor", localizedStationCalendarTitle(context, station.copy(kind = StationKind.DerivedGate)))
        assertNull(localizedStationCalendarTitle(context, station.copy(kind = StationKind.Restricted)))
    }
}
