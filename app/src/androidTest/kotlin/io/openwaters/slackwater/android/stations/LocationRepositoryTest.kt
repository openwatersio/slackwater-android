package io.openwaters.slackwater.android.stations

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.location.Criteria
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.FIX_MAXIMUM_AGE_MILLIS
import io.openwaters.slackwater.android.core.station.LocationFix
import io.openwaters.slackwater.android.core.station.LocationPermission
import io.openwaters.slackwater.android.core.station.LocationSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocationRepositoryTest {
    private val now = 50_000_000L
    private val here = Coordinate(48.42, -123.37)

    private class FakeSource(
        var permission: LocationPermission = LocationPermission.PRECISE,
        var cached: List<LocationFix> = emptyList(),
        var next: LocationFix? = null,
    ) : FixSource {
        var requests = 0
        override fun permission() = permission
        override fun lastKnown() = cached
        override suspend fun current(): LocationFix? = next.also { requests++ }
    }

    private fun fix(ageMillis: Long, accuracy: Double? = 30.0, at: Coordinate = here) = LocationFix(at, accuracy, now - ageMillis)

    @Test fun aNewFixReplacesTheNewestUsableCachedOne() = runBlocking {
        val newest = fix(ageMillis = 10_000, at = Coordinate(1.0, 1.0))
        val source = FakeSource(cached = listOf(fix(20_000), newest, fix(FIX_MAXIMUM_AGE_MILLIS + 1, at = Coordinate(2.0, 2.0))))
        val repository = LocationRepository(source) { now }
        repository.refresh()
        // The fresh request failed, so the cached fix stands.
        assertEquals(LocationSlot.Located(newest, approximate = false), repository.slot())
        source.next = fix(0, at = Coordinate(3.0, 3.0))
        repository.refresh()
        assertEquals(Coordinate(3.0, 3.0), (repository.slot() as LocationSlot.Located).fix.coordinate)
    }

    @Test fun aFailedRequestWithNothingCachedStopsTheSpinner() = runBlocking {
        val repository = LocationRepository(FakeSource(cached = listOf(fix(0, accuracy = 0.0)))) { now }
        assertEquals(LocationSlot.Locating, repository.slot())
        repository.refresh()
        assertEquals(LocationSlot.Failed, repository.slot())
    }

    @Test fun aHeldFixThatAgesOutDuringAFailedRequestStopsTheSpinner() = runBlocking {
        var clock = now
        val source = object : FixSource {
            override fun permission() = LocationPermission.PRECISE
            override fun lastKnown() = listOf(fix(FIX_MAXIMUM_AGE_MILLIS - 10_000))
            override suspend fun current(): LocationFix? {
                clock += 30_000
                return null
            }
        }
        val repository = LocationRepository(source) { clock }
        repository.refresh()
        assertEquals(LocationSlot.Failed, repository.slot())
    }

    @Test fun aSlowOlderAnswerNeverReplacesANewerFix() = runBlocking {
        val older = fix(ageMillis = 60_000, at = Coordinate(1.0, 1.0))
        val newer = fix(ageMillis = 1_000, at = Coordinate(2.0, 2.0))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val source = object : FixSource {
            override fun permission() = LocationPermission.PRECISE
            override fun lastKnown() = emptyList<LocationFix>()
            override suspend fun current(): LocationFix? = if (calls++ == 0) { gate.await(); older } else newer
        }
        val repository = LocationRepository(source) { now }
        val slow = async(Dispatchers.Default) { repository.refresh() }
        while (calls == 0) delay(10)
        repository.refresh()
        gate.complete(Unit)
        slow.await()
        assertEquals(newer, repository.inputs.value.fix)
    }

    @Test fun aChangedGrantDropsTheHeldFix() = runBlocking {
        val source = FakeSource(permission = LocationPermission.APPROXIMATE, next = fix(0))
        val repository = LocationRepository(source) { now }
        repository.refresh()
        assertEquals(LocationSlot.Located(fix(0), approximate = true), repository.slot())
        repository.promptShown()
        source.permission = LocationPermission.PRECISE
        repository.promptAnswered(refused = false)
        assertNull(repository.inputs.value.fix)
        assertEquals(LocationSlot.Locating, repository.slot())
    }

    @Test fun aFixAgesOutWithoutANewEvent() = runBlocking {
        var clock = now
        val repository = LocationRepository(FakeSource(next = fix(0))) { clock }
        repository.refresh()
        assertTrue(repository.slot() is LocationSlot.Located)
        clock += FIX_MAXIMUM_AGE_MILLIS + 1
        assertEquals(LocationSlot.Locating, repository.slot())
    }

    @Test fun revokedPermissionDropsTheFixAndNeverRequests() = runBlocking {
        val source = FakeSource(next = fix(0))
        val repository = LocationRepository(source) { now }
        repository.refresh()
        source.permission = LocationPermission.NOT_GRANTED
        // A change made in Settings shows before the next refresh.
        assertEquals(LocationSlot.NotDetermined, repository.slot())
        assertNull(repository.inputs.value.fix)
        val requests = source.requests
        repository.refresh()
        assertEquals(requests, source.requests)
    }

    @Test fun onlyARefusedPromptShowsTheSettingsCard() = runBlocking {
        val source = FakeSource(permission = LocationPermission.NOT_GRANTED)
        val repository = LocationRepository(source) { now }
        repository.refresh()
        assertEquals(LocationSlot.NotDetermined, repository.slot())
        repository.promptShown()
        repository.promptAnswered(refused = false)
        assertEquals(LocationSlot.NotDetermined, repository.slot())
        repository.promptShown()
        repository.promptAnswered(refused = true)
        assertEquals(LocationSlot.Denied, repository.slot())
        // Granting in Settings clears the refusal.
        source.permission = LocationPermission.PRECISE
        assertFalse(repository.inputs.value.promptRefused && repository.slot() == LocationSlot.Denied)
        source.permission = LocationPermission.NOT_GRANTED
        assertEquals(LocationSlot.NotDetermined, repository.slot())
    }

    @Test fun aRetryShowsTheSpinnerAndACancelledRequestIsNotAFailure() = runBlocking {
        val answer = kotlinx.coroutines.CompletableDeferred<LocationFix?>()
        var calls = 0
        val source = object : FixSource {
            override fun permission() = LocationPermission.PRECISE
            override fun lastKnown() = emptyList<LocationFix>()
            override suspend fun current(): LocationFix? = if (calls++ == 0) null else answer.await()
        }
        val repository = LocationRepository(source) { now }
        repository.refresh()
        assertEquals(LocationSlot.Failed, repository.slot())
        val retry = launch(Dispatchers.Default) { repository.refresh() }
        while (calls < 2) delay(10)
        assertEquals(LocationSlot.Locating, repository.slot())
        retry.cancelAndJoin()
        assertEquals(LocationSlot.Locating, repository.slot())
    }

    @Test fun aRequestFromAnEarlierGrantDoesNotFailTheNewOne() = runBlocking {
        val answer = kotlinx.coroutines.CompletableDeferred<LocationFix?>()
        val source = FakeSource(permission = LocationPermission.APPROXIMATE)
        val slowSource = object : FixSource by source {
            override suspend fun current(): LocationFix? = answer.await()
        }
        val repository = LocationRepository(slowSource) { now }
        val slow = async(Dispatchers.Default) { repository.refresh() }
        while (repository.inputs.value.permission != LocationPermission.APPROXIMATE) delay(10)
        delay(50)
        source.permission = LocationPermission.PRECISE
        assertEquals(LocationSlot.Locating, repository.slot())
        answer.complete(null)
        slow.await()
        assertEquals(LocationSlot.Locating, repository.slot())
    }

    @Test fun aFinishedRequestDoesNotFailWhileAnotherIsStillAsking() = runBlocking {
        val slowAnswer = kotlinx.coroutines.CompletableDeferred<LocationFix?>()
        var calls = 0
        val source = object : FixSource {
            override fun permission() = LocationPermission.PRECISE
            override fun lastKnown() = emptyList<LocationFix>()
            override suspend fun current(): LocationFix? = if (calls++ == 0) slowAnswer.await() else null
        }
        val repository = LocationRepository(source) { now }
        val slow = async(Dispatchers.Default) { repository.refresh() }
        while (calls == 0) delay(10)
        repository.refresh()
        assertEquals(LocationSlot.Locating, repository.slot())
        slowAnswer.complete(fix(0))
        slow.await()
        assertTrue(repository.slot() is LocationSlot.Located)
    }

    @Test fun thePromptShowsLocatingAndItsAnswerRereadsThePermission() = runBlocking {
        val source = FakeSource(permission = LocationPermission.NOT_GRANTED)
        val repository = LocationRepository(source) { now }
        repository.refresh()
        assertEquals(LocationSlot.NotDetermined, repository.slot())
        repository.promptShown()
        assertEquals(LocationSlot.Locating, repository.slot())
        source.permission = LocationPermission.APPROXIMATE
        repository.promptAnswered(refused = false)
        assertEquals(LocationPermission.APPROXIMATE, repository.inputs.value.permission)
        source.next = fix(0)
        repository.refresh()
        assertEquals(LocationSlot.Located(fix(0), approximate = true), repository.slot())
    }

    @Test fun thePlatformSourceReadsATestProvider() = withTestProviders(answering = null) { source, report ->
        val fix = request(source, report)
        assertEquals(here.latitude, fix.coordinate.latitude, 1e-9)
        assertEquals(here.longitude, fix.coordinate.longitude, 1e-9)
        assertEquals(20.0, fix.accuracyMetres!!, 0.0)
        assertTrue(fix.isUsable(SystemClock.elapsedRealtime()))
        assertTrue(source.lastKnown().any { it.coordinate == fix.coordinate })
    }

    @Test fun aSilentProviderFallsBackToGps() = withTestProviders(answering = LocationManager.GPS_PROVIDER) { source, report ->
        val fix = request(source, report)
        assertEquals(here.latitude, fix.coordinate.latitude, 1e-9)
    }

    // Report while the request waits, whether or not the platform answers from its cache.
    private suspend fun request(source: PlatformFixSource, report: () -> Unit): LocationFix = coroutineScope {
        report()
        val request = async(Dispatchers.Default) { source.current() }
        while (request.isActive) {
            delay(250)
            report()
        }
        assertNotNull(request.await())!!
    }

    /** Replaces the platform providers with test ones. [answering] alone reports positions; null means all do. */
    private fun withTestProviders(answering: String?, block: suspend (PlatformFixSource, () -> Unit) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // `pm grant` works on every API level CI runs; UiAutomation's two-argument grant needs API 28. Granting is
        // left in place because revoking a runtime permission kills the test process.
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            shell("pm grant ${context.packageName} $permission")
        }
        shell("appops set ${context.packageName} android:mock_location allow")
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        // The platform answers current-location requests only for an app in the foreground, as the list will be.
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            for (name in providers) {
                manager.addTestProvider(name, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                manager.setTestProviderEnabled(name, true)
            }
            val report = {
                for (name in providers.filter { answering == null || it == answering }) {
                    manager.setTestProviderLocation(name, Location(name).apply {
                        latitude = here.latitude
                        longitude = here.longitude
                        accuracy = 20f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    })
                }
            }
            block(PlatformFixSource(context, providerTimeoutMillis = 2_000), report)
        } finally {
            for (name in providers) runCatching { manager.removeTestProvider(name) }
            activity.close()
            shell("appops set ${context.packageName} android:mock_location default")
        }
    }

    private fun <T> assertNotNull(value: T?): T? = value.also { org.junit.Assert.assertNotNull(it) }

    // The command runs asynchronously until its output is read to the end.
    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
