package io.openwaters.slackwater.android.map

import io.openwaters.slackwater.android.core.map.ChartPackSpec
import org.junit.Assert.*
import org.junit.Test

class ChartPackChangesTest {
    private val spec = ChartPackSpec("area/5/2/3", 1.0, 2.0, 3.0, 4.0, 5, 8)
    private val stored = StoredChartArea(12, spec.key, spec, false)
    private val desired = mapOf(spec.key to spec)

    @Test fun removalWaitsForNativeListingCreationAndDeletion() {
        assertFalse(removalComplete(false, 0, 0))
        assertFalse(removalComplete(true, 0, 1))
        assertFalse(removalComplete(true, 1, 0))
        assertTrue(removalComplete(true, 0, 0))
        assertEquals(setOf(12L), chartPackChanges(emptyMap(), listOf(stored), emptySet()).delete)
    }

    @Test fun movedBoundsZoomOrStaleStyleReplaceOnlyAfterDeletion() {
        for (previous in listOf(spec.copy(west = 1.5), spec.copy(maxZoom = 7), null)) {
            val old = stored.copy(spec = previous)
            assertEquals(ChartPackChanges(setOf(12L), emptyList()), chartPackChanges(desired, listOf(old), emptySet()))
            assertEquals(ChartPackChanges(emptySet(), emptyList()), chartPackChanges(desired, listOf(old.copy(deleting = true)), emptySet()))
        }
        assertEquals(listOf(spec), chartPackChanges(desired, emptyList(), emptySet()).create)
        assertTrue(chartPackChanges(desired, emptyList(), setOf(spec.key)).create.isEmpty())
    }

    @Test fun pendingFixKeepsOnlyOwnedCurrentStyleVicinity() {
        val other = listOf(stored, stored.copy(key = "station/N", spec = spec.copy(key = "station/N")),
            stored.copy(key = "area/5/4/4", spec = null))
        assertEquals(desired, wantedChartAreas(emptyMap(), other, true, false))
        assertTrue(wantedChartAreas(emptyMap(), other, true, true).isEmpty())
        assertTrue(wantedChartAreas(emptyMap(), other, false, false).isEmpty())
    }

    @Test fun failedDeletionStaysRetiredUntilExplicitQuotaRetry() {
        val deletion = ChartPackDeletion()
        assertTrue(deletion.begin())
        assertFalse(deletion.begin())
        deletion.failed(ChartPackProblem.Quota)
        deletion.retry(manual = false)
        assertEquals(ChartPackProblem.Quota, deletion.error)
        assertFalse(deletion.begin())
        deletion.retry(manual = true)
        assertTrue(deletion.requested)
        assertTrue(deletion.begin())
        deletion.failed(ChartPackProblem.Storage)
        deletion.retry(manual = false)
        assertTrue(deletion.requested)
        assertTrue(deletion.begin())
    }

    @Test fun serializedBoundsRemainTheSameArea() {
        val existing = stored.copy(spec = spec.copy(west = Math.nextUp(spec.west)))
        assertTrue(chartPackChanges(desired, listOf(existing), emptySet()).delete.isEmpty())
    }

    @Test fun failedDeletionCanBeRetainedButNativeDeletionCannotBeInterrupted() {
        val deletion = ChartPackDeletion()
        deletion.begin()
        deletion.retain()
        assertTrue(deletion.requested)
        deletion.failed(ChartPackProblem.Storage)
        deletion.retain()
        assertFalse(deletion.requested)
        assertNull(deletion.error)
    }

    @Test fun unavailableStationInputsRetainBothTiersUntilRecoveryOrRemoval() {
        val station = spec.copy(key = "station/N")
        val records = listOf(stored, stored.copy(key = station.key, spec = station))
        assertEquals(setOf(spec.key, station.key), wantedChartAreas(emptyMap(), records, false, false, true).keys)
        assertTrue(wantedChartAreas(emptyMap(), records, false, true, true).isEmpty())
        assertTrue(wantedChartAreas(emptyMap(), records, false, false, false).isEmpty())
    }

    @Test fun dataSaverAndUserPauseIndependentlySuspendDownloads() {
        val summary = ChartPackSummary(total = 1)
        assertFalse(summary.copy(dataSaver = true).downloading)
        assertFalse(summary.copy(paused = true).downloading)
        assertTrue(summary.downloading)
    }

    @Test fun duplicateOfRetainedAreaIsRemoved() {
        val changes = chartPackChanges(desired, listOf(stored, stored.copy(id = 13)), emptySet())
        assertEquals(ChartPackChanges(setOf(13L), emptyList()), changes)
    }
}
