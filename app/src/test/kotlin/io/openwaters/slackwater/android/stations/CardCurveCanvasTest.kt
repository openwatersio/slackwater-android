package io.openwaters.slackwater.android.stations

import androidx.compose.ui.graphics.Color
import io.openwaters.slackwater.android.core.station.CardCurve
import org.junit.Assert.assertEquals
import org.junit.Test

class CardCurveCanvasTest {
    // List §6.4: high teal, low amber, slack go green.
    @Test fun axisTimesPrintInTheirTurnsInk() {
        val ink = Color(0xFFF3F5EF)
        assertEquals(Color(0xFF2DD4BF), axisTimeColor(CardCurve.AxisInk.HIGH, ink))
        assertEquals(Color(0xFFFBBF24), axisTimeColor(CardCurve.AxisInk.LOW, ink))
        assertEquals(Color(0xFF88B868), axisTimeColor(CardCurve.AxisInk.SLACK, ink))
        assertEquals(ink, axisTimeColor(null, ink))
    }
}
