package io.openwaters.slackwater.android.detail

import org.junit.Assert.*
import org.junit.Test

class DetailHingeTest {
    @Test fun onlyAWiderScreenDuringRecentOpeningReceivesAHandoff() {
        val readings = HingeReadings()
        readings.width(360, 10)
        readings.angle(12f, 100)
        readings.angle(35f, 150)
        readings.width(720, 300)
        assertTrue(readings.pending)
        readings.take()
        readings.angle(25f, 400)
        readings.width(360, 500)
        assertFalse(readings.pending)
        readings.width(720, 600)
        assertFalse(readings.pending)
        readings.angle(40f, 650)
        readings.width(810, 3000)
        assertFalse(readings.pending)
    }

    @Test fun waitsForFirstMotionThenEasesTowardFlatOrAnIdleHinge() {
        val stationary = HingeFollow(60f, 21600.0, 1000)
        assertEquals(0.0, stationary.frame(60f, 1000, 1999), 0.0)
        assertTrue(stationary.frame(60f, 1000, 2001) > 0)
        assertEquals(1.0, stationary.frame(60f, 1000, 3000), 0.0)
        val moving = HingeFollow(60f, 21600.0, 1000)
        val halfway = moving.frame(120f, 1100, 1100)
        assertTrue(halfway in .30.. .33)
        assertTrue(moving.frame(110f, 1200, 1200) > halfway)
        assertTrue(moving.frame(110f, 1200, 1449) < .5)
        assertTrue(moving.frame(110f, 1200, 1500) > .5)
        assertEquals(1.0, moving.frame(110f, 1200, 2300), 0.0)
    }
}
