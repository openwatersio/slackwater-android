package io.openwaters.slackwater.android.alerts

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.HeightUnits
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AlertDetailPlotTest {
    private fun t(minute: Int) = Instant.EPOCH.plusSeconds(minute * 60L)
    private val station = StationIdentity(StationId("tide"), "Hope Bay", "", 0.0, 0.0, StationKind.Tide)
    private val rest = ChartAlertSelection(station, StationCapabilities.HARMONIC_TIDE, AlertTrigger.TideExtreme(false), t(0), t(60))
    private val tide = PredictionSeries.Tide(listOf(TidePoint(t(0), 0.0), TidePoint(t(60), 1.111),
        TidePoint(t(120), 0.0)), emptyList(), listOf(TideExtreme(t(60), 1.111, ExtremeKind.HIGH)))

    @Test fun aNearTurnParksOnTheTurnAndOtherPositionsRoundTheHeightBeingDrawn() {
        val snap = plotSelection(tide, rest, t(45), HeightUnits.METRIC)!!
        assertEquals(t(60), snap.moment)
        assertEquals(AlertTrigger.TideExtreme(true), snap.trigger)
        val rise = plotSelection(tide, rest, t(30), HeightUnits.METRIC)!!
        assertEquals(AlertTrigger.TideCrossing(0.56, true), rise.trigger)
        val fall = plotSelection(tide, rest, t(90), HeightUnits.IMPERIAL)!!
        val crossing = fall.trigger as AlertTrigger.TideCrossing
        assertFalse(crossing.rising)
        assertEquals(1.8 * 0.3048, crossing.heightM, 1e-9)
        assertEquals(t(90), fall.moment)
        assertNull(plotSelection(tide, rest, t(121), HeightUnits.METRIC))
    }

    @Test fun notificationMinuteSelectsTheActualContactOrNearbyExtreme() {
        val contact = t(50).plusSeconds(37)
        assertEquals(AlertTrigger.TideExtreme(true), plotSelection(tide, rest, t(50), HeightUnits.METRIC, listOf(contact))!!.trigger)
        val selected = plotSelection(tide, rest, t(50), HeightUnits.METRIC, listOf(contact), fromNotification = true)!!
        assertEquals(AlertTrigger.Eclipse, selected.trigger)
        assertEquals(contact, selected.moment)
        val nearHigh = plotSelection(tide, rest, t(59), HeightUnits.METRIC)!!
        assertEquals(AlertTrigger.TideExtreme(true), nearHigh.trigger)
        assertEquals(t(60), nearHigh.moment)
    }

    @Test fun signedCurrentPeaksAndSlacksHaveDistinctOffers() {
        val current = PredictionSeries.Current(emptyList(), listOf(
            CurrentEvent(t(10), -2.0, CurrentEventKind.MAX_EBB),
            CurrentEvent(t(40), 0.0, CurrentEventKind.SLACK),
            CurrentEvent(t(90), 1.8, CurrentEventKind.MAX_FLOOD)), null, null)
        assertEquals(AlertTrigger.CurrentPeak(false), plotSelection(current, rest, t(12), HeightUnits.METRIC)!!.trigger)
        assertEquals(AlertTrigger.CurrentPeak(true), plotSelection(current, rest, t(88), HeightUnits.METRIC)!!.trigger)
        assertEquals(t(40), plotSelection(current, rest, t(42), HeightUnits.METRIC)!!.moment)
        assertEquals(AlertTrigger.SlackWindowOpens, plotSelection(current, rest, t(65), HeightUnits.METRIC)!!.trigger)
        assertEquals(AlertTrigger.Eclipse, plotSelection(current, rest, t(65), HeightUnits.METRIC, listOf(t(65)))!!.trigger)
    }
}
