package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.tour.TourCoach
import io.openwaters.slackwater.android.core.tour.TourStep
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MoonTourTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredMoonMarkWaitsForThePageAndSkipsWhenCoverageIsLost() {
        val coach = TourCoach {}
        val station = StationId("noaa/9449880")
        coach.arm(seen = false)
        coach.begin(station, starsAvailable = false, moonAvailable = false)
        coach.advance(moonCardAvailable = true)

        val selected = mutableStateOf<Instant?>(null)
        compose.setContent {
            val available = selected.value?.let { rememberDetailMoon(it) != null }
            DetailTour(coach, coach.state.collectAsState().value, station, "Friday Harbor", null,
                remember { TourAnchors() }, rememberScrollState(), showSky = false,
                moonCardAvailable = available, beforeScroll = {})
        }
        compose.onNodeWithText("Moon details").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(TourStep.MOON_CARD, coach.state.value.step)
            selected.value = Instant.parse("2026-11-06T08:00:00Z")
        }
        compose.onNodeWithText("Moon details").assertExists()
        compose.runOnIdle { selected.value = Instant.parse("1900-11-06T08:00:00Z") }
        compose.waitUntil { coach.state.value.step == TourStep.STAR }
        compose.onNodeWithText("Moon details").assertDoesNotExist()
        compose.onNodeWithText("Favorites").assertExists()
    }
}
