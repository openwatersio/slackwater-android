package io.openwaters.slackwater.android.stations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.SlackwaterTheme
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.LocationFix
import io.openwaters.slackwater.android.core.station.LocationSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** List §4: the position shares the My Location heading's row, at its trailing end, while it fits. */
class LocationHeadingTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val heading get() = compose.onNodeWithContentDescription(context.getString(R.string.list_my_location))
    private val position get() = compose.onAllNodesWithTag("location-position")

    private fun show(slot: LocationSlot, width: Dp = 360.dp, fontScale: Float = 1f) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            SlackwaterTheme { Box(Modifier.width(width).testTag("row")) { LocationHeading(slot) } }
        }
    }

    private fun located(approximate: Boolean) =
        LocationSlot.Located(LocationFix(Coordinate(48.4234, -123.3705), accuracyMetres = 10.0, elapsedRealtimeMillis = 0), approximate)

    @Test fun thePositionSitsOppositeTheLabelOnOneRow() {
        show(located(approximate = false))
        val label = heading.getBoundsInRoot()
        val fix = compose.onNodeWithText("48.423°N, 123.371°W").getBoundsInRoot()
        assertTrue("the position starts after the label", fix.left >= label.right)
        assertTrue("the position shares the label's row", fix.top < label.bottom && label.top < fix.bottom)
        // The screen can be narrower than the width asked for, so measure the row itself.
        assertEquals(compose.onNodeWithTag("row").getBoundsInRoot().right.value, fix.right.value, 1f)
    }

    @Test fun aPositionWithNoRoomBesideTheLabelMovesBelowIt() {
        show(located(approximate = false), width = 240.dp, fontScale = 2f)
        val label = heading.getBoundsInRoot()
        val fix = position[0].getBoundsInRoot()
        assertTrue("the position sits under the label", fix.top >= label.bottom)
        assertTrue("the position keeps the row's width", fix.right - fix.left > 120.dp)
    }

    @Test fun anApproximateFixNamesItselfInsteadOfAPosition() {
        show(located(approximate = true))
        position[0].assertTextEquals(context.getString(R.string.list_approximate_location))
    }

    @Test fun withoutAFixTheHeadingStandsAlone() {
        show(LocationSlot.Locating)
        heading.assertIsDisplayed()
        assertEquals(0, position.fetchSemanticsNodes().size)
    }
}
