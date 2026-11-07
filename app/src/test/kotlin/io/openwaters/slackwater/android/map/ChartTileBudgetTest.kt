package io.openwaters.slackwater.android.map

import io.openwaters.slackwater.android.core.map.ChartPackSpec
import org.junit.Assert.*
import org.junit.Test

class ChartTileBudgetTest {
    private val world = ChartPackSpec("world", -85.0511, -180.0, 85.0511, 180.0, 0, 0)
    private val west = ChartPackSpec("station/A", -1.0, -180.0, 0.0, 0.0, 1, 1)
    private val east = west.copy(key = "station/B", west = 0.0, east = 180.0)

    @Test fun budgetDeduplicatesSharedTilesAndPrioritizesWorldThenKey() {
        val shared = west.copy(key = "station/C")
        assertEquals(setOf(east.key), deferredChartKeys(listOf(east, shared, west, world), emptySet(), 2))
        assertTrue(deferredChartKeys(listOf(east, world), emptySet(), 2).isEmpty())
    }

    @Test fun completedRegionRetainsItsBudgetWhenNewEarlierKeyArrives() {
        assertEquals(setOf(west.key), deferredChartKeys(listOf(west, world, east), setOf(east.key), 2))
    }

    @Test fun partialRegionGetsPriorityOverANewRegionButNotACompleteOne() {
        assertEquals(setOf(west.key), deferredChartKeys(listOf(west, world, east), emptySet(), 2, setOf(east.key)))
        assertEquals(setOf(east.key), deferredChartKeys(listOf(west, east), setOf(west.key), 1, setOf(east.key)))
    }

    @Test fun tierBoundsCountDistinctXyzAndEnforceFiordLimit() {
        val low = world.copy(maxZoom = 4)
        assertTrue(deferredChartKeys(listOf(low), emptySet(), 341).isEmpty())
        assertEquals(setOf(low.key), deferredChartKeys(listOf(low), emptySet(), 340))
        val tooLarge = world.copy(key = "station/large", minZoom = 12, maxZoom = 12)
        assertEquals(setOf(tooLarge.key), deferredChartKeys(listOf(low, tooLarge), emptySet()))
    }
}
