package io.openwaters.slackwater.android.alerts

import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.planning.OccurrenceResolver
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.AndroidPredictionRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class FridayHarborTidesTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val rule = AlertRule(stationId = StationId("noaa/9449880"), trigger = AlertTrigger.TideExtreme(high = true))

    // The alert tests search from now, so check the year ahead of now. Their searches start up to two hours out.
    @Test fun consecutiveHighsCanBeMoreThanADayApartButNotMoreThanTheTestSearch() {
        val start = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val highs = runBlocking {
            OccurrenceResolver.resolve(rule, AndroidPredictionRepository(app), TimeSpan(start, start.plus(Duration.ofDays(365))),
                ZoneOffset.UTC, 48.0, -123.0, 0.5)
        }.map { it.event }
        assertTrue("Expected a year of highs, got ${highs.size}", highs.size > 600)
        val widest = highs.zipWithNext { a, b -> Duration.between(a, b) }.max()
        assertTrue("Widest gap $widest should exceed a day", widest > Duration.ofDays(1))
        val searched = NEXT_HIGH_SEARCH.minusHours(2)
        assertTrue("Widest gap $widest should fit in $searched", widest < searched)
    }
}
