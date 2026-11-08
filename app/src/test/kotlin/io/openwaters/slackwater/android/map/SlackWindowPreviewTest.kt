package io.openwaters.slackwater.android.map

import java.time.Duration
import org.junit.Assert.*
import org.junit.Test

class SlackWindowPreviewTest {
    @Test fun offStillDrawsTheSlackInstant() {
        val curve = exampleSlackCurve(0.0)
        assertTrue(curve.windows.isEmpty())
        assertEquals(0.0, curve.valueAt(curve.slacks.single()), 0.0)
    }

    @Test fun comfortWindowWidensAtTheInterpolatedCrossings() {
        val curve = exampleSlackCurve(0.5)
        val run = curve.windows.single()
        assertEquals(-0.5, curve.valueAt(run.start), 0.0001)
        assertEquals(0.5, curve.valueAt(run.end), 0.0001)
        assertEquals(132, Duration.between(run.start, run.end).toMinutes())
        assertTrue(exampleSlackCurve(1.0).windows.single().start < run.start)
        val full = exampleSlackCurve(10.0).windows.single()
        assertEquals(Duration.ofHours(6), Duration.between(full.start, full.end))
    }
}
