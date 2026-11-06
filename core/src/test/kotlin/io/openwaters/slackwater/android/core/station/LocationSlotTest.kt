package io.openwaters.slackwater.android.core.station

import org.junit.Assert.*
import org.junit.Test

class LocationSlotTest {
    private val now = 10_000_000L
    private val here = Coordinate(48.4235, -123.3714)
    private val fresh = LocationFix(here, 25.0, now - 1_000)

    private fun slot(
        permission: LocationPermission = LocationPermission.PRECISE,
        promptRefused: Boolean = false,
        promptShowing: Boolean = false,
        fix: LocationFix? = fresh,
        requestFailed: Boolean = false,
    ) = locationSlot(LocationInputs(permission, promptRefused, promptShowing, fix, requestFailed), now)

    @Test fun anAllowedUsableFixLocates() {
        assertEquals(LocationSlot.Located(fresh, approximate = false), slot())
        assertEquals(LocationSlot.Located(fresh, approximate = true), slot(permission = LocationPermission.APPROXIMATE))
        assertEquals(here, slot().fixCoordinate)
    }

    @Test fun allowedWithoutAFixIsLocatingUntilTheRequestFails() {
        // §14.7: authorized, no fix.
        assertEquals(LocationSlot.Locating, slot(fix = null))
        assertNull(slot(fix = null).fixCoordinate)
        assertEquals(LocationSlot.Failed, slot(fix = null, requestFailed = true))
        // A usable fix wins over an earlier failure.
        assertTrue(slot(requestFailed = true) is LocationSlot.Located)
    }

    @Test fun notAllowedOffersThePromptUntilTheSystemRefusesIt() {
        // §14.6: never asked, denied once, or an "Only this time" grant that lapsed all offer the prompt.
        assertEquals(LocationSlot.NotDetermined, slot(permission = LocationPermission.NOT_GRANTED))
        // §14.5: a fix counts only while location is allowed.
        val denied = slot(permission = LocationPermission.NOT_GRANTED, promptRefused = true)
        assertEquals(LocationSlot.Denied, denied)
        assertNull(denied.fixCoordinate)
        // Once allowed again, a refusal on record no longer matters.
        assertTrue(slot(promptRefused = true) is LocationSlot.Located)
    }

    @Test fun aPromptIsRefusedOnlyWhenTheSystemStopsAsking() {
        // First ask, denied: the flag turns true, so the prompt will appear again.
        assertFalse(promptRefused(granted = false, rationaleBefore = false, rationaleAfter = true, answeredAfterMillis = 2_000))
        // Second ask, denied: the flag turns false, permanently.
        assertTrue(promptRefused(granted = false, rationaleBefore = true, rationaleAfter = false, answeredAfterMillis = 2_000))
        // The first dialog dismissed with Back leaves the flag false but a person saw it.
        assertFalse(promptRefused(granted = false, rationaleBefore = false, rationaleAfter = false, answeredAfterMillis = 1_500))
        // After a permanent denial the system answers at once without a dialog.
        assertTrue(promptRefused(granted = false, rationaleBefore = false, rationaleAfter = false, answeredAfterMillis = 40))
        assertFalse(promptRefused(granted = true, rationaleBefore = true, rationaleAfter = false, answeredAfterMillis = 40))
    }

    @Test fun theSystemPromptShowsTheLocatingState() {
        assertEquals(LocationSlot.Locating, slot(permission = LocationPermission.NOT_GRANTED, promptShowing = true))
        assertEquals(LocationSlot.Locating, slot(promptShowing = true))
    }

    @Test fun staleOrInaccurateFixesAreNotUsable() {
        assertTrue(LocationFix(here, 25.0, now - FIX_MAXIMUM_AGE_MILLIS).isUsable(now))
        assertFalse(LocationFix(here, 25.0, now - FIX_MAXIMUM_AGE_MILLIS - 1).isUsable(now))
        assertFalse(LocationFix(here, 25.0, now + 1).isUsable(now))
        // Android reports 0 for a fix with no accuracy estimate.
        for (accuracy in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse("$accuracy", LocationFix(here, accuracy, now).isUsable(now))
        }
        assertFalse(LocationFix(Coordinate(Double.NaN, 0.0), 10.0, now).isUsable(now))
        assertEquals(LocationSlot.Locating, slot(fix = LocationFix(here, 25.0, now - 700_000)))
    }

    @Test fun coordinatesPrintWithHemispheresAndThreeDecimals() {
        // 48.4235 is stored just below the half, so it rounds down, as iOS prints it.
        assertEquals("48.423°N, 123.371°W", formatCoordinate(here))
        assertEquals("0.000°S, 0.000°W", formatCoordinate(Coordinate(-0.0004, -0.0004)))
        assertThrows(IllegalArgumentException::class.java) { formatCoordinate(Coordinate(Double.NaN, 0.0)) }
        assertEquals("33.857°S, 151.215°E", formatCoordinate(Coordinate(-33.8568, 151.2153)))
        assertEquals("0.000°N, 0.000°E", formatCoordinate(Coordinate(0.0, 0.0)))
    }
}
