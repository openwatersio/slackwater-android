package io.openwaters.slackwater.android.shortcuts

import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.stations.FixSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueryFixTest {
    private val fix = LocationFix(Coordinate(48.0, -123.0), 35.0, 100)
    private val now = { 600_100L }
    private var permission = LocationPermission.APPROXIMATE
    private var cached = listOf(fix)
    private var calls = 0
    private var update: suspend () -> LocationFix? = { fix }
    private val source = object : FixSource {
        override fun permission() = permission
        override fun lastKnown() = cached
        override suspend fun current(): LocationFix? {
            calls++
            return update()
        }
    }

    @Test fun tenMinuteFixIsUsableAndNamedQueryNeverRefreshes() = runBlocking {
        assertEquals(fix.coordinate, queryFix(source, true, now))
        cached = listOf(fix.copy(elapsedRealtimeMillis = 99), fix.copy(accuracyMetres = null),
            fix.copy(coordinate = Coordinate(91.0, 0.0)))
        assertNull(queryFix(source, false, now))
        assertEquals(0, calls)
        assertEquals(fix.coordinate, queryFix(source, true, now))
        assertEquals(1, calls)
    }

    @Test fun deniedOrRevokedPermissionSuppressesFix() = runBlocking {
        permission = LocationPermission.NOT_GRANTED
        assertNull(queryFix(source, true, now))
        assertEquals(0, calls)
        permission = LocationPermission.PRECISE
        cached = emptyList()
        update = { permission = LocationPermission.NOT_GRANTED; fix }
        assertNull(queryFix(source, true, now))
    }

    @Test fun refreshCancelsAnUnresponsiveProvider() = runBlocking {
        cached = emptyList()
        var cancelled = false
        update = { try { awaitCancellation() } finally { cancelled = true } }
        assertNull(queryFix(source, true, now, timeoutMillis = 50))
        assertTrue(cancelled)
    }
}
