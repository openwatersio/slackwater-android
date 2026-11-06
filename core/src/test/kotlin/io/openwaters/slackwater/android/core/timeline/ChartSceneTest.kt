package io.openwaters.slackwater.android.core.timeline

import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.ChartModel
import io.openwaters.slackwater.android.core.prediction.PredictionSeries
import io.openwaters.slackwater.android.core.station.SpeedUnitKind
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ChartSceneTest {
    private val origin = Instant.parse("2024-04-08T00:00:00Z")
    private fun t(minute: Long) = origin.plusSeconds(minute * 60)

    @Test
    fun weakOneSidedWaterHasNoSlackAndDerivedGatesNeverInventAMagnitude() {
        val weak = PredictionSeries.Current(
            listOf(CurrentPoint(t(0), 0.1), CurrentPoint(t(60), 0.2)),
            emptyList(),
            90.0,
            270.0
        )
        val scene = ChartScene(weak, emptyList(), 0.5, false)
        assertTrue(scene.windows.isEmpty())
        assertTrue(scene.water.isEmpty())
        assertEquals(
            "Flooding",
            scene.lead(t(30), false, SpeedUnitKind.KNOTS, false, Locale.US).state
        )
        val derived = PredictionSeries.Derived(
            listOf(
                PredictionSeries.DerivedSlack(t(0), ExtremeKind.LOW),
                PredictionSeries.DerivedSlack(t(120), ExtremeKind.HIGH)
            )
        )
        val schematic = ChartScene(derived, emptyList(), 0.5, false)
        assertEquals(1.0, schematic.at(t(60))!!, 0.001)
        assertEquals(0.0, schematic.at(t(-10))!!, 0.0)
        assertEquals(
            ChartLead("Flooding"),
            schematic.lead(t(60), false, SpeedUnitKind.KNOTS, false, Locale.US)
        )
        assertEquals(
            ChartLead("Slack"),
            schematic.lead(t(12), true, SpeedUnitKind.KNOTS, true, Locale.US)
        )
    }

    @Test
    fun visualStopAndTapShareTheEarlierOfDaylightAndWaterWithinCoverage() {
        val points = listOf(TidePoint(t(0), 0.0), TidePoint(t(180), 1.0))
        val series = PredictionSeries.Tide(
            points,
            emptyList(),
            listOf(TideExtreme(t(100), 1.0, ExtremeKind.HIGH))
        )
        val day = TimelineDay(LocalDate.of(2024, 4, 8), sunrise = t(100), sunset = t(200))
        val sky = ChartScene(series, listOf(day), 0.5, true)
        assertEquals(ChartMark(t(100), ChartStop.Sunrise), sky.commentary(t(20)))
        assertNull(sky.commentary(t(100).minusSeconds(1)))
        assertEquals(
            ChartStop.High,
            ChartScene(series, listOf(day), 0.5, false).commentary(t(20))?.stop
        )
    }

    @Test
    fun boundedWindowsHaveAnOpeningAndClosingButACroppedEndHasNoClosing() {
        val samples = listOf(-1.0, 0.0, 1.0).mapIndexed { index, value ->
            CurrentPoint(
                t(index * 60L),
                value
            )
        }
        val series = PredictionSeries.Current(
            samples,
            listOf(CurrentEvent(t(60), 0.0, CurrentEventKind.SLACK)),
            0.0,
            180.0
        )
        val scene = ChartScene(series, emptyList(), 0.5, false)
        assertEquals(
            listOf(ChartStop.Open, ChartStop.Slack, ChartStop.Close),
            scene.water.map { it.stop })
        assertEquals(t(30), scene.water.first().at)
        val clipped = ChartScene(series.copy(points = samples.take(2)), emptyList(), 0.5, false)
        assertFalse(clipped.water.any { it.stop == ChartStop.Close })
    }

    @Test
    fun actualModelReadingUsesSelectedSecondAndClassifiesYearOncePerModel() {
        var calls = 0
        val model = ChartModel.Current({ a, _, _ -> listOf(CurrentPoint(a, 2.25)) }, { start, end ->
            calls++
            assertEquals(Instant.parse("2024-01-01T08:00:00Z"), start)
            assertEquals(Instant.parse("2025-01-01T08:00:00Z").minusNanos(1), end)
            listOf(CurrentEvent(t(10), -3.0, CurrentEventKind.MAX_EBB))
        })
        val series = PredictionSeries.Current(
            listOf(CurrentPoint(t(0), 1.0), CurrentPoint(t(60), 1.0)),
            emptyList(),
            90.0,
            270.0
        )
        val scene = ChartScene(series, emptyList(), 0.5, false)
        assertEquals(
            "2.3",
            scene.lead(t(20), false, SpeedUnitKind.KNOTS, false, Locale.US, model).value
        )
        repeat(2) { assertFalse(model.isReach(2024, ZoneId.of("America/Vancouver"))) }
        assertEquals(1, calls)
    }

    @Test
    fun localSolarEclipseChangesTheSkyAtPeakAndScopeExcludesDistantStations() {
        val dallas = SkyScene(32.78, -96.8, origin, t(1440), emptyList())
        val peak = dallas.peaks.single()
        assertTrue(dallas.frame(peak)!!.obscuration > 0.98)
        assertEquals(0.0, dallas.frame(t(0))!!.obscuration, 0.0)
        val night = dallas.frame(t(480))!!
        assertTrue(night.stars.size > 20)
        assertTrue(SkyScene(-33.9, 151.2, origin, t(1440), emptyList()).peaks.isEmpty())
    }

    @Test
    fun selectingTheFastestTideStopAdvancesCommentaryAndLeadKeepsTheMeasuredRate() {
        val points = listOf(TidePoint(t(0), 0.0), TidePoint(t(60), 1.0), TidePoint(t(120), 1.5))
        val rates =
            listOf(TideRatePoint(t(0), 0.5), TideRatePoint(t(60), 0.8), TideRatePoint(t(120), 0.0))
        val series =
            PredictionSeries.Tide(points, rates, listOf(TideExtreme(t(120), 1.5, ExtremeKind.HIGH)))
        val scene = ChartScene(series, emptyList(), 0.5, false)
        assertEquals(ChartStop.Rise, scene.commentary(t(0))?.stop)
        assertEquals(ChartStop.High, scene.commentary(t(60))?.stop)
        val fast = scene.lead(t(60), false, SpeedUnitKind.KNOTS, false, Locale.US)
        assertEquals(0.8, fast.rate!!, 0.0)
        assertEquals("Rising 0.80 m/hr", fast.state)
        assertEquals("High", scene.lead(t(120), false, SpeedUnitKind.KNOTS, false, Locale.US).state)
    }

    @Test
    fun drawingAHaloLeavesSlackJoinedToBlueInsideAWindow() {
        val points = listOf(-1.0, 0.0, 1.0).mapIndexed { i, v -> CurrentPoint(t(i * 60L), v) }
        val series = PredictionSeries.Current(
            points,
            listOf(CurrentEvent(t(60), 0.0, CurrentEventKind.SLACK)),
            0.0,
            180.0
        )
        assertEquals(
            listOf(t(30) to t(90)),
            ChartScene(series, emptyList(), 0.5, false).slackInkEdges
        )
        assertEquals(
            listOf(t(50) to t(70)),
            ChartScene(series, emptyList(), 0.0, false).slackInkEdges
        )
    }

    @Test
    fun aLocallyPartialEclipseRetainsAnOffsetBite() {
        val vancouver = SkyScene(49.28, -123.12, origin, t(1440), emptyList())
        val peak = vancouver.peaks.single()
        val maximum = vancouver.frame(peak)!!.obscuration
        assertTrue(maximum > 0.0 && maximum < 0.5)
        assertTrue(vancouver.frame(peak.minusSeconds(1200))!!.obscuration < maximum)
    }

}
