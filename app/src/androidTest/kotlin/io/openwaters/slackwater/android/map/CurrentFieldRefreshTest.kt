package io.openwaters.slackwater.android.map

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.openwaters.slackwater.android.core.map.FillCell
import io.openwaters.slackwater.android.core.station.Coordinate
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class CurrentFieldRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previousFieldRemainsVisibleWhileANewTimeIsEvaluated() {
        val time = Instant.parse("2026-11-07T16:00:00Z")
        val clock = mutableStateOf(time)
        val shape = FillCell(listOf(Coordinate(48.0, -123.0), Coordinate(48.0, -122.9), Coordinate(48.1, -123.0)), 2.0, 90.0)
        val covered = CurrentMapFields(emptyList(), listOf(shape)).features()
        val uncovered = CurrentMapFields(emptyList(), emptyList()).features()
        val nextStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visible = AtomicReference<CurrentFieldFeatures?>()
        compose.setContent {
            val frame = rememberCurrentFieldFeatures(clock.value, 0) { at ->
                if (at == time) covered else {
                    nextStarted.complete(Unit)
                    release.await()
                    uncovered
                }
            }
            SideEffect { visible.set(frame) }
        }
        compose.waitUntil(10_000) { visible.get() === covered }
        compose.runOnIdle { clock.value = time.plusSeconds(15) }
        compose.waitUntil(10_000) { nextStarted.isCompleted }
        assertSame(covered, visible.get())
        release.complete(Unit)
        compose.waitUntil(10_000) { visible.get() === uncovered }
    }
}
